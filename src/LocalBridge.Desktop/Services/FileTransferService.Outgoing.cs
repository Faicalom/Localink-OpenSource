using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using LocalBridge.Core;
using LocalBridge.Core.Protocol;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Infrastructure;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Services;

public sealed partial class FileTransferService
{
    private void StartNextOutgoingWorker()
    {
        OutgoingTransferRuntime? runtimeToStart = null;

        lock (_outgoingGate)
        {
            if (_outgoingTransfers.Values.Any(runtime => runtime.WorkerTask is { IsCompleted: false }))
            {
                return;
            }

            runtimeToStart = _outgoingTransfers.Values
                .Where(runtime => !runtime.CancelRequested)
                .Where(runtime => runtime.Item.Status is TransferState.Queued or TransferState.Preparing or TransferState.Sending)
                .OrderBy(runtime => runtime.Item.CreatedAtUtc)
                .FirstOrDefault();
        }

        if (runtimeToStart is not null)
        {
            StartOutgoingWorker(runtimeToStart);
        }
    }

    private void StartOutgoingWorker(OutgoingTransferRuntime runtime)
    {
        lock (runtime.SyncRoot)
        {
            if (runtime.WorkerTask is { IsCompleted: false })
            {
                return;
            }

            runtime.WorkerTask = Task.Run(() => ProcessOutgoingTransferAsync(runtime));
        }
    }

