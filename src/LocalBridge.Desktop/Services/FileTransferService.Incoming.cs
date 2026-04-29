using System.Diagnostics;
using System.IO;
using LocalBridge.Core.Protocol;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Services;

public sealed partial class FileTransferService
{
    public async Task<FileTransferPrepareResponseDto> PrepareIncomingTransferAsync(
        FileTransferPrepareRequestDto request,
        ConnectionSessionSnapshot session,
        CancellationToken cancellationToken = default)
    {
        await InitializeAsync(cancellationToken);

        var maxFileSize = ResolveMaxTransferSizeBytes(session.TransportMode);
        if (request.FileSize <= 0 || request.FileSize > maxFileSize)
        {
            return CreatePrepareFailure(
                request.TransferId,
                "file_size_not_supported",
                session,
                0,
                0);
        }

        if (request.ChunkSize <= 0 || request.TotalChunks <= 0)
        {
            return CreatePrepareFailure(
                request.TransferId,
                "invalid_chunk_layout",
                session,
                0,
                0);
        }

        IncomingTransferRuntime? runtime;
        lock (_incomingGate)
        {
            _incomingTransfers.TryGetValue(request.TransferId, out runtime);
        }

        if (runtime is not null)
        {
            await runtime.Gate.WaitAsync(cancellationToken);
            try
            {
                if (!string.Equals(runtime.SenderId, request.SenderId, StringComparison.OrdinalIgnoreCase))
                {
                    return CreatePrepareFailure(
                        request.TransferId,
                        "transfer_sender_mismatch",
                        session,
                        runtime.NextExpectedChunkIndex,
                        runtime.Item.TransferredBytes);
                }

                runtime.SessionId = session.SessionId;
                runtime.PeerName = session.Peer.DisplayName;

                await UpdateTransferAsync(runtime.Item, item =>
                {
                    item.PeerName = session.Peer.DisplayName;
                    item.PeerId = session.Peer.Id;
                    item.Status = item.TransferredBytes >= item.TotalBytes
                        ? TransferState.Completed
                        : TransferState.Receiving;
                }, cancellationToken);

                return new FileTransferPrepareResponseDto(
                    Accepted: true,
                    TransferId: request.TransferId,
                    Status: MapState(runtime.Item.Status),
                    FailureReason: null,
                    NextExpectedChunkIndex: runtime.NextExpectedChunkIndex,
                    ReceivedBytes: runtime.Item.TransferredBytes,
                    ReceiverDeviceId: session.LocalDeviceId,
                    ReceiverDeviceName: session.LocalDeviceName,
                    SuggestedFilePath: runtime.FinalFilePath,
                    RespondedAtUtc: DateTimeOffset.UtcNow);
            }
            finally
            {
                runtime.Gate.Release();
            }
        }

        var reservedFilePath = await BuildIncomingFilePathAsync(request.FileName, cancellationToken);
        Directory.CreateDirectory(Path.GetDirectoryName(reservedFilePath)!);

        var tempFilePath = Path.Combine(_incomingTempRoot, $"{request.TransferId}.part");
        var stream = new FileStream(
            tempFilePath,
            FileMode.Create,
            FileAccess.ReadWrite,
            FileShare.Read,
            AppConstants.TransferChunkSizeBytes,
            FileOptions.Asynchronous);

        var transfer = new TransferItem
        {
            Id = request.TransferId,
            FileName = request.FileName,
            PeerId = session.Peer.Id,
            PeerName = session.Peer.DisplayName,
            Direction = TransferDirection.Incoming,
            Kind = request.Kind,
            MimeType = request.MimeType,
            TotalBytes = request.FileSize,
            TransferredBytes = 0,
            Status = TransferState.Receiving,
            CreatedAtUtc = DateTimeOffset.UtcNow,
            FileCreatedAtUtc = request.FileCreatedAtUtc,
            SourcePath = string.Empty,
            SavedFilePath = string.Empty,
            LastError = string.Empty,
            ChunkSize = request.ChunkSize,
            TotalChunks = request.TotalChunks,
            ProcessedChunks = 0
        };

        runtime = new IncomingTransferRuntime(
            request.TransferId,
            session.SessionId,
            request.SenderId,
            session.Peer.DisplayName,
            tempFilePath,
            reservedFilePath,
            stream,
            transfer);

        lock (_incomingGate)
        {
            _incomingTransfers[request.TransferId] = runtime;
        }

        await AddTransferAsync(transfer, publish: true, cancellationToken);
        await _loggerService.LogInfoAsync(
            $"[TRANSFER] Accepted incoming transfer {request.FileName} from {session.Peer.DisplayName}. Target path: {reservedFilePath}.",
            cancellationToken);

        return new FileTransferPrepareResponseDto(
            Accepted: true,
            TransferId: request.TransferId,
            Status: ProtocolConstants.TransferStateReceiving,
            FailureReason: null,
            NextExpectedChunkIndex: 0,
            ReceivedBytes: 0,
            ReceiverDeviceId: session.LocalDeviceId,
            ReceiverDeviceName: session.LocalDeviceName,
            SuggestedFilePath: reservedFilePath,
            RespondedAtUtc: DateTimeOffset.UtcNow);
    }

