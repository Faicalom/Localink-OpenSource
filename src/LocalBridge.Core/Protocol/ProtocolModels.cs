namespace LocalBridge.Core.Protocol;

public static class ProtocolConstants
{
    public const string Version = "1.1";

    public const string MultipartMetadataPartName = "metadata";
    public const string MultipartBinaryPartName = "chunk";

    public const string SessionStateIdle = "idle";
    public const string SessionStateDiscovering = "discovering";
    public const string SessionStateConnecting = "connecting";
    public const string SessionStateWaitingForPairing = "waiting_for_pairing";
    public const string SessionStatePaired = "paired";
    public const string SessionStateConnected = "connected";
    public const string SessionStateTransferInProgress = "transfer_in_progress";
    public const string SessionStateDisconnected = "disconnected";
    public const string SessionStateFailed = "failed";

    public const string DeliveryStatusSending = "sending";
    public const string DeliveryStatusSent = "sent";
    public const string DeliveryStatusDelivered = "delivered";
    public const string DeliveryStatusFailed = "failed";

    public const string TransferStateQueued = "queued";
    public const string TransferStatePreparing = "preparing";
    public const string TransferStateSending = "sending";
    public const string TransferStateReceiving = "receiving";
    public const string TransferStatePaused = "paused";
    public const string TransferStateCompleted = "completed";
    public const string TransferStateFailed = "failed";
    public const string TransferStateCanceled = "canceled";
}

public static class ProtocolPacketTypes
{
    public const string DiscoveryProbe = "discovery.probe";
    public const string DiscoveryReply = "discovery.reply";
    public const string DiscoveryAnnouncement = "discovery.announcement";

    public const string ConnectionStatus = "connection.status";
    public const string ConnectionHandshakeRequest = "connection.handshake.request";
    public const string ConnectionHandshakeResponse = "connection.handshake.response";
    public const string ConnectionHeartbeatRequest = "connection.heartbeat.request";
    public const string ConnectionHeartbeatResponse = "connection.heartbeat.response";
    public const string ConnectionDisconnectRequest = "connection.disconnect.request";
    public const string ConnectionDisconnectResponse = "connection.disconnect.response";

    public const string ChatTextMessage = "chat.text.message";
    public const string ChatDeliveryReceipt = "chat.text.receipt";

    public const string TransferPrepareRequest = "transfer.prepare.request";
    public const string TransferPrepareResponse = "transfer.prepare.response";
    public const string TransferChunkRequest = "transfer.chunk.request";
    public const string TransferChunkResponse = "transfer.chunk.response";
    public const string TransferCompleteRequest = "transfer.complete.request";
    public const string TransferCompleteResponse = "transfer.complete.response";
    public const string TransferCancelRequest = "transfer.cancel.request";
    public const string TransferCancelResponse = "transfer.cancel.response";
}

public static class ProtocolErrorCodes
{
    public const string InvalidRequest = "invalid_request";
    public const string ProtocolMismatch = "protocol_mismatch";
    public const string SessionNotFound = "session_not_found";
    public const string SelfConnectionNotAllowed = "self_connection_not_allowed";
    public const string PairingTokenRequired = "pairing_token_required";
    public const string InvalidPairingToken = "invalid_pairing_token";
    public const string NotConnected = "not_connected";
    public const string EmptyMessage = "empty_message";
    public const string WrongReceiver = "wrong_receiver";
    public const string TransferServiceUnavailable = "transfer_service_unavailable";
    public const string InvalidTransferPrepare = "invalid_transfer_prepare";
    public const string InvalidTransferChunk = "invalid_transfer_chunk";
    public const string InvalidTransferComplete = "invalid_transfer_complete";
    public const string InvalidTransferCancel = "invalid_transfer_cancel";
}

public sealed record ProtocolMetadata(
    string Version,
    string PacketType,
    string MessageId,
    DateTimeOffset SentAtUtc,
    string? SessionId = null,
    string? SenderDeviceId = null,
    string? ReceiverDeviceId = null,
    string? CorrelationId = null);

public sealed record ProtocolError(
    string Code,
    string Message,
    bool IsRetryable = false,
    string? Details = null);

public sealed record ProtocolEnvelope<TPayload>(
    ProtocolMetadata Meta,
    TPayload? Payload,
    ProtocolError? Error = null);

public static class ProtocolEnvelopeFactory
{
    public static ProtocolEnvelope<TPayload> Create<TPayload>(
        string packetType,
        TPayload payload,
        string? senderDeviceId = null,
        string? receiverDeviceId = null,
        string? sessionId = null,
        string? correlationId = null,
        string? messageId = null,
        DateTimeOffset? sentAtUtc = null)
    {
        return new ProtocolEnvelope<TPayload>(
            Meta: new ProtocolMetadata(
                Version: ProtocolConstants.Version,
                PacketType: packetType,
                MessageId: string.IsNullOrWhiteSpace(messageId) ? Guid.NewGuid().ToString("N") : messageId,
                SentAtUtc: sentAtUtc ?? DateTimeOffset.UtcNow,
                SessionId: sessionId,
                SenderDeviceId: senderDeviceId,
                ReceiverDeviceId: receiverDeviceId,
                CorrelationId: correlationId),
            Payload: payload,
            Error: null);
    }