    private async Task ProcessOutgoingTransferAsync(OutgoingTransferRuntime runtime)
    {
        await runtime.Gate.WaitAsync();
        try
        {
            await InitializeAsync();

            if (runtime.CancelRequested)
            {
                await FinalizeOutgoingCancellationAsync(runtime, "Canceled locally.");
                return;
            }

            if (runtime.PauseRequested)
            {
                await UpdateTransferAsync(runtime.Item, item =>
                {
                    item.Status = TransferState.Paused;
                    item.SpeedBytesPerSecond = 0;
                    item.EstimatedSecondsRemaining = 0;
                });
                RemoveOutgoingRuntime(runtime.Item.Id);
                StartNextOutgoingWorker();
                return;
            }

            var session = await GetSessionForTransferPeerAsync(runtime.Item.PeerId);
            if (session is null)
            {
                await MarkOutgoingFailedAsync(runtime, "peer_not_connected");
                return;
            }

            var localDevice = await _connectionService.GetLocalDeviceProfileAsync();
            var fileInfo = new FileInfo(runtime.Item.SourcePath);
            if (!fileInfo.Exists)
            {
                await MarkOutgoingFailedAsync(runtime, "file_missing");
                return;
            }

            if (fileInfo.Length != runtime.Item.TotalBytes)
            {
                await UpdateTransferAsync(runtime.Item, item =>
                {
                    item.TotalBytes = fileInfo.Length;
                    item.TotalChunks = (int)Math.Ceiling(fileInfo.Length / (double)item.ChunkSize);
                });
            }

            await UpdateTransferAsync(runtime.Item, item =>
            {
                item.Status = TransferState.Preparing;
                item.LastError = string.Empty;
                item.PeerName = session.Peer.DisplayName;
                item.PeerId = session.Peer.Id;
                item.StartedAtUtc ??= DateTimeOffset.UtcNow;
            });

            await _loggerService.LogInfoAsync(
                $"[TRANSFER] Preparing {runtime.Item.FileName} for {session.Peer.DisplayName}.");

            var prepareRequest = new FileTransferPrepareRequestDto(
                TransferId: runtime.Item.Id,
                SessionId: session.SessionId,
                SenderId: localDevice.DeviceId,
                SenderName: localDevice.DeviceName,
                ReceiverId: session.Peer.Id,
                FileName: runtime.Item.FileName,
                FileSize: runtime.Item.TotalBytes,
                MimeType: runtime.Item.MimeType,
                Kind: runtime.Item.Kind,
                FileCreatedAtUtc: runtime.Item.FileCreatedAtUtc,
                ChunkSize: runtime.Item.ChunkSize,
                TotalChunks: runtime.Item.TotalChunks,
                RequestedAtUtc: DateTimeOffset.UtcNow);

            var prepareResponse = await SendPrepareAsync(runtime, session, prepareRequest);
            if (!prepareResponse.Accepted)
            {
                var recovered = await TryRecoverOutgoingTransferAsync(
                    runtime,
                    prepareResponse.FailureReason ?? "prepare_failed",
                    CancellationToken.None);
                if (!recovered.Recovered || recovered.Session is null)
                {
                    await MarkOutgoingFailedAsync(runtime, prepareResponse.FailureReason ?? "prepare_failed");
                    return;
                }

                session = recovered.Session;
            }

            var nextChunkIndex = Math.Clamp(
                runtime.Item.ProcessedChunks > 0 ? runtime.Item.ProcessedChunks : prepareResponse.NextExpectedChunkIndex,
                0,
                runtime.Item.TotalChunks);
            var receivedBytes = Math.Clamp(
                runtime.Item.TransferredBytes > 0 ? runtime.Item.TransferredBytes : prepareResponse.ReceivedBytes,
                0,
                runtime.Item.TotalBytes);
            runtime.RestartMetrics(receivedBytes);
            runtime.RecoveryAttempts = 0;

            await UpdateTransferAsync(runtime.Item, item =>
            {
                item.Status = TransferState.Sending;
                item.TransferredBytes = receivedBytes;
                item.ProcessedChunks = nextChunkIndex;
                item.SpeedBytesPerSecond = 0;
                item.EstimatedSecondsRemaining = item.TotalBytes > receivedBytes ? item.TotalBytes - receivedBytes : 0;
            });

            await using var stream = new FileStream(
                runtime.Item.SourcePath,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                Math.Max(runtime.Item.ChunkSize, 4096),
                FileOptions.Asynchronous | FileOptions.SequentialScan);

            stream.Position = receivedBytes;
            for (var chunkIndex = nextChunkIndex; chunkIndex < runtime.Item.TotalChunks; chunkIndex++)
            {
                if (runtime.CancelRequested)
                {
                    await FinalizeOutgoingCancellationAsync(runtime, "Canceled locally.");
                    return;
                }

                if (runtime.PauseRequested)
                {
                    await UpdateTransferAsync(runtime.Item, item =>
                    {
                        item.Status = TransferState.Paused;
                        item.SpeedBytesPerSecond = 0;
                        item.EstimatedSecondsRemaining = 0;
                    });
                    RemoveOutgoingRuntime(runtime.Item.Id);
                    StartNextOutgoingWorker();
                    return;
                }

                session = await GetSessionForTransferPeerAsync(runtime.Item.PeerId);
                if (session is null)
                {
                    await MarkOutgoingFailedAsync(runtime, "peer_not_connected");
                    return;
                }

                var remaining = runtime.Item.TotalBytes - runtime.Item.TransferredBytes;
                var chunkLength = (int)Math.Min(runtime.Item.ChunkSize, remaining);
                var buffer = new byte[chunkLength];
                var read = await ReadExactlyAsync(stream, buffer, chunkLength, CancellationToken.None);
                if (read != chunkLength)
                {
                    await MarkOutgoingFailedAsync(runtime, "unexpected_local_file_length");
                    return;
                }

                var chunkResponse = await SendChunkAsync(
                    runtime,
                    session,
                    localDevice.DeviceId,
                    buffer,
                    chunkIndex,
                    runtime.Item.TransferredBytes,
                    chunkLength);

                if (!chunkResponse.Accepted)
                {
                    var recovered = await TryRecoverOutgoingTransferAsync(
                        runtime,
                        chunkResponse.FailureReason ?? "chunk_failed",
                        CancellationToken.None);
                    if (!recovered.Recovered || recovered.Session is null)
                    {
                        await MarkOutgoingFailedAsync(runtime, chunkResponse.FailureReason ?? "chunk_failed");
                        return;
                    }

                    session = recovered.Session;
                    stream.Position = runtime.Item.TransferredBytes;
                    chunkIndex = Math.Max(runtime.Item.ProcessedChunks - 1, -1);
                    continue;
                }

                runtime.RecoveryAttempts = 0;

                await UpdateTransferProgressAsync(runtime.Item, item =>
                {
                    item.Status = TransferState.Sending;
                    item.TransferredBytes = Math.Clamp(chunkResponse.ReceivedBytes, 0, item.TotalBytes);
                    item.ProcessedChunks = Math.Max(item.ProcessedChunks, chunkResponse.NextExpectedChunkIndex);
                    ApplyMetrics(item, runtime.Stopwatch, runtime.MetricStartBytes);
                });
            }

            var completeResponse = await SendCompleteAsync(runtime, session, localDevice.DeviceId);
            if (!completeResponse.Accepted)
            {
                var recovered = await TryRecoverOutgoingTransferAsync(
                    runtime,
                    completeResponse.FailureReason ?? "complete_failed",
                    CancellationToken.None);
                if (!recovered.Recovered || recovered.Session is null)
                {
                    await MarkOutgoingFailedAsync(runtime, completeResponse.FailureReason ?? "complete_failed");
                    return;
                }

                session = recovered.Session;
                completeResponse = await SendCompleteAsync(runtime, session, localDevice.DeviceId);
                if (!completeResponse.Accepted)
                {
                    await MarkOutgoingFailedAsync(runtime, completeResponse.FailureReason ?? "complete_failed");
                    return;
                }
            }

            await UpdateTransferAsync(runtime.Item, item =>
            {
                item.Status = TransferState.Completed;
                item.CompletedAtUtc = DateTimeOffset.UtcNow;
                item.TransferredBytes = item.TotalBytes;
                item.ProcessedChunks = item.TotalChunks;
                item.SpeedBytesPerSecond = 0;
                item.EstimatedSecondsRemaining = 0;
                item.LastError = string.Empty;
            });

            await _loggerService.LogInfoAsync($"[TRANSFER] Completed outgoing transfer {runtime.Item.FileName}.");
            RemoveOutgoingRuntime(runtime.Item.Id);
            StartNextOutgoingWorker();
        }
        catch (OperationCanceledException) when (runtime.CancelRequested)
        {
            await FinalizeOutgoingCancellationAsync(runtime, "Canceled locally.");
        }
        catch (Exception ex)
        {
            await MarkOutgoingFailedAsync(runtime, ex.Message);
        }
        finally
        {
            runtime.CurrentRequestCts?.Dispose();
            runtime.CurrentRequestCts = null;
            runtime.Gate.Release();
        }
    }

