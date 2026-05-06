using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Windows;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Models;
using LocalBridge.Desktop.Repositories;

namespace LocalBridge.Desktop.Services;

public sealed partial class FileTransferService : IFileTransferService, IFileTransferEndpointHandler
{
    private readonly IConnectionService _connectionService;
    private readonly BluetoothConnectionService _bluetoothConnectionService;
    private readonly ISettingsRepository _settingsRepository;
    private readonly ITransferRepository _transferRepository;
    private readonly ILoggerService _loggerService;
    private readonly HttpClient _httpClient;
    private readonly SemaphoreSlim _lifecycleGate = new(1, 1);
    private readonly SemaphoreSlim _transfersGate = new(1, 1);
    private readonly object _outgoingGate = new();
    private readonly object _incomingGate = new();
    private readonly List<TransferItem> _transfers = [];
    private readonly Dictionary<string, OutgoingTransferRuntime> _outgoingTransfers = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, IncomingTransferRuntime> _incomingTransfers = new(StringComparer.OrdinalIgnoreCase);
    private readonly string _incomingTempRoot;
    private readonly long _receiveFlushThresholdBytes = Math.Max(32L * 1024 * 1024, AppConstants.TransferChunkSizeBytes * 2L);
    private long _lastProgressPersistenceUtcTicks;
    private bool _isInitialized;