    public static ProtocolEnvelope<TPayload> CreateError<TPayload>(
        string packetType,
        string code,
        string message,
        TPayload? payload = default,
        string? senderDeviceId = null,
        string? receiverDeviceId = null,
        string? sessionId = null,
        string? correlationId = null,
        string? messageId = null,
        DateTimeOffset? sentAtUtc = null,
        bool isRetryable = false,
        string? details = null)
    {
        return new ProtocolEnvelope<TPayload>(
            Meta: new ProtocolMetadata(
                Version: ProtocolConstants.Version,
                PacketType: packetType,
                MessageId: string.IsNullOrWhiteSpace(messageId) ? Guid.NewGuid().ToString("N") : messageId,
                SentAtUtc: sentAtUtc ?? DateTimeOffset.UtcNow,
                SessionId: sessionId,
                SenderDeviceId: senderDeviceId,
                ReceiverDeviceId: receiverDeviceId,
                CorrelationId: correlationId),
            Payload: payload,
            Error: new ProtocolError(
                Code: code,
                Message: message,
                IsRetryable: isRetryable,
                Details: details));
    }
}

public sealed record StatusResponseDto(
    string ServerDeviceId,
    string ServerName,
    string PairingCode,
    int ApiPort,
    int DiscoveryPort,
    IReadOnlyList<string> LocalAddresses);

public sealed record ConnectionHandshakeRequestDto(
    string DeviceId,
    string DeviceName,
    string Platform,
    string AppVersion,
    string PairingToken,
    IReadOnlyList<string> SupportedModes);

public sealed record ConnectionHandshakeResponseDto(
    bool Accepted,
    string SessionState,
    string? SessionId,
    string? FailureReason,
    string ServerDeviceId,
    string ServerDeviceName,
    string ServerPlatform,
    string ServerAppVersion,
    IReadOnlyList<string> SupportedModes,
    DateTimeOffset IssuedAtUtc);

public sealed record ConnectionHeartbeatRequestDto(
    string SessionId,
    string DeviceId,
    string DeviceName,
    string Platform,
    string AppVersion);

public sealed record ConnectionHeartbeatResponseDto(
    bool Alive,
    string SessionState,
    string? FailureReason,
    string ServerDeviceId,
    string ServerDeviceName,
    string ServerPlatform,
    string ServerAppVersion,
    DateTimeOffset ReceivedAtUtc);

public sealed record ConnectionDisconnectRequestDto(
    string SessionId,
    string DeviceId,
    DateTimeOffset SentAtUtc);

public sealed record ConnectionDisconnectResponseDto(
    bool Acknowledged,
    string SessionId,
    DateTimeOffset ReceivedAtUtc);

public sealed record TextChatPacketDto(
    string Id,
    string SessionId,
    string SenderId,
    string SenderName,
    string ReceiverId,
    string Text,
    DateTimeOffset TimestampUtc);

public sealed record TextChatDeliveryReceiptDto(
    bool Accepted,
    string MessageId,
    string Status,
    string? FailureReason,
    string ReceiverDeviceId,
    string ReceiverDeviceName,
    DateTimeOffset ReceivedAtUtc);

public sealed record FileTransferPrepareRequestDto(
    string TransferId,
    string SessionId,
    string SenderId,
    string SenderName,
    string ReceiverId,
    string FileName,
    long FileSize,
    string MimeType,
    string Kind,
    DateTimeOffset FileCreatedAtUtc,
    int ChunkSize,
    int TotalChunks,
    DateTimeOffset RequestedAtUtc);

public sealed record FileTransferPrepareResponseDto(
    bool Accepted,
    string TransferId,
    string Status,
    string? FailureReason,
    int NextExpectedChunkIndex,
    long ReceivedBytes,
    string ReceiverDeviceId,
    string ReceiverDeviceName,
    string? SuggestedFilePath,
    DateTimeOffset RespondedAtUtc);

public sealed record FileTransferChunkDescriptorDto(
    string TransferId,
    string SessionId,
    string SenderId,
    int ChunkIndex,
    long ChunkOffset,
    int ChunkLength);

public sealed record FileTransferChunkResponseDto(
    bool Accepted,
    string TransferId,
    int ChunkIndex,
    string Status,
    string? FailureReason,
    int NextExpectedChunkIndex,
    long ReceivedBytes,
    DateTimeOffset RespondedAtUtc);

public sealed record FileTransferCompleteRequestDto(
    string TransferId,
    string SessionId,
    string SenderId,
    int TotalChunks,
    long TotalBytes,
    DateTimeOffset SentAtUtc);

public sealed record FileTransferCompleteResponseDto(
    bool Accepted,
    string TransferId,
    string Status,
    string? FailureReason,
    string? SavedFilePath,
    DateTimeOffset CompletedAtUtc);

public sealed record FileTransferCancelRequestDto(
    string TransferId,
    string SessionId,
    string SenderId,
    string Reason,
    DateTimeOffset SentAtUtc);

public sealed record FileTransferCancelResponseDto(
    bool Accepted,
    string TransferId,
    string Status,
    string? FailureReason,
    DateTimeOffset CanceledAtUtc);