    private async Task<FileTransferPrepareResponseDto> SendPrepareAsync(
        OutgoingTransferRuntime runtime,
        ConnectionSessionSnapshot session,
        FileTransferPrepareRequestDto request)
    {
        var localDevice = await _connectionService.GetLocalDeviceProfileAsync();
        runtime.CurrentRequestCts?.Dispose();
        runtime.CurrentRequestCts = new CancellationTokenSource(TimeSpan.FromSeconds(AppConstants.TransferRequestTimeoutSeconds));

        var envelope = ProtocolEnvelopeFactory.Create(
            packetType: ProtocolPacketTypes.TransferPrepareRequest,
            payload: request,
            senderDeviceId: localDevice.DeviceId,
            receiverDeviceId: session.Peer.Id,
            sessionId: session.SessionId,
            messageId: request.TransferId,
            sentAtUtc: request.RequestedAtUtc);

        if (session.TransportMode == AppConnectionMode.BluetoothFallback)
        {
            return await _bluetoothConnectionService.SendTransferPrepareAsync(request, CancellationToken.None);
        }

        using var response = await _httpClient.PostAsJsonAsync(
            BuildPeerUri(session.Peer, AppConstants.TransferPreparePath),
            envelope,
            JsonDefaults.Options,
            runtime.CurrentRequestCts.Token);

        var responseEnvelope = await response.Content.ReadEnvelopeAsync<FileTransferPrepareResponseDto>(
            runtime.CurrentRequestCts.Token);
        var responseValidation = ProtocolEnvelopeValidator.Validate(
            responseEnvelope,
            expectedPacketTypes: [ProtocolPacketTypes.TransferPrepareResponse]);
        var payload = responseValidation.IsValid ? responseEnvelope!.Payload! : null;

        if (!response.IsSuccessStatusCode || payload is null)
        {
            return CreatePrepareFailure(
                runtime.Item.Id,
                responseEnvelope?.Error?.Code ??
                responseValidation.ErrorCode ??
                $"prepare_http_{(int)response.StatusCode}",
                session,
                0,
                runtime.Item.TransferredBytes);
        }

        return payload!;
    }