    public FileTransferService(
        IConnectionService connectionService,
        BluetoothConnectionService bluetoothConnectionService,
        ISettingsRepository settingsRepository,
        ITransferRepository transferRepository,
        ILoggerService loggerService)
    {
        _connectionService = connectionService;
        _bluetoothConnectionService = bluetoothConnectionService;
        _settingsRepository = settingsRepository;
        _transferRepository = transferRepository;
        _loggerService = loggerService;
        var handler = new SocketsHttpHandler
        {
            UseProxy = false,
            AutomaticDecompression = DecompressionMethods.None,
            ConnectTimeout = TimeSpan.FromSeconds(15),
            Expect100ContinueTimeout = TimeSpan.Zero,
            MaxConnectionsPerServer = 8
        };

        _httpClient = new HttpClient(handler)
        {
            Timeout = TimeSpan.FromSeconds(AppConstants.TransferRequestTimeoutSeconds),
            DefaultRequestVersion = HttpVersion.Version11,
            DefaultVersionPolicy = HttpVersionPolicy.RequestVersionOrLower
        };
        _httpClient.DefaultRequestHeaders.ExpectContinue = false;

        _incomingTempRoot = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            AppConstants.AppName,
            "Desktop",
            "incoming-temp");
    }

    public event Action<TransferItem>? TransferAdded;

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        await _lifecycleGate.WaitAsync(cancellationToken);
        try
        {
            if (_isInitialized)
            {
                return;
            }

            Directory.CreateDirectory(_incomingTempRoot);
            CleanupStaleTempFiles();

            var loadedTransfers = await _transferRepository.LoadAsync(cancellationToken);

            await _transfersGate.WaitAsync(cancellationToken);
            try
            {
                _transfers.Clear();
                _transfers.AddRange(loadedTransfers.OrderByDescending(transfer => transfer.CreatedAtUtc));
                _isInitialized = true;
            }
            finally
            {
                _transfersGate.Release();
            }

            await _loggerService.LogInfoAsync(
                $"[TRANSFER] File transfer service initialized with {loadedTransfers.Count} persisted transfer item(s).",
                cancellationToken);
        }
        finally
        {
            _lifecycleGate.Release();
        }
    }

    public async Task ShutdownAsync(CancellationToken cancellationToken = default)
    {
        await _lifecycleGate.WaitAsync(cancellationToken);
        try
        {
            if (!_isInitialized)
            {
                return;
            }

            List<OutgoingTransferRuntime> outgoingRuntimes;
            lock (_outgoingGate)
            {
                outgoingRuntimes = _outgoingTransfers.Values.ToList();
                _outgoingTransfers.Clear();
            }

            foreach (var runtime in outgoingRuntimes)
            {
                runtime.CancelRequested = true;
                runtime.CurrentRequestCts?.Cancel();
            }

            foreach (var runtime in outgoingRuntimes)
            {
                if (runtime.WorkerTask is not null)
                {
                    await SafeAwaitAsync(runtime.WorkerTask);
                }

                runtime.CurrentRequestCts?.Dispose();
                runtime.Gate.Dispose();
            }

            List<IncomingTransferRuntime> incomingRuntimes;
            lock (_incomingGate)
            {
                incomingRuntimes = _incomingTransfers.Values.ToList();
                _incomingTransfers.Clear();
            }

            foreach (var runtime in incomingRuntimes)
            {
                await runtime.Gate.WaitAsync(cancellationToken);
                try
                {
                    runtime.Stream.Dispose();
                    await UpdateTransferAsync(runtime.Item, item =>
                    {
                        if (item.Status is TransferState.Receiving or TransferState.Preparing)
                        {
                            item.Status = TransferState.Failed;
                            item.LastError = "Transfer stopped before completion.";
                        }
                    }, cancellationToken);
                }
                finally
                {
                    runtime.Gate.Release();
                    runtime.Gate.Dispose();
                }
            }

            await PersistTransfersAsync(cancellationToken);
            _isInitialized = false;

            await _loggerService.LogInfoAsync("File transfer service stopped and transfer history persisted.", cancellationToken);
        }
        finally
        {
            _lifecycleGate.Release();
        }
    }

    public async Task<IReadOnlyList<TransferItem>> GetTransfersAsync(CancellationToken cancellationToken = default)
    {
        await InitializeAsync(cancellationToken);

        await _transfersGate.WaitAsync(cancellationToken);
        try
        {
            return _transfers
                .OrderByDescending(transfer => transfer.CreatedAtUtc)
                .ToList();
        }
        finally
        {
            _transfersGate.Release();
        }
    }

    public async Task QueueFilesAsync(IEnumerable<string> filePaths, CancellationToken cancellationToken = default)
    {
        await InitializeAsync(cancellationToken);

        var session = await _connectionService.GetActiveSessionAsync(cancellationToken);
        if (session is null || !session.IsConnected)
        {
            await _loggerService.LogWarningAsync("File selection was ignored because no local peer is connected.", cancellationToken);
            return;
        }

        var maxFileSize = ResolveMaxTransferSizeBytes(session.TransportMode);
        var chunkSize = ResolveChunkSize(session.TransportMode);
        var transportLabel = DescribeTransport(session.TransportMode);
        var transportLimitLabel = DescribeTransportLimit(session.TransportMode);

        foreach (var filePath in filePaths
                     .Where(path => !string.IsNullOrWhiteSpace(path))
                     .Distinct(StringComparer.OrdinalIgnoreCase))
        {
            if (!File.Exists(filePath))
            {
                await _loggerService.LogWarningAsync($"Skipped transfer because the file does not exist: {filePath}", cancellationToken);
                continue;
            }

            var fileInfo = new FileInfo(filePath);
            if (fileInfo.Length <= 0)
            {
                await _loggerService.LogWarningAsync($"Skipped empty file transfer for {fileInfo.Name}.", cancellationToken);
                continue;
            }

            if (fileInfo.Length > maxFileSize)
            {
                var (rejectedMimeType, rejectedKind) = ResolveMimeType(fileInfo.Extension);
                var rejectedTransfer = new TransferItem
                {
                    Id = Guid.NewGuid().ToString("N"),
                    FileName = fileInfo.Name,
                    PeerId = session.Peer.Id,
                    PeerName = session.Peer.DisplayName,
                    Direction = TransferDirection.Outgoing,
                    Kind = rejectedKind,
                    MimeType = rejectedMimeType,
                    TotalBytes = fileInfo.Length,
                    TransferredBytes = 0,
                    Status = TransferState.Failed,
                    CreatedAtUtc = DateTimeOffset.UtcNow,
                    FileCreatedAtUtc = fileInfo.CreationTimeUtc,
                    SourcePath = fileInfo.FullName,
                    SavedFilePath = string.Empty,
                    LastError = $"File exceeds the {transportLimitLabel} {transportLabel} limit.",
                    ChunkSize = chunkSize,
                    TotalChunks = (int)Math.Ceiling(fileInfo.Length / (double)chunkSize),
                    ProcessedChunks = 0
                };

                await AddTransferAsync(rejectedTransfer, publish: true, cancellationToken);
                await _loggerService.LogWarningAsync(
                    $"Skipped {fileInfo.Name} because it exceeds the {transportLimitLabel} {transportLabel} limit.",
                    cancellationToken);
                continue;
            }

            var (mimeType, kind) = ResolveMimeType(fileInfo.Extension);
            var totalChunks = (int)Math.Ceiling(fileInfo.Length / (double)chunkSize);

            var transfer = new TransferItem
            {
                Id = Guid.NewGuid().ToString("N"),
                FileName = fileInfo.Name,
                PeerId = session.Peer.Id,
                PeerName = session.Peer.DisplayName,
                Direction = TransferDirection.Outgoing,
                Kind = kind,
                MimeType = mimeType,
                TotalBytes = fileInfo.Length,
                TransferredBytes = 0,
                Status = TransferState.Queued,
                CreatedAtUtc = DateTimeOffset.UtcNow,
                FileCreatedAtUtc = fileInfo.CreationTimeUtc,
                SourcePath = fileInfo.FullName,
                SavedFilePath = string.Empty,
                LastError = string.Empty,
                ChunkSize = chunkSize,
                TotalChunks = totalChunks,
                ProcessedChunks = 0
            };

            await AddTransferAsync(transfer, publish: true, cancellationToken);

            var runtime = new OutgoingTransferRuntime(transfer);
            lock (_outgoingGate)
            {
                _outgoingTransfers[transfer.Id] = runtime;
            }

            await _loggerService.LogInfoAsync(
                $"Queued outgoing transfer {transfer.FileName} ({transfer.TotalBytes} bytes) to {transfer.PeerName}.",
                cancellationToken);

            StartNextOutgoingWorker();
        }
    }

    public async Task PauseTransferAsync(TransferItem transferItem, CancellationToken cancellationToken = default)
    {
        if (transferItem.Direction != TransferDirection.Outgoing)
        {
            return;
        }

        if (!TryGetOutgoingRuntime(transferItem.Id, out var runtime))
        {
            return;
        }

        runtime.PauseRequested = true;
        await _loggerService.LogInfoAsync($"[TRANSFER] Pause requested for {transferItem.FileName}.", cancellationToken);
    }

    public async Task ResumeTransferAsync(TransferItem transferItem, CancellationToken cancellationToken = default)
    {
        if (transferItem.Direction != TransferDirection.Outgoing)
        {
            return;
        }

        if (!TryGetOutgoingRuntime(transferItem.Id, out var runtime))
        {
            runtime = new OutgoingTransferRuntime(transferItem);
            lock (_outgoingGate)
            {
                _outgoingTransfers[transferItem.Id] = runtime;
            }
        }

        runtime.PauseRequested = false;
        runtime.CancelRequested = false;
        runtime.CurrentRequestCts?.Dispose();
        runtime.CurrentRequestCts = null;

        await UpdateTransferAsync(transferItem, item =>
        {
            if (item.Status == TransferState.Paused)
            {
                item.Status = TransferState.Queued;
                item.LastError = string.Empty;
            }
        }, cancellationToken);

        await _loggerService.LogInfoAsync($"[TRANSFER] Resume requested for {transferItem.FileName}.", cancellationToken);
        StartNextOutgoingWorker();
    }

    public async Task CancelTransferAsync(TransferItem transferItem, CancellationToken cancellationToken = default)
    {
        await InitializeAsync(cancellationToken);

        if (transferItem.Direction == TransferDirection.Outgoing)
        {
            if (!TryGetOutgoingRuntime(transferItem.Id, out var runtime))
            {
                runtime = new OutgoingTransferRuntime(transferItem)
                {
                    CancelRequested = true
                };

                lock (_outgoingGate)
                {
                    _outgoingTransfers[transferItem.Id] = runtime;
                }
            }
            else
            {
                runtime.CancelRequested = true;
            }

            runtime.PauseRequested = false;
            runtime.CurrentRequestCts?.Cancel();
            StartOutgoingWorker(runtime);
        }
        else
        {
            IncomingTransferRuntime? runtime;
            lock (_incomingGate)
            {
                _incomingTransfers.TryGetValue(transferItem.Id, out runtime);
            }

            if (runtime is null)
            {
                await UpdateTransferAsync(transferItem, item =>
                {
                    item.Status = TransferState.Canceled;
                    item.LastError = "Canceled locally.";
                    item.CompletedAtUtc = DateTimeOffset.UtcNow;
                    item.SpeedBytesPerSecond = 0;
                    item.EstimatedSecondsRemaining = 0;
                }, cancellationToken);
            }
            else
            {
                await CancelIncomingRuntimeAsync(runtime, "Canceled locally.", notifyRemote: true, cancellationToken);
            }
        }

        await _loggerService.LogInfoAsync($"[TRANSFER] Cancel requested for {transferItem.FileName}.", cancellationToken);
    }

    public async Task OpenTransferAsync(TransferItem transferItem, CancellationToken cancellationToken = default)
    {
        var path = transferItem.OpenPath;
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path))
        {
            await _loggerService.LogWarningAsync($"Open skipped because the file path is not available: {transferItem.FileName}.", cancellationToken);
            return;
        }

        try
        {
            Process.Start(new ProcessStartInfo(path)
            {
                UseShellExecute = true
            });

            await _loggerService.LogInfoAsync($"Opened file {path}.", cancellationToken);
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync($"Could not open {path}: {ex.Message}", cancellationToken);
        }
    }

    public async Task<int> ClearHistoryAsync(CancellationToken cancellationToken = default)
    {
        await InitializeAsync(cancellationToken);

        int removedCount;

        await _transfersGate.WaitAsync(cancellationToken);
        try
        {
            removedCount = _transfers.RemoveAll(transfer => transfer.Status is TransferState.Completed or TransferState.Failed or TransferState.Canceled);
        }
        finally
        {
            _transfersGate.Release();
        }

        await PersistTransfersAsync(cancellationToken);
        await _loggerService.LogInfoAsync(
            $"[HISTORY] Cleared {removedCount} completed/failed/canceled transfer record(s) from local desktop history.",
            cancellationToken);
        return removedCount;
    }

    private async Task AddTransferAsync(TransferItem transfer, bool publish, CancellationToken cancellationToken)
    {
        await _transfersGate.WaitAsync(cancellationToken);
        try
        {
            if (_transfers.Any(existing => string.Equals(existing.Id, transfer.Id, StringComparison.OrdinalIgnoreCase)))
            {
                return;
            }

            _transfers.Insert(0, transfer);
        }
        finally
        {
            _transfersGate.Release();
        }

        await PersistTransfersAsync(cancellationToken);

        if (publish)
        {
            var handler = TransferAdded;
            if (handler is not null)
            {
                await DispatchAsync(() => handler.Invoke(transfer));
            }
        }
    }

    private async Task PersistTransfersAsync(CancellationToken cancellationToken = default)
    {
        List<TransferItem> snapshot;

        await _transfersGate.WaitAsync(cancellationToken);
        try
        {
            snapshot = _transfers
                .OrderByDescending(transfer => transfer.CreatedAtUtc)
                .ToList();
        }
        finally
        {
            _transfersGate.Release();
        }

        await _transferRepository.SaveAsync(snapshot, cancellationToken);
    }

    private async Task PersistTransfersIfDueAsync(CancellationToken cancellationToken = default)
    {
        var nowTicks = DateTime.UtcNow.Ticks;
        var lastTicks = Interlocked.Read(ref _lastProgressPersistenceUtcTicks);
        if (nowTicks - lastTicks < TimeSpan.FromMilliseconds(750).Ticks)
        {
            return;
        }

        if (Interlocked.CompareExchange(ref _lastProgressPersistenceUtcTicks, nowTicks, lastTicks) != lastTicks)
        {
            return;
        }

        await PersistTransfersAsync(cancellationToken);
    }

    private async Task UpdateTransferAsync(
        TransferItem transfer,
        Action<TransferItem> update,
        CancellationToken cancellationToken = default)
    {
        await DispatchAsync(() => update(transfer));
        await PersistTransfersAsync(cancellationToken);
    }

    private async Task UpdateTransferProgressAsync(
        TransferItem transfer,
        Action<TransferItem> update,
        CancellationToken cancellationToken = default)
    {
        await DispatchAsync(() => update(transfer));
        await PersistTransfersIfDueAsync(cancellationToken);
    }

    private bool ShouldFlushIncomingRuntime(IncomingTransferRuntime runtime)
    {
        if (runtime.PendingBytesSinceFlush <= 0)
        {
            return false;
        }

        if (runtime.Item.TransferredBytes + runtime.PendingBytesSinceFlush >= runtime.Item.TotalBytes)
        {
            return true;
        }

        return runtime.PendingBytesSinceFlush >= Math.Max(_receiveFlushThresholdBytes, runtime.Item.ChunkSize * 2L);
    }

    private async Task<string> BuildIncomingFilePathAsync(string fileName, CancellationToken cancellationToken)
    {
        var downloadFolder = await _settingsRepository.LoadDownloadFolderAsync(cancellationToken);
        var safeFolder = string.IsNullOrWhiteSpace(downloadFolder)
            ? Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
                AppConstants.DefaultDownloadFolderName)
            : downloadFolder;

        Directory.CreateDirectory(safeFolder);
        return EnsureUniqueFilePath(Path.Combine(safeFolder, SanitizeFileName(fileName)));
    }

    private static string EnsureUniqueFilePath(string path)
    {
        var directory = Path.GetDirectoryName(path)!;
        var fileName = Path.GetFileNameWithoutExtension(path);
        var extension = Path.GetExtension(path);
        var candidate = path;
        var index = 1;

        while (File.Exists(candidate))
        {
            candidate = Path.Combine(directory, $"{fileName} ({index}){extension}");
            index++;
        }

        return candidate;
    }

    private static string SanitizeFileName(string fileName)
    {
        var sanitized = fileName;

        foreach (var invalidChar in Path.GetInvalidFileNameChars())
        {
            sanitized = sanitized.Replace(invalidChar, '_');
        }

        return string.IsNullOrWhiteSpace(sanitized) ? "received-file" : sanitized;
    }

    private static Uri BuildPeerUri(DevicePeer peer, string path)
    {
        return new UriBuilder(Uri.UriSchemeHttp, peer.IpAddress, peer.Port, path).Uri;
    }

    private static int ResolveChunkSize(AppConnectionMode mode)
    {
        return mode == AppConnectionMode.BluetoothFallback
            ? AppConstants.BluetoothTransferChunkSizeBytes
            : AppConstants.TransferChunkSizeBytes;
    }

    private static long ResolveMaxTransferSizeBytes(AppConnectionMode mode)
    {
        return mode == AppConnectionMode.BluetoothFallback
            ? AppConstants.BluetoothSmallFileTransferLimitBytes
            : AppConstants.TransferMaxFileSizeBytes;
    }

    private static string DescribeTransport(AppConnectionMode mode)
    {
        return mode == AppConnectionMode.BluetoothFallback ? "Bluetooth fallback" : "hotspot/LAN";
    }

    private static string DescribeTransportLimit(AppConnectionMode mode)
    {
        return mode == AppConnectionMode.BluetoothFallback ? "300 MB" : "20 GB";
    }

    private static void ApplyMetrics(TransferItem item, Stopwatch stopwatch, long metricStartBytes)
    {
        var elapsedSeconds = Math.Max(stopwatch.Elapsed.TotalSeconds, 0.01d);
        var bytesDelta = Math.Max(item.TransferredBytes - metricStartBytes, 0);
        var speed = bytesDelta / elapsedSeconds;

        item.SpeedBytesPerSecond = speed;
        item.EstimatedSecondsRemaining = speed <= 0.1d
            ? 0
            : Math.Max(item.TotalBytes - item.TransferredBytes, 0) / speed;
    }

    private static (string MimeType, string Kind) ResolveMimeType(string extension)
    {
        var normalized = extension.Trim().ToLowerInvariant();

        return normalized switch
        {
            ".png" => ("image/png", "image"),
            ".jpg" or ".jpeg" => ("image/jpeg", "image"),
            ".gif" => ("image/gif", "image"),
            ".bmp" => ("image/bmp", "image"),
            ".webp" => ("image/webp", "image"),
            ".pdf" => ("application/pdf", "document"),
            ".txt" => ("text/plain", "text"),
            ".json" => ("application/json", "file"),
            ".csv" => ("text/csv", "file"),
            ".mp4" => ("video/mp4", "video"),
            ".mov" => ("video/quicktime", "video"),
            ".avi" => ("video/x-msvideo", "video"),
            ".mkv" => ("video/x-matroska", "video"),
            ".webm" => ("video/webm", "video"),
            _ => ("application/octet-stream", "file")
        };
    }

    private static async Task<int> ReadExactlyAsync(Stream stream, byte[] buffer, int length, CancellationToken cancellationToken)
    {
        var totalRead = 0;

        while (totalRead < length)
        {
            var bytesRead = await stream.ReadAsync(
                buffer.AsMemory(totalRead, length - totalRead),
                cancellationToken);

            if (bytesRead == 0)
            {
                break;
            }

            totalRead += bytesRead;
        }

        return totalRead;
    }

    private static async Task<byte[]?> ReadChunkAsync(Stream stream, int expectedLength, CancellationToken cancellationToken)
    {
        var buffer = new byte[expectedLength];
        var read = await ReadExactlyAsync(stream, buffer, expectedLength, cancellationToken);
        return read == expectedLength ? buffer : null;
    }

    private void CleanupStaleTempFiles()
    {
        if (!Directory.Exists(_incomingTempRoot))
        {
            return;
        }

        foreach (var path in Directory.EnumerateFiles(_incomingTempRoot, "*.part"))
        {
            try
            {
                var info = new FileInfo(path);
                if (info.LastWriteTimeUtc < DateTime.UtcNow.AddDays(-1))
                {
                    info.Delete();
                }
            }
            catch
            {
                // Ignore stale temp cleanup failures and keep the runtime alive.
            }
        }
    }

    private static Task DispatchAsync(Action action)
    {
        var dispatcher = Application.Current?.Dispatcher;

        if (dispatcher is null || dispatcher.CheckAccess())
        {
            action();
            return Task.CompletedTask;
        }

        return dispatcher.InvokeAsync(action).Task;
    }

    private static Task SafeAwaitAsync(Task task)
    {
        return task.ContinueWith(
            _ => { },
            CancellationToken.None,
            TaskContinuationOptions.ExecuteSynchronously,
            TaskScheduler.Default);
    }
}
