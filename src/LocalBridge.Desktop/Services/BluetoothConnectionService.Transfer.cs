using System.IO;
using System.Text.Json;
using LocalBridge.Core;
using LocalBridge.Core.Protocol;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Infrastructure;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Services;

public sealed partial class BluetoothConnectionService
{
    private IFileTransferEndpointHandler? _fileTransferHandler;

    public void RegisterFileTransferHandler(IFileTransferEndpointHandler handler)
    {
        _fileTransferHandler = handler;
    }

    public async Task<FileTransferPrepareResponseDto> SendTransferPrepareAsync(
        FileTransferPrepareRequestDto request,
        CancellationToken cancellationToken = default)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        var session = _activeSession;
        if (session is null || _boundState?.IsConnected != true || string.IsNullOrWhiteSpace(session.SessionId))
        {
            return CreateTransferPrepareFailure(request.TransferId, ProtocolErrorCodes.NotConnected, localDevice);
        }

        if (request.FileSize > AppConstants.BluetoothSmallFileTransferLimitBytes)
        {
            return CreateTransferPrepareFailure(request.TransferId, "file_size_not_supported", localDevice);
        }

        return await SendTimedRequestAsync<FileTransferPrepareRequestDto, FileTransferPrepareResponseDto>(
                   session,
                   packetType: ProtocolPacketTypes.TransferPrepareRequest,
                   expectedResponsePacketType: ProtocolPacketTypes.TransferPrepareResponse,
                   payload: request,
                   receiverDeviceId: session.Peer.Id,
                   sessionId: session.SessionId,
                   messageId: request.TransferId,
                   sentAtUtc: request.RequestedAtUtc,
                   timeoutSeconds: AppConstants.TransferRequestTimeoutSeconds,
                   cancellationToken: cancellationToken)
               ?? CreateTransferPrepareFailure(request.TransferId, "bluetooth_prepare_timeout", localDevice);
    }

    public async Task<FileTransferChunkResponseDto> SendTransferChunkAsync(
        FileTransferChunkDescriptorDto descriptor,
        byte[] chunkPayload,
        CancellationToken cancellationToken = default)
    {
        var session = _activeSession;
        if (session is null || _boundState?.IsConnected != true || string.IsNullOrWhiteSpace(session.SessionId))
        {
            return CreateTransferChunkFailure(descriptor.TransferId, descriptor.ChunkIndex, ProtocolErrorCodes.NotConnected);
        }

        if (descriptor.ChunkLength <= 0)
        {
            return CreateTransferChunkFailure(descriptor.TransferId, descriptor.ChunkIndex, ProtocolErrorCodes.InvalidTransferChunk);
        }

        if (chunkPayload.Length < descriptor.ChunkLength)
        {
            await _loggerService.LogWarningAsync(
                $"Bluetooth outgoing chunk {descriptor.TransferId}/{descriptor.ChunkIndex} is shorter than expected. " +
                $"Expected {descriptor.ChunkLength} byte(s) but only {chunkPayload.Length} byte(s) were available.",
                cancellationToken);
            return CreateTransferChunkFailure(descriptor.TransferId, descriptor.ChunkIndex, "incomplete_local_chunk_payload");
        }

        var normalizedPayload = chunkPayload.Length == descriptor.ChunkLength
            ? chunkPayload
            : chunkPayload[..descriptor.ChunkLength];

        return await SendTimedBinaryRequestAsync<FileTransferChunkDescriptorDto, FileTransferChunkResponseDto>(
                   session,
                   packetType: ProtocolPacketTypes.TransferChunkRequest,
                   expectedResponsePacketType: ProtocolPacketTypes.TransferChunkResponse,
                   payload: descriptor,
                   binaryPayload: normalizedPayload,
                   receiverDeviceId: session.Peer.Id,
                   sessionId: session.SessionId,
                   messageId: $"{descriptor.TransferId}-{descriptor.ChunkIndex}",
                   timeoutSeconds: AppConstants.TransferRequestTimeoutSeconds,
                   cancellationToken: cancellationToken)
               ?? CreateTransferChunkFailure(descriptor.TransferId, descriptor.ChunkIndex, "bluetooth_chunk_timeout");
    }

    public async Task<FileTransferCompleteResponseDto> SendTransferCompleteAsync(
        FileTransferCompleteRequestDto request,
        CancellationToken cancellationToken = default)
    {
        var session = _activeSession;
        if (session is null || _boundState?.IsConnected != true || string.IsNullOrWhiteSpace(session.SessionId))
        {
            return CreateTransferCompleteFailure(request.TransferId, ProtocolErrorCodes.NotConnected);
        }

        return await SendTimedRequestAsync<FileTransferCompleteRequestDto, FileTransferCompleteResponseDto>(
                   session,
                   packetType: ProtocolPacketTypes.TransferCompleteRequest,
                   expectedResponsePacketType: ProtocolPacketTypes.TransferCompleteResponse,
                   payload: request,
                   receiverDeviceId: session.Peer.Id,
                   sessionId: session.SessionId,
                   messageId: request.TransferId,
                   sentAtUtc: request.SentAtUtc,
                   timeoutSeconds: AppConstants.TransferRequestTimeoutSeconds,
                   cancellationToken: cancellationToken)
               ?? CreateTransferCompleteFailure(request.TransferId, "bluetooth_complete_timeout");
    }

    public async Task<FileTransferCancelResponseDto> SendTransferCancelAsync(
        FileTransferCancelRequestDto request,
        CancellationToken cancellationToken = default)
    {
        var session = _activeSession;
        if (session is null || _boundState?.IsConnected != true || string.IsNullOrWhiteSpace(session.SessionId))
        {
            return CreateTransferCancelFailure(request.TransferId, ProtocolErrorCodes.NotConnected);
        }

        return await SendTimedRequestAsync<FileTransferCancelRequestDto, FileTransferCancelResponseDto>(
                   session,
                   packetType: ProtocolPacketTypes.TransferCancelRequest,
                   expectedResponsePacketType: ProtocolPacketTypes.TransferCancelResponse,
                   payload: request,
                   receiverDeviceId: session.Peer.Id,
                   sessionId: session.SessionId,
                   messageId: request.TransferId,
                   sentAtUtc: request.SentAtUtc,
                   timeoutSeconds: AppConstants.TransferRequestTimeoutSeconds,
                   cancellationToken: cancellationToken)
               ?? CreateTransferCancelFailure(request.TransferId, "bluetooth_cancel_timeout");
    }

    private async Task HandleTransferPrepareRequestAsync(
        BluetoothSession session,
        ProtocolEnvelope<JsonElement> envelope,
        CancellationToken cancellationToken)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        var validation = ProtocolEnvelopeValidator.Validate(
            envelope,
            expectedPacketTypes: [ProtocolPacketTypes.TransferPrepareRequest]);
        var request = DeserializePayload<FileTransferPrepareRequestDto>(envelope);

        if (!validation.IsValid)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferPrepareResponse,
                errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferPrepare,
                errorMessage: validation.ErrorMessage ?? "Bluetooth transfer prepare request is malformed.",
                payload: CreateTransferPrepareFailure(
                    request?.TransferId ?? string.Empty,
                    validation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferPrepare,
                    localDevice),
                receiverDeviceId: request?.SenderId,
                sessionId: request?.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (request is null ||
            string.IsNullOrWhiteSpace(request.TransferId) ||
            string.IsNullOrWhiteSpace(request.SessionId) ||
            string.IsNullOrWhiteSpace(request.SenderId) ||
            string.IsNullOrWhiteSpace(request.FileName) ||
            request.FileSize <= 0 ||
            request.ChunkSize <= 0 ||
            request.TotalChunks <= 0)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferPrepareResponse,
                errorCode: ProtocolErrorCodes.InvalidTransferPrepare,
                errorMessage: "Bluetooth transfer prepare request is missing required metadata.",
                payload: CreateTransferPrepareFailure(request?.TransferId ?? string.Empty, ProtocolErrorCodes.InvalidTransferPrepare, localDevice),
                receiverDeviceId: request?.SenderId,
                sessionId: request?.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (!string.Equals(session.SessionId, request.SessionId, StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(session.Peer.Id, request.SenderId, StringComparison.OrdinalIgnoreCase))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferPrepareResponse,
                errorCode: ProtocolErrorCodes.SessionNotFound,
                errorMessage: "The Bluetooth transfer session is not active on this host.",
                payload: CreateTransferPrepareFailure(request.TransferId, ProtocolErrorCodes.SessionNotFound, localDevice),
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (request.FileSize > AppConstants.BluetoothSmallFileTransferLimitBytes)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferPrepareResponse,
                errorCode: "file_size_not_supported",
                errorMessage: "Bluetooth fallback supports files up to 300 MB only.",
                payload: CreateTransferPrepareFailure(request.TransferId, "file_size_not_supported", localDevice),
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (_fileTransferHandler is null)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferPrepareResponse,
                errorCode: ProtocolErrorCodes.TransferServiceUnavailable,
                errorMessage: "Bluetooth transfer service is not ready on this host.",
                payload: CreateTransferPrepareFailure(request.TransferId, ProtocolErrorCodes.TransferServiceUnavailable, localDevice),
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        var snapshot = await BuildTransferSnapshotAsync(session, cancellationToken);
        FileTransferPrepareResponseDto response;
        try
        {
            response = await _fileTransferHandler.PrepareIncomingTransferAsync(request, snapshot, cancellationToken);
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync($"Bluetooth transfer prepare failed: {ex.Message}", cancellationToken);
            response = CreateTransferPrepareFailure(request.TransferId, ex.Message, localDevice);
        }

        if (response.Accepted)
        {
            await SendResponseEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferPrepareResponse,
                payload: response,
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
        }
        else
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferPrepareResponse,
                errorCode: response.FailureReason ?? ProtocolErrorCodes.InvalidTransferPrepare,
                errorMessage: response.FailureReason ?? "Bluetooth transfer prepare failed.",
                payload: response,
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
        }
    }

    private async Task HandleTransferChunkRequestAsync(
        BluetoothSession session,
        ProtocolEnvelope<JsonElement> envelope,
        byte[]? binaryPayload,
        CancellationToken cancellationToken)
    {
        var validation = ProtocolEnvelopeValidator.Validate(
            envelope,
            expectedPacketTypes: [ProtocolPacketTypes.TransferChunkRequest]);
        var request = DeserializePayload<FileTransferChunkDescriptorDto>(envelope);

        if (!validation.IsValid)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferChunkResponse,
                errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferChunk,
                errorMessage: validation.ErrorMessage ?? "Bluetooth transfer chunk metadata is malformed.",
                payload: CreateTransferChunkFailure(
                    request?.TransferId ?? string.Empty,
                    request?.ChunkIndex ?? 0,
                    validation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferChunk),
                receiverDeviceId: request?.SenderId,
                sessionId: request?.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (request is null ||
            string.IsNullOrWhiteSpace(request.TransferId) ||
            string.IsNullOrWhiteSpace(request.SessionId) ||
            string.IsNullOrWhiteSpace(request.SenderId) ||
            request.ChunkIndex < 0 ||
            request.ChunkOffset < 0 ||
            request.ChunkLength <= 0 ||
            binaryPayload is null ||
            binaryPayload.Length == 0)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferChunkResponse,
                errorCode: ProtocolErrorCodes.InvalidTransferChunk,
                errorMessage: "Bluetooth transfer chunk metadata or binary payload is missing.",
                payload: CreateTransferChunkFailure(request?.TransferId ?? string.Empty, request?.ChunkIndex ?? 0, ProtocolErrorCodes.InvalidTransferChunk),
                receiverDeviceId: request?.SenderId,
                sessionId: request?.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (!string.Equals(session.SessionId, request.SessionId, StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(session.Peer.Id, request.SenderId, StringComparison.OrdinalIgnoreCase))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferChunkResponse,
                errorCode: ProtocolErrorCodes.SessionNotFound,
                errorMessage: "The Bluetooth transfer session is not active on this host.",
                payload: CreateTransferChunkFailure(request.TransferId, request.ChunkIndex, ProtocolErrorCodes.SessionNotFound),
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (_fileTransferHandler is null)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferChunkResponse,
                errorCode: ProtocolErrorCodes.TransferServiceUnavailable,
                errorMessage: "Bluetooth transfer service is not ready on this host.",
                payload: CreateTransferChunkFailure(request.TransferId, request.ChunkIndex, ProtocolErrorCodes.TransferServiceUnavailable),
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        var snapshot = await BuildTransferSnapshotAsync(session, cancellationToken);
        FileTransferChunkResponseDto response;
        try
        {
            await using var contentStream = new MemoryStream(binaryPayload, writable: false);
            response = await _fileTransferHandler.ReceiveChunkAsync(request, contentStream, snapshot, cancellationToken);
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync($"Bluetooth transfer chunk failed: {ex.Message}", cancellationToken);
            response = CreateTransferChunkFailure(request.TransferId, request.ChunkIndex, ex.Message);
        }

        if (response.Accepted)
        {
            await SendResponseEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferChunkResponse,
                payload: response,
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
        }
        else
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferChunkResponse,
                errorCode: response.FailureReason ?? ProtocolErrorCodes.InvalidTransferChunk,
                errorMessage: response.FailureReason ?? "Bluetooth transfer chunk failed.",
                payload: response,
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
        }
    }

    private async Task HandleTransferCompleteRequestAsync(
        BluetoothSession session,
        ProtocolEnvelope<JsonElement> envelope,
        CancellationToken cancellationToken)
    {
        var validation = ProtocolEnvelopeValidator.Validate(
            envelope,
            expectedPacketTypes: [ProtocolPacketTypes.TransferCompleteRequest]);
        var request = DeserializePayload<FileTransferCompleteRequestDto>(envelope);

        if (!validation.IsValid)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferCompleteResponse,
                errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferComplete,
                errorMessage: validation.ErrorMessage ?? "Bluetooth transfer complete request is malformed.",
                payload: CreateTransferCompleteFailure(
                    request?.TransferId ?? string.Empty,
                    validation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferComplete),
                receiverDeviceId: request?.SenderId,
                sessionId: request?.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (request is null ||
            string.IsNullOrWhiteSpace(request.TransferId) ||
            string.IsNullOrWhiteSpace(request.SessionId) ||
            string.IsNullOrWhiteSpace(request.SenderId))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferCompleteResponse,
                errorCode: ProtocolErrorCodes.InvalidTransferComplete,
                errorMessage: "Bluetooth transfer completion metadata is missing.",
                payload: CreateTransferCompleteFailure(request?.TransferId ?? string.Empty, ProtocolErrorCodes.InvalidTransferComplete),
                receiverDeviceId: request?.SenderId,
                sessionId: request?.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (!string.Equals(session.SessionId, request.SessionId, StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(session.Peer.Id, request.SenderId, StringComparison.OrdinalIgnoreCase))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferCompleteResponse,
                errorCode: ProtocolErrorCodes.SessionNotFound,
                errorMessage: "The Bluetooth transfer session is not active on this host.",
                payload: CreateTransferCompleteFailure(request.TransferId, ProtocolErrorCodes.SessionNotFound),
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (_fileTransferHandler is null)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferCompleteResponse,
                errorCode: ProtocolErrorCodes.TransferServiceUnavailable,
                errorMessage: "Bluetooth transfer service is not ready on this host.",
                payload: CreateTransferCompleteFailure(request.TransferId, ProtocolErrorCodes.TransferServiceUnavailable),
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        var snapshot = await BuildTransferSnapshotAsync(session, cancellationToken);
        FileTransferCompleteResponseDto response;
        try
        {
            response = await _fileTransferHandler.CompleteIncomingTransferAsync(request, snapshot, cancellationToken);
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync($"Bluetooth transfer completion failed: {ex.Message}", cancellationToken);
            response = CreateTransferCompleteFailure(request.TransferId, ex.Message);
        }

        if (response.Accepted)
        {
            await SendResponseEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferCompleteResponse,
                payload: response,
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
        }
        else
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferCompleteResponse,
                errorCode: response.FailureReason ?? ProtocolErrorCodes.InvalidTransferComplete,
                errorMessage: response.FailureReason ?? "Bluetooth transfer completion failed.",
                payload: response,
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
        }
    }

    private async Task HandleTransferCancelRequestAsync(
        BluetoothSession session,
        ProtocolEnvelope<JsonElement> envelope,
        CancellationToken cancellationToken)
    {
        var validation = ProtocolEnvelopeValidator.Validate(
            envelope,
            expectedPacketTypes: [ProtocolPacketTypes.TransferCancelRequest]);
        var request = DeserializePayload<FileTransferCancelRequestDto>(envelope);

        if (!validation.IsValid)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferCancelResponse,
                errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferCancel,
                errorMessage: validation.ErrorMessage ?? "Bluetooth transfer cancel request is malformed.",
                payload: CreateTransferCancelFailure(
                    request?.TransferId ?? string.Empty,
                    validation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferCancel),
                receiverDeviceId: request?.SenderId,
                sessionId: request?.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (request is null ||
            string.IsNullOrWhiteSpace(request.TransferId) ||
            string.IsNullOrWhiteSpace(request.SessionId) ||
            string.IsNullOrWhiteSpace(request.SenderId))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferCancelResponse,
                errorCode: ProtocolErrorCodes.InvalidTransferCancel,
                errorMessage: "Bluetooth transfer cancel metadata is missing.",
                payload: CreateTransferCancelFailure(request?.TransferId ?? string.Empty, ProtocolErrorCodes.InvalidTransferCancel),
                receiverDeviceId: request?.SenderId,
                sessionId: request?.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (!string.Equals(session.SessionId, request.SessionId, StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(session.Peer.Id, request.SenderId, StringComparison.OrdinalIgnoreCase))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferCancelResponse,
                errorCode: ProtocolErrorCodes.SessionNotFound,
                errorMessage: "The Bluetooth transfer session is not active on this host.",
                payload: CreateTransferCancelFailure(request.TransferId, ProtocolErrorCodes.SessionNotFound),
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (_fileTransferHandler is null)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferCancelResponse,
                errorCode: ProtocolErrorCodes.TransferServiceUnavailable,
                errorMessage: "Bluetooth transfer service is not ready on this host.",
                payload: CreateTransferCancelFailure(request.TransferId, ProtocolErrorCodes.TransferServiceUnavailable),
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        var snapshot = await BuildTransferSnapshotAsync(session, cancellationToken);
        FileTransferCancelResponseDto response;
        try
        {
            response = await _fileTransferHandler.CancelIncomingTransferAsync(request, snapshot, cancellationToken);
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync($"Bluetooth transfer cancel failed: {ex.Message}", cancellationToken);
            response = CreateTransferCancelFailure(request.TransferId, ex.Message);
        }

        if (response.Accepted)
        {
            await SendResponseEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferCancelResponse,
                payload: response,
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
        }
        else
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.TransferCancelResponse,
                errorCode: response.FailureReason ?? ProtocolErrorCodes.InvalidTransferCancel,
                errorMessage: response.FailureReason ?? "Bluetooth transfer cancel failed.",
                payload: response,
                receiverDeviceId: request.SenderId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
        }
    }

    private async Task<ConnectionSessionSnapshot> BuildTransferSnapshotAsync(
        BluetoothSession session,
        CancellationToken cancellationToken)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        return new ConnectionSessionSnapshot
        {
            SessionId = session.SessionId,
            LocalDeviceId = localDevice.DeviceId,
            LocalDeviceName = localDevice.DeviceName,
            Peer = ClonePeer(session.Peer),
            IsConnected = _boundState?.IsConnected ?? true,
            IsIncoming = session.IsIncoming,
            TransportMode = AppConnectionMode.BluetoothFallback
        };
    }

    private async Task<TResponse?> SendTimedRequestAsync<TRequest, TResponse>(
        BluetoothSession session,
        string packetType,
        string expectedResponsePacketType,
        TRequest payload,
        string? receiverDeviceId,
        string? sessionId,
        string? messageId,
        DateTimeOffset? sentAtUtc,
        int timeoutSeconds,
        CancellationToken cancellationToken)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        var resolvedMessageId = string.IsNullOrWhiteSpace(messageId) ? Guid.NewGuid().ToString("N") : messageId;
        var envelope = ProtocolEnvelopeFactory.Create(
            packetType: packetType,
            payload: payload,
            senderDeviceId: localDevice.DeviceId,
            receiverDeviceId: receiverDeviceId,
            sessionId: sessionId,
            messageId: resolvedMessageId,
            sentAtUtc: sentAtUtc ?? DateTimeOffset.UtcNow);

        var waiter = new TaskCompletionSource<ProtocolEnvelope<JsonElement>?>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        session.PendingResponses[resolvedMessageId] = waiter;

        try
        {
            await SendEnvelopeAsync(session, envelope, cancellationToken);
            var responseEnvelope = await waiter.Task.WaitAsync(TimeSpan.FromSeconds(timeoutSeconds), cancellationToken);
            var validation = ProtocolEnvelopeValidator.Validate(
                responseEnvelope,
                expectedPacketTypes: [expectedResponsePacketType]);
            return validation.IsValid ? DeserializePayload<TResponse>(responseEnvelope!) : default;
        }
        catch (TimeoutException)
        {
            return default;
        }
        catch (OperationCanceledException)
        {
            return default;
        }
        finally
        {
            session.PendingResponses.TryRemove(resolvedMessageId, out _);
        }
    }

    private async Task<TResponse?> SendTimedBinaryRequestAsync<TRequest, TResponse>(
        BluetoothSession session,
        string packetType,
        string expectedResponsePacketType,
        TRequest payload,
        byte[] binaryPayload,
        string? receiverDeviceId,
        string? sessionId,
        string? messageId,
        int timeoutSeconds,
        CancellationToken cancellationToken)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        var resolvedMessageId = string.IsNullOrWhiteSpace(messageId) ? Guid.NewGuid().ToString("N") : messageId;
        var envelope = ProtocolEnvelopeFactory.Create(
            packetType: packetType,
            payload: payload,
            senderDeviceId: localDevice.DeviceId,
            receiverDeviceId: receiverDeviceId,
            sessionId: sessionId,
            messageId: resolvedMessageId,
            sentAtUtc: DateTimeOffset.UtcNow);

        var waiter = new TaskCompletionSource<ProtocolEnvelope<JsonElement>?>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        session.PendingResponses[resolvedMessageId] = waiter;

        try
        {
            await SendEnvelopeWithBinaryAsync(session, envelope, binaryPayload, cancellationToken);
            var responseEnvelope = await waiter.Task.WaitAsync(TimeSpan.FromSeconds(timeoutSeconds), cancellationToken);
            var validation = ProtocolEnvelopeValidator.Validate(
                responseEnvelope,
                expectedPacketTypes: [expectedResponsePacketType]);
            return validation.IsValid ? DeserializePayload<TResponse>(responseEnvelope!) : default;
        }
        catch (TimeoutException)
        {
            return default;
        }
        catch (OperationCanceledException)
        {
            return default;
        }
        finally
        {
            session.PendingResponses.TryRemove(resolvedMessageId, out _);
        }
    }

    private async Task SendEnvelopeWithBinaryAsync<TPayload>(
        BluetoothSession session,
        ProtocolEnvelope<TPayload> envelope,
        byte[] binaryPayload,
        CancellationToken cancellationToken)
    {
        var json = JsonSerializer.Serialize(envelope, JsonDefaults.Options);
        await session.SendGate.WaitAsync(cancellationToken);
        try
        {
            await BluetoothTransportFrameCodec.WriteJsonEnvelopeWithBinaryAsync(
                session.Stream,
                json,
                binaryPayload,
                cancellationToken);
        }
        finally
        {
            session.SendGate.Release();
        }
    }

    private static FileTransferPrepareResponseDto CreateTransferPrepareFailure(
        string transferId,
        string reason,
        LocalDeviceProfile localDevice)
    {
        return new FileTransferPrepareResponseDto(
            Accepted: false,
            TransferId: transferId,
            Status: ProtocolConstants.TransferStateFailed,
            FailureReason: reason,
            NextExpectedChunkIndex: 0,
            ReceivedBytes: 0,
            ReceiverDeviceId: localDevice.DeviceId,
            ReceiverDeviceName: localDevice.DeviceName,
            SuggestedFilePath: null,
            RespondedAtUtc: DateTimeOffset.UtcNow);
    }

    private static FileTransferChunkResponseDto CreateTransferChunkFailure(
        string transferId,
        int chunkIndex,
        string reason)
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

    private static FileTransferCompleteResponseDto CreateTransferCompleteFailure(string transferId, string reason)
    {
        return new FileTransferCompleteResponseDto(
            Accepted: false,
            TransferId: transferId,
            Status: ProtocolConstants.TransferStateFailed,
            FailureReason: reason,
            SavedFilePath: null,
            CompletedAtUtc: DateTimeOffset.UtcNow);
    }

    private static FileTransferCancelResponseDto CreateTransferCancelFailure(string transferId, string reason)
    {
        return new FileTransferCancelResponseDto(
            Accepted: false,
            TransferId: transferId,
            Status: ProtocolConstants.TransferStateFailed,
            FailureReason: reason,
            CanceledAtUtc: DateTimeOffset.UtcNow);
    }
}