    private async Task<FileTransferChunkResponseDto> SendChunkAsync(
        OutgoingTransferRuntime runtime,
        ConnectionSessionSnapshot session,
        string senderId,
        byte[] chunk,
        int chunkIndex,
        long chunkOffset,
        int chunkLength)
    {
        var localDevice = await _connectionService.GetLocalDeviceProfileAsync();
        runtime.CurrentRequestCts?.Dispose();
        runtime.CurrentRequestCts = new CancellationTokenSource(TimeSpan.FromSeconds(AppConstants.TransferRequestTimeoutSeconds));

        if (session.TransportMode == AppConnectionMode.BluetoothFallback)
        {
            var descriptor = new FileTransferChunkDescriptorDto(
                TransferId: runtime.Item.Id,
                SessionId: session.SessionId,
                SenderId: localDevice.DeviceId,
                ChunkIndex: chunkIndex,
                ChunkOffset: chunkOffset,
                ChunkLength: chunkLength);

            return await _bluetoothConnectionService.SendTransferChunkAsync(
                descriptor,
                chunk,
                runtime.CurrentRequestCts.Token);
        }

        using var request = new HttpRequestMessage(HttpMethod.Post, BuildPeerUri(session.Peer, AppConstants.TransferChunkPath))
        {
            Content = CreateChunkContent(
                localDevice.DeviceId,
                session,
                runtime.Item.Id,
                chunk,
                chunkIndex,
                chunkOffset,
                chunkLength)
        };

        using var response = await _httpClient.SendAsync(
            request,
            HttpCompletionOption.ResponseHeadersRead,
            runtime.CurrentRequestCts.Token);

        var responseEnvelope = await response.Content.ReadEnvelopeAsync<FileTransferChunkResponseDto>(
            runtime.CurrentRequestCts.Token);
        var responseValidation = ProtocolEnvelopeValidator.Validate(
            responseEnvelope,
            expectedPacketTypes: [ProtocolPacketTypes.TransferChunkResponse]);
        var payload = responseValidation.IsValid ? responseEnvelope!.Payload! : null;

        if (!response.IsSuccessStatusCode || payload is null)
        {
            return CreateChunkFailure(
                runtime.Item.Id,
                chunkIndex,
                responseEnvelope?.Error?.Code ??
                responseValidation.ErrorCode ??
                $"chunk_http_{(int)response.StatusCode}");
        }

        return payload!;
    }