    public async Task<FileTransferChunkResponseDto> ReceiveChunkAsync(
        FileTransferChunkDescriptorDto descriptor,
        Stream contentStream,
        ConnectionSessionSnapshot session,
        CancellationToken cancellationToken = default)
    {
        await InitializeAsync(cancellationToken);

        IncomingTransferRuntime? runtime;
        lock (_incomingGate)
        {
            _incomingTransfers.TryGetValue(descriptor.TransferId, out runtime);
        }

        if (runtime is null)
        {
            return CreateChunkFailure(descriptor.TransferId, descriptor.ChunkIndex, "transfer_not_found");
        }

        await runtime.Gate.WaitAsync(cancellationToken);
        try
        {
            if (!string.Equals(runtime.SenderId, descriptor.SenderId, StringComparison.OrdinalIgnoreCase))
            {
                return CreateChunkFailure(descriptor.TransferId, descriptor.ChunkIndex, "transfer_sender_mismatch");
            }

            if (descriptor.ChunkIndex != runtime.NextExpectedChunkIndex ||
                descriptor.ChunkOffset != runtime.Item.TransferredBytes)
            {
                return new FileTransferChunkResponseDto(
                    Accepted: false,
                    TransferId: descriptor.TransferId,
                    ChunkIndex: descriptor.ChunkIndex,
                    Status: MapState(runtime.Item.Status),
                    FailureReason: "unexpected_chunk_position",
                    NextExpectedChunkIndex: runtime.NextExpectedChunkIndex,
                    ReceivedBytes: runtime.Item.TransferredBytes,
                    RespondedAtUtc: DateTimeOffset.UtcNow);
            }

            var buffer = await ReadChunkAsync(contentStream, descriptor.ChunkLength, cancellationToken);
            if (buffer is null)
            {
                return CreateChunkFailure(descriptor.TransferId, descriptor.ChunkIndex, "incomplete_chunk_payload");
            }

            runtime.SessionId = session.SessionId;
            runtime.PeerName = session.Peer.DisplayName;
            runtime.Stopwatch.Start();
            runtime.Stream.Position = descriptor.ChunkOffset;
            await runtime.Stream.WriteAsync(buffer, cancellationToken);
            runtime.PendingBytesSinceFlush += buffer.Length;
            if (ShouldFlushIncomingRuntime(runtime))
            {
                await runtime.Stream.FlushAsync(cancellationToken);
                runtime.PendingBytesSinceFlush = 0;
            }

            runtime.NextExpectedChunkIndex++;
            runtime.LastActivityUtc = DateTimeOffset.UtcNow;

            await UpdateTransferProgressAsync(runtime.Item, item =>
            {
                item.Status = TransferState.Receiving;
                item.PeerName = session.Peer.DisplayName;
                item.PeerId = session.Peer.Id;
                item.TransferredBytes = Math.Min(item.TotalBytes, item.TransferredBytes + buffer.Length);
                item.ProcessedChunks = runtime.NextExpectedChunkIndex;
                ApplyMetrics(item, runtime.Stopwatch, runtime.MetricStartBytes);
            }, cancellationToken);

            return new FileTransferChunkResponseDto(
                Accepted: true,
                TransferId: descriptor.TransferId,
                ChunkIndex: descriptor.ChunkIndex,
                Status: ProtocolConstants.TransferStateReceiving,
                FailureReason: null,
                NextExpectedChunkIndex: runtime.NextExpectedChunkIndex,
                ReceivedBytes: runtime.Item.TransferredBytes,
                RespondedAtUtc: DateTimeOffset.UtcNow);
        }
        catch (Exception ex)
        {
            await UpdateTransferAsync(runtime.Item, item =>
            {
                item.Status = TransferState.Failed;
                item.LastError = ex.Message;
            }, cancellationToken);

            await _loggerService.LogWarningAsync(
                $"Incoming chunk failed for {runtime.Item.FileName}: {ex.Message}",
                cancellationToken);

            return CreateChunkFailure(descriptor.TransferId, descriptor.ChunkIndex, "chunk_write_failed");
        }
        finally
        {
            runtime.Gate.Release();
        }
    }