    private async Task<FileTransferCompleteResponseDto> SendCompleteAsync(
        OutgoingTransferRuntime runtime,
        ConnectionSessionSnapshot session,
        string senderId)
    {
        var localDevice = await _connectionService.GetLocalDeviceProfileAsync();
        runtime.CurrentRequestCts?.Dispose();
        runtime.CurrentRequestCts = new CancellationTokenSource(TimeSpan.FromSeconds(AppConstants.TransferRequestTimeoutSeconds));

        var request = new FileTransferCompleteRequestDto(
            TransferId: runtime.Item.Id,
            SessionId: session.SessionId,
            SenderId: senderId,
            TotalChunks: runtime.Item.TotalChunks,
            TotalBytes: runtime.Item.TotalBytes,
            SentAtUtc: DateTimeOffset.UtcNow);

        if (session.TransportMode == AppConnectionMode.BluetoothFallback)
        {
            return await _bluetoothConnectionService.SendTransferCompleteAsync(request, runtime.CurrentRequestCts.Token);
        }

        using var response = await _httpClient.PostAsJsonAsync(
            BuildPeerUri(session.Peer, AppConstants.TransferCompletePath),
            ProtocolEnvelopeFactory.Create(
                packetType: ProtocolPacketTypes.TransferCompleteRequest,
                payload: request,
                senderDeviceId: localDevice.DeviceId,
                receiverDeviceId: session.Peer.Id,
                sessionId: session.SessionId,
                messageId: runtime.Item.Id,
                sentAtUtc: request.SentAtUtc),
            JsonDefaults.Options,
            runtime.CurrentRequestCts.Token);

        var responseEnvelope = await response.Content.ReadEnvelopeAsync<FileTransferCompleteResponseDto>(
            runtime.CurrentRequestCts.Token);
        var responseValidation = ProtocolEnvelopeValidator.Validate(
            responseEnvelope,
            expectedPacketTypes: [ProtocolPacketTypes.TransferCompleteResponse]);
        var payload = responseValidation.IsValid ? responseEnvelope!.Payload! : null;

        if (!response.IsSuccessStatusCode || payload is null)
        {
            return CreateCompleteFailure(
                runtime.Item.Id,
                responseEnvelope?.Error?.Code ??
                responseValidation.ErrorCode ??
                $"complete_http_{(int)response.StatusCode}");
        }

        return payload!;
    }

    private async Task<FileTransferCancelResponseDto> SendCancelAsync(
        string transferId,
        string peerId,
        string reason,
        CancellationToken cancellationToken)
    {
        var session = await GetSessionForTransferPeerAsync(peerId, cancellationToken);
        if (session is null)
        {
            return CreateCancelFailure(transferId, "peer_not_connected");
        }

        var localDevice = await _connectionService.GetLocalDeviceProfileAsync(cancellationToken);
        var requestPayload = new FileTransferCancelRequestDto(
            TransferId: transferId,
            SessionId: session.SessionId,
            SenderId: localDevice.DeviceId,
            Reason: reason,
            SentAtUtc: DateTimeOffset.UtcNow);

        if (session.TransportMode == AppConnectionMode.BluetoothFallback)
        {
            return await _bluetoothConnectionService.SendTransferCancelAsync(requestPayload, cancellationToken);
        }

        using var response = await _httpClient.PostAsJsonAsync(
            BuildPeerUri(session.Peer, AppConstants.TransferCancelPath),
            ProtocolEnvelopeFactory.Create(
                packetType: ProtocolPacketTypes.TransferCancelRequest,
                payload: requestPayload,
                senderDeviceId: localDevice.DeviceId,
                receiverDeviceId: session.Peer.Id,
                sessionId: session.SessionId,
                messageId: transferId,
                sentAtUtc: requestPayload.SentAtUtc),
            JsonDefaults.Options,
            cancellationToken);

        var responseEnvelope = await response.Content.ReadEnvelopeAsync<FileTransferCancelResponseDto>(
            cancellationToken);
        var responseValidation = ProtocolEnvelopeValidator.Validate(
            responseEnvelope,
            expectedPacketTypes: [ProtocolPacketTypes.TransferCancelResponse]);
        var payload = responseValidation.IsValid ? responseEnvelope!.Payload! : null;

        if (!response.IsSuccessStatusCode || payload is null)
        {
            return CreateCancelFailure(
                transferId,
                responseEnvelope?.Error?.Code ??
                responseValidation.ErrorCode ??
                $"cancel_http_{(int)response.StatusCode}");
        }

        return payload!;
    }

    private async Task FinalizeOutgoingCancellationAsync(OutgoingTransferRuntime runtime, string reason)
    {
        await UpdateTransferAsync(runtime.Item, item =>
        {
            item.Status = TransferState.Canceled;
            item.LastError = reason;
            item.CompletedAtUtc = DateTimeOffset.UtcNow;
            item.SpeedBytesPerSecond = 0;
            item.EstimatedSecondsRemaining = 0;
        });

        var receipt = await SendCancelAsync(runtime.Item.Id, runtime.Item.PeerId, "sender_canceled", CancellationToken.None);
        if (!receipt.Accepted && !string.Equals(receipt.FailureReason, "peer_not_connected", StringComparison.OrdinalIgnoreCase))
        {
                await _loggerService.LogWarningAsync(
                $"[TRANSFER] Remote cancel notification failed for {runtime.Item.FileName}: {receipt.FailureReason}.");
        }

        RemoveOutgoingRuntime(runtime.Item.Id);
        StartNextOutgoingWorker();
    }