    public async Task<FileTransferCompleteResponseDto> CompleteIncomingTransferAsync(
        FileTransferCompleteRequestDto request,
        ConnectionSessionSnapshot session,
        CancellationToken cancellationToken = default)
    {
        await InitializeAsync(cancellationToken);

        IncomingTransferRuntime? runtime;
        lock (_incomingGate)
        {
            _incomingTransfers.TryGetValue(request.TransferId, out runtime);
        }

        if (runtime is null)
        {
            return CreateCompleteFailure(request.TransferId, "transfer_not_found");
        }

        await runtime.Gate.WaitAsync(cancellationToken);
        var disposedGate = false;
        try
        {
            if (!string.Equals(runtime.SenderId, request.SenderId, StringComparison.OrdinalIgnoreCase))
            {
                return CreateCompleteFailure(request.TransferId, "transfer_sender_mismatch");
            }

            if (request.TotalBytes != runtime.Item.TotalBytes ||
                request.TotalChunks != runtime.Item.TotalChunks ||
                runtime.Item.TransferredBytes != runtime.Item.TotalBytes)
            {
                return CreateCompleteFailure(request.TransferId, "transfer_totals_mismatch");
            }

            runtime.Stream.Dispose();

            var finalPath = EnsureUniqueFilePath(runtime.FinalFilePath);
            Directory.CreateDirectory(Path.GetDirectoryName(finalPath)!);
            File.Move(runtime.TempFilePath, finalPath, overwrite: false);

            await UpdateTransferAsync(runtime.Item, item =>
            {
                item.Status = TransferState.Completed;
                item.CompletedAtUtc = DateTimeOffset.UtcNow;
                item.SavedFilePath = finalPath;
                item.SpeedBytesPerSecond = 0;
                item.EstimatedSecondsRemaining = 0;
                item.LastError = string.Empty;
            }, cancellationToken);

            lock (_incomingGate)
            {
                _incomingTransfers.Remove(request.TransferId);
            }

            runtime.Gate.Release();
            runtime.Gate.Dispose();
            disposedGate = true;

            await _loggerService.LogInfoAsync(
                $"[TRANSFER] Completed incoming transfer {runtime.Item.FileName} from {session.Peer.DisplayName}. Saved to {finalPath}.",
                cancellationToken);

            return new FileTransferCompleteResponseDto(
                Accepted: true,
                TransferId: request.TransferId,
                Status: ProtocolConstants.TransferStateCompleted,
                FailureReason: null,
                SavedFilePath: finalPath,
                CompletedAtUtc: DateTimeOffset.UtcNow);
        }
        catch (Exception ex)
        {
            await UpdateTransferAsync(runtime.Item, item =>
            {
                item.Status = TransferState.Failed;
                item.LastError = ex.Message;
            }, cancellationToken);

            await _loggerService.LogWarningAsync(
                $"Incoming transfer completion failed for {runtime.Item.FileName}: {ex.Message}",
                cancellationToken);

            return CreateCompleteFailure(request.TransferId, "transfer_finalize_failed");
        }
        finally
        {
            if (!disposedGate)
            {
                runtime.Gate.Release();
            }
        }
    }

    public async Task<FileTransferCancelResponseDto> CancelIncomingTransferAsync(
        FileTransferCancelRequestDto request,
        ConnectionSessionSnapshot session,
        CancellationToken cancellationToken = default)
    {
        await InitializeAsync(cancellationToken);

        IncomingTransferRuntime? runtime;
        lock (_incomingGate)
        {
            _incomingTransfers.TryGetValue(request.TransferId, out runtime);
        }

        if (runtime is null)
        {
            return CreateCancelFailure(request.TransferId, "transfer_not_found");
        }

        if (!string.Equals(runtime.SenderId, request.SenderId, StringComparison.OrdinalIgnoreCase))
        {
            return CreateCancelFailure(request.TransferId, "transfer_sender_mismatch");
        }

        await CancelIncomingRuntimeAsync(runtime, request.Reason, notifyRemote: false, cancellationToken);

        await _loggerService.LogInfoAsync(
            $"Incoming transfer {runtime.Item.FileName} was canceled by {session.Peer.DisplayName}.",
            cancellationToken);

        return new FileTransferCancelResponseDto(
            Accepted: true,
            TransferId: request.TransferId,
            Status: ProtocolConstants.TransferStateCanceled,
            FailureReason: null,
            CanceledAtUtc: DateTimeOffset.UtcNow);
    }