    private async Task MarkOutgoingFailedAsync(OutgoingTransferRuntime runtime, string reason)
    {
        await UpdateTransferAsync(runtime.Item, item =>
        {
            item.Status = TransferState.Failed;
            item.LastError = reason;
            item.SpeedBytesPerSecond = 0;
            item.EstimatedSecondsRemaining = 0;
        });

        await _loggerService.LogWarningAsync($"[TRANSFER] {runtime.Item.FileName} failed: {reason}.");
        RemoveOutgoingRuntime(runtime.Item.Id);
        StartNextOutgoingWorker();
    }

    private async Task<(bool Recovered, ConnectionSessionSnapshot? Session)> TryRecoverOutgoingTransferAsync(
        OutgoingTransferRuntime runtime,
        string reason,
        CancellationToken cancellationToken)
    {
        if (!IsRecoverableTransferFailure(reason) ||
            runtime.RecoveryAttempts >= AppConstants.TransferRecoveryRetryLimit ||
            runtime.CancelRequested ||
            runtime.PauseRequested)
        {
            return (false, null);
        }

        runtime.RecoveryAttempts++;
        await _loggerService.LogWarningAsync(
            $"[TRANSFER] Attempting recovery {runtime.RecoveryAttempts}/{AppConstants.TransferRecoveryRetryLimit} for {runtime.Item.FileName}: {reason}.",
            cancellationToken);

        await Task.Delay(TimeSpan.FromSeconds(Math.Min(runtime.RecoveryAttempts, 3)), cancellationToken);

        var session = await GetSessionForTransferPeerAsync(runtime.Item.PeerId, cancellationToken);
        if (session is null)
        {
            return (false, null);
        }

        var localDevice = await _connectionService.GetLocalDeviceProfileAsync(cancellationToken);
        var prepareRequest = new FileTransferPrepareRequestDto(
            TransferId: runtime.Item.Id,
            SessionId: session.SessionId,
            SenderId: localDevice.DeviceId,
            SenderName: localDevice.DeviceName,
            ReceiverId: session.Peer.Id,
            FileName: runtime.Item.FileName,
            FileSize: runtime.Item.TotalBytes,
            MimeType: runtime.Item.MimeType,
            Kind: runtime.Item.Kind,
            FileCreatedAtUtc: runtime.Item.FileCreatedAtUtc,
            ChunkSize: runtime.Item.ChunkSize,
            TotalChunks: runtime.Item.TotalChunks,
            RequestedAtUtc: DateTimeOffset.UtcNow);

        var prepareResponse = await SendPrepareAsync(runtime, session, prepareRequest);
        if (!prepareResponse.Accepted)
        {
            await _loggerService.LogWarningAsync(
                $"[TRANSFER] Recovery prepare failed for {runtime.Item.FileName}: {prepareResponse.FailureReason ?? "prepare_failed"}.",
                cancellationToken);
            return (false, null);
        }

        var resumedChunkIndex = Math.Clamp(prepareResponse.NextExpectedChunkIndex, 0, runtime.Item.TotalChunks);
        var resumedBytes = Math.Clamp(prepareResponse.ReceivedBytes, 0, runtime.Item.TotalBytes);
        runtime.RestartMetrics(resumedBytes);

        await UpdateTransferAsync(runtime.Item, item =>
        {
            item.Status = TransferState.Sending;
            item.TransferredBytes = resumedBytes;
            item.ProcessedChunks = resumedChunkIndex;
            item.SpeedBytesPerSecond = 0;
            item.EstimatedSecondsRemaining = item.TotalBytes > resumedBytes ? item.TotalBytes - resumedBytes : 0;
            item.LastError = string.Empty;
        }, cancellationToken);

        return (true, session);
    }