    private async Task CancelIncomingRuntimeAsync(
        IncomingTransferRuntime runtime,
        string reason,
        bool notifyRemote,
        CancellationToken cancellationToken)
    {
        await runtime.Gate.WaitAsync(cancellationToken);
        try
        {
            runtime.Stream.Dispose();

            if (File.Exists(runtime.TempFilePath))
            {
                File.Delete(runtime.TempFilePath);
            }

            await UpdateTransferAsync(runtime.Item, item =>
            {
                item.Status = TransferState.Canceled;
                item.LastError = reason;
                item.CompletedAtUtc = DateTimeOffset.UtcNow;
                item.SpeedBytesPerSecond = 0;
                item.EstimatedSecondsRemaining = 0;
            }, cancellationToken);
        }
        finally
        {
            runtime.Gate.Release();
        }

        lock (_incomingGate)
        {
            _incomingTransfers.Remove(runtime.TransferId);
        }

        runtime.Gate.Dispose();

        if (notifyRemote)
        {
            var receipt = await SendCancelAsync(runtime.TransferId, runtime.Item.PeerId, "receiver_canceled", cancellationToken);
            if (!receipt.Accepted)
            {
                await _loggerService.LogWarningAsync(
                    $"Could not notify remote peer that transfer {runtime.Item.FileName} was canceled: {receipt.FailureReason}.",
                    cancellationToken);
            }
        }
    }

    private static string MapState(TransferState state)
    {
        return state switch
        {
            TransferState.Queued => ProtocolConstants.TransferStateQueued,
            TransferState.Preparing => ProtocolConstants.TransferStatePreparing,
            TransferState.Sending => ProtocolConstants.TransferStateSending,
            TransferState.Receiving => ProtocolConstants.TransferStateReceiving,
            TransferState.Paused => ProtocolConstants.TransferStatePaused,
            TransferState.Completed => ProtocolConstants.TransferStateCompleted,
            TransferState.Failed => ProtocolConstants.TransferStateFailed,
            TransferState.Canceled => ProtocolConstants.TransferStateCanceled,
            _ => ProtocolConstants.TransferStateFailed
        };
    }

    private static FileTransferPrepareResponseDto CreatePrepareFailure(
        string transferId,
        string reason,
        ConnectionSessionSnapshot session,
        int nextExpectedChunkIndex,
        long receivedBytes)
    {
        return new FileTransferPrepareResponseDto(
            Accepted: false,
            TransferId: transferId,
            Status: ProtocolConstants.TransferStateFailed,
            FailureReason: reason,
            NextExpectedChunkIndex: nextExpectedChunkIndex,
            ReceivedBytes: receivedBytes,
            ReceiverDeviceId: session.LocalDeviceId,
            ReceiverDeviceName: session.LocalDeviceName,
            SuggestedFilePath: null,
            RespondedAtUtc: DateTimeOffset.UtcNow);
    }

    private static FileTransferChunkResponseDto CreateChunkFailure(string transferId, int chunkIndex, string reason)
    {
        return new FileTransferChunkResponseDto(
            Accepted: false,
            TransferId: transferId,
            ChunkIndex: chunkIndex,
            Status: ProtocolConstants.TransferStateFailed,
            FailureReason: reason,
            NextExpectedChunkIndex: 0,
            ReceivedBytes: 0,
            RespondedAtUtc: DateTimeOffset.UtcNow);
    }

    private static FileTransferCompleteResponseDto CreateCompleteFailure(string transferId, string reason)
    {
        return new FileTransferCompleteResponseDto(
            Accepted: false,
            TransferId: transferId,
            Status: ProtocolConstants.TransferStateFailed,
            FailureReason: reason,
            SavedFilePath: null,
            CompletedAtUtc: DateTimeOffset.UtcNow);
    }

    private static FileTransferCancelResponseDto CreateCancelFailure(string transferId, string reason)
    {
        return new FileTransferCancelResponseDto(
            Accepted: false,
            TransferId: transferId,
            Status: ProtocolConstants.TransferStateFailed,
            FailureReason: reason,
            CanceledAtUtc: DateTimeOffset.UtcNow);
    }

    private sealed class IncomingTransferRuntime
    {
        public IncomingTransferRuntime(
            string transferId,
            string sessionId,
            string senderId,
            string peerName,
            string tempFilePath,
            string finalFilePath,
            FileStream stream,
            TransferItem item)
        {
            TransferId = transferId;
            SessionId = sessionId;
            SenderId = senderId;
            PeerName = peerName;
            TempFilePath = tempFilePath;
            FinalFilePath = finalFilePath;
            Stream = stream;
            Item = item;
            LastActivityUtc = DateTimeOffset.UtcNow;
            MetricStartBytes = item.TransferredBytes;
        }

        public SemaphoreSlim Gate { get; } = new(1, 1);

        public string TransferId { get; }

        public string SessionId { get; set; }

        public string SenderId { get; }

        public string PeerName { get; set; }

        public string TempFilePath { get; }

        public string FinalFilePath { get; }

        public FileStream Stream { get; }

        public TransferItem Item { get; }

        public int NextExpectedChunkIndex { get; set; }

        public DateTimeOffset LastActivityUtc { get; set; }

        public Stopwatch Stopwatch { get; } = new();

        public long MetricStartBytes { get; }

        public long PendingBytesSinceFlush { get; set; }
    }
}