    private static bool IsRecoverableTransferFailure(string? reason)
    {
        if (string.IsNullOrWhiteSpace(reason))
        {
            return true;
        }

        return reason.StartsWith("chunk_http_", StringComparison.OrdinalIgnoreCase) ||
               reason.StartsWith("prepare_http_", StringComparison.OrdinalIgnoreCase) ||
               reason.StartsWith("complete_http_", StringComparison.OrdinalIgnoreCase) ||
               reason.Contains("timeout", StringComparison.OrdinalIgnoreCase) ||
               reason.Contains("timed out", StringComparison.OrdinalIgnoreCase) ||
               reason.Contains("canceled", StringComparison.OrdinalIgnoreCase) ||
               reason is "transfer_not_found" or "unexpected_chunk_position" or "peer_not_connected";
    }

    private async Task<ConnectionSessionSnapshot?> GetSessionForTransferPeerAsync(
        string peerId,
        CancellationToken cancellationToken = default)
    {
        var session = await _connectionService.GetActiveSessionAsync(cancellationToken);
        if (session is null ||
            !session.IsConnected ||
            !string.Equals(session.Peer.Id, peerId, StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        return session;
    }

    private bool TryGetOutgoingRuntime(string transferId, out OutgoingTransferRuntime runtime)
    {
        lock (_outgoingGate)
        {
            return _outgoingTransfers.TryGetValue(transferId, out runtime!);
        }
    }

    private void RemoveOutgoingRuntime(string transferId)
    {
        OutgoingTransferRuntime? runtime;
        lock (_outgoingGate)
        {
            if (!_outgoingTransfers.Remove(transferId, out runtime))
            {
                return;
            }
        }

        runtime?.CurrentRequestCts?.Dispose();
    }

    private static MultipartFormDataContent CreateChunkContent(
        string senderDeviceId,
        ConnectionSessionSnapshot session,
        string transferId,
        byte[] chunk,
        int chunkIndex,
        long chunkOffset,
        int chunkLength)
    {
        var metadataEnvelope = ProtocolEnvelopeFactory.Create(
            packetType: ProtocolPacketTypes.TransferChunkRequest,
            payload: new FileTransferChunkDescriptorDto(
                TransferId: transferId,
                SessionId: session.SessionId,
                SenderId: senderDeviceId,
                ChunkIndex: chunkIndex,
                ChunkOffset: chunkOffset,
                ChunkLength: chunkLength),
            senderDeviceId: senderDeviceId,
            receiverDeviceId: session.Peer.Id,
            sessionId: session.SessionId,
            messageId: $"{transferId}-{chunkIndex}",
            sentAtUtc: DateTimeOffset.UtcNow);

        var multipart = new MultipartFormDataContent();
        var metadataContent = new StringContent(
            System.Text.Json.JsonSerializer.Serialize(metadataEnvelope, JsonDefaults.Options));
        metadataContent.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        multipart.Add(metadataContent, ProtocolConstants.MultipartMetadataPartName);

        var binaryContent = new ByteArrayContent(chunk, 0, chunkLength);
        binaryContent.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        multipart.Add(binaryContent, ProtocolConstants.MultipartBinaryPartName, "chunk.bin");

        return multipart;
    }

    private sealed class OutgoingTransferRuntime
    {
        public OutgoingTransferRuntime(TransferItem item)
        {
            Item = item;
        }

        public object SyncRoot { get; } = new();

        public SemaphoreSlim Gate { get; } = new(1, 1);

        public TransferItem Item { get; }

        public bool PauseRequested { get; set; }

        public bool CancelRequested { get; set; }

        public CancellationTokenSource? CurrentRequestCts { get; set; }

        public Task? WorkerTask { get; set; }

        public Stopwatch Stopwatch { get; } = new();

        public long MetricStartBytes { get; private set; }

        public int RecoveryAttempts { get; set; }

        public void RestartMetrics(long transferredBytes)
        {
            MetricStartBytes = transferredBytes;
            Stopwatch.Reset();
            Stopwatch.Start();
        }
    }
}
