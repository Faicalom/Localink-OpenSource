using LocalBridge.Core.Protocol;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Models;
using LocalBridge.Desktop.Repositories;

namespace LocalBridge.Desktop.Services;

public sealed class ChatService : IChatService
{
    private readonly IConnectionService _connectionService;
    private readonly IChatRepository _chatRepository;
    private readonly ILoggerService _loggerService;
    private readonly SemaphoreSlim _messagesGate = new(1, 1);
    private readonly List<ChatMessage> _messages = [];

    private CancellationTokenSource? _retryCts;
    private Task? _retryTask;
    private bool _isInitialized;

    public ChatService(
        IConnectionService connectionService,
        IChatRepository chatRepository,
        ILoggerService loggerService)
    {
        _connectionService = connectionService;
        _chatRepository = chatRepository;
        _loggerService = loggerService;
    }

    public event Action<ChatMessage>? MessageAdded;

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        if (_isInitialized)
        {
            return;
        }

        var loadedMessages = await _chatRepository.LoadAsync(cancellationToken);

        await _messagesGate.WaitAsync(cancellationToken);
        try
        {
            _messages.Clear();
            _messages.AddRange(loadedMessages.OrderBy(message => message.TimestampUtc));
        }
        finally
        {
            _messagesGate.Release();
        }

        _connectionService.ChatMessageReceived += HandleIncomingTransportMessage;
        _connectionService.SessionChanged += HandleSessionChanged;

        StartRetryLoop();
        _isInitialized = true;

        await _loggerService.LogInfoAsync(
            $"Chat service initialized with {loadedMessages.Count} locally stored message(s).",
            cancellationToken);
    }

    public async Task ShutdownAsync(CancellationToken cancellationToken = default)
    {
        if (!_isInitialized)
        {
            return;
        }

        _connectionService.ChatMessageReceived -= HandleIncomingTransportMessage;
        _connectionService.SessionChanged -= HandleSessionChanged;

        _retryCts?.Cancel();
        _retryCts?.Dispose();
        _retryCts = null;
        _retryTask = null;

        await PersistMessagesAsync(cancellationToken);

        _isInitialized = false;
        await _loggerService.LogInfoAsync("Chat service stopped and local history persisted.", cancellationToken);
    }

    public async Task<IReadOnlyList<ChatMessage>> GetRecentMessagesAsync(CancellationToken cancellationToken = default)
    {
        await _messagesGate.WaitAsync(cancellationToken);
        try
        {
            return _messages
                .OrderBy(message => message.TimestampUtc)
                .ToList();
        }
        finally
        {
            _messagesGate.Release();
        }
    }

    public async Task<bool> SendMessageAsync(string text, CancellationToken cancellationToken = default)
    {
        var normalizedText = text.Trim();
        if (string.IsNullOrWhiteSpace(normalizedText))
        {
            return false;
        }

        var session = await _connectionService.GetActiveSessionAsync(cancellationToken);
        if (session is null || !session.IsConnected)
        {
            await _loggerService.LogWarningAsync("Chat send skipped because there is no active local peer connection.", cancellationToken);
            return false;
        }

        var localDevice = await _connectionService.GetLocalDeviceProfileAsync(cancellationToken);

        var message = new ChatMessage
        {
            Id = Guid.NewGuid().ToString("N"),
            SenderId = localDevice.DeviceId,
            ReceiverId = session.Peer.Id,
            SenderName = localDevice.DeviceName,
            Text = normalizedText,
            TimestampUtc = DateTimeOffset.UtcNow,
            Status = ChatMessageStatus.Sent,
            IsOutgoing = true,
            DeliveryAttempts = 0,
            LastError = string.Empty
        };

        await AddMessageAsync(message, cancellationToken);
        await TryDeliverMessageAsync(message, forceManualRetry: false, cancellationToken);
        return true;
    }

    public async Task RetryMessageAsync(ChatMessage message, CancellationToken cancellationToken = default)
    {
        await TryDeliverMessageAsync(message, forceManualRetry: true, cancellationToken);
    }

    public async Task<int> ClearHistoryAsync(CancellationToken cancellationToken = default)
    {
        await InitializeAsync(cancellationToken);

        int removedCount;
        await _messagesGate.WaitAsync(cancellationToken);
        try
        {
            removedCount = _messages.Count;
            _messages.Clear();
        }
        finally
        {
            _messagesGate.Release();
        }

        await PersistMessagesAsync(cancellationToken);
        await _loggerService.LogInfoAsync($"[HISTORY] Cleared {removedCount} chat message(s) from local desktop history.", cancellationToken);
        return removedCount;
    }

    private void HandleIncomingTransportMessage(TextChatPacketDto packet)
    {
        _ = ProcessIncomingTransportMessageAsync(packet);
    }

    private void HandleSessionChanged(ConnectionSessionSnapshot? session)
    {
        if (session is null || !session.IsConnected)
        {
            return;
        }

        _ = RetryPendingMessagesAsync();
    }

    private async Task ProcessIncomingTransportMessageAsync(TextChatPacketDto packet, CancellationToken cancellationToken = default)
    {
        ChatMessage? messageToPublish = null;

        await _messagesGate.WaitAsync(cancellationToken);
        try
        {
            var existing = _messages.FirstOrDefault(message => string.Equals(message.Id, packet.Id, StringComparison.OrdinalIgnoreCase));
            if (existing is not null)
            {
                return;
            }

            var message = new ChatMessage
            {
                Id = packet.Id,
                SenderId = packet.SenderId,
                ReceiverId = packet.ReceiverId,
                SenderName = packet.SenderName,
                Text = packet.Text,
                TimestampUtc = packet.TimestampUtc,
                Status = ChatMessageStatus.Delivered,
                IsOutgoing = false,
                DeliveryAttempts = 1
            };

            _messages.Add(message);
            _messages.Sort((left, right) => left.TimestampUtc.CompareTo(right.TimestampUtc));
            messageToPublish = message;
        }
        finally
        {
            _messagesGate.Release();
        }

        await PersistMessagesAsync(cancellationToken);

        if (messageToPublish is not null)
        {
            MessageAdded?.Invoke(messageToPublish);
            await _loggerService.LogInfoAsync(
                $"Received chat message from {messageToPublish.SenderName}.",
                cancellationToken);
        }
    }

    private void StartRetryLoop()
    {
        if (_retryTask is { IsCompleted: false })
        {
            return;
        }

        _retryCts?.Cancel();
        _retryCts?.Dispose();
        _retryCts = new CancellationTokenSource();

        var token = _retryCts.Token;
        _retryTask = Task.Run(async () =>
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(TimeSpan.FromSeconds(AppConstants.ChatRetryIntervalSeconds), token);
                    await RetryPendingMessagesAsync(token);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception ex)
                {
                    await _loggerService.LogWarningAsync($"Chat retry loop error: {ex.Message}", token);
                }
            }
        }, token);
    }

    private async Task RetryPendingMessagesAsync(CancellationToken cancellationToken = default)
    {
        var session = await _connectionService.GetActiveSessionAsync(cancellationToken);
        if (session is null || !session.IsConnected)
        {
            return;
        }

        List<ChatMessage> retryCandidates;

        await _messagesGate.WaitAsync(cancellationToken);
        try
        {
            retryCandidates = _messages
                .Where(message => message.IsOutgoing)
                .Where(message => string.Equals(message.ReceiverId, session.Peer.Id, StringComparison.OrdinalIgnoreCase))
                .Where(message => message.Status is ChatMessageStatus.Sent or ChatMessageStatus.Failed)
                .Where(message => message.DeliveryAttempts < AppConstants.ChatAutoRetryLimit)
                .OrderBy(message => message.TimestampUtc)
                .ToList();
        }
        finally
        {
            _messagesGate.Release();
        }

        foreach (var message in retryCandidates)
        {
            await TryDeliverMessageAsync(message, forceManualRetry: false, cancellationToken);
        }
    }

    private async Task TryDeliverMessageAsync(ChatMessage message, bool forceManualRetry, CancellationToken cancellationToken)
    {
        var session = await _connectionService.GetActiveSessionAsync(cancellationToken);
        if (session is null || !session.IsConnected)
        {
            message.Status = ChatMessageStatus.Failed;
            message.LastError = "peer_not_connected";
            await PersistMessagesAsync(cancellationToken);
            return;
        }

        if (!string.Equals(session.Peer.Id, message.ReceiverId, StringComparison.OrdinalIgnoreCase))
        {
            message.Status = ChatMessageStatus.Failed;
            message.LastError = "different_active_peer";
            await PersistMessagesAsync(cancellationToken);
            return;
        }

        if (!forceManualRetry && message.DeliveryAttempts >= AppConstants.ChatAutoRetryLimit)
        {
            return;
        }

        message.Status = ChatMessageStatus.Sending;
        await PersistMessagesAsync(cancellationToken);

        var packet = new TextChatPacketDto(
            Id: message.Id,
            SessionId: session.SessionId,
            SenderId: message.SenderId,
            SenderName: message.SenderName,
            ReceiverId: message.ReceiverId,
            Text: message.Text,
            TimestampUtc: message.TimestampUtc);

        var receipt = await _connectionService.SendChatMessageAsync(packet, cancellationToken);
        message.DeliveryAttempts++;

        if (receipt.Accepted && string.Equals(receipt.Status, ProtocolConstants.DeliveryStatusDelivered, StringComparison.OrdinalIgnoreCase))
        {
            message.Status = ChatMessageStatus.Delivered;
            message.LastError = string.Empty;
            await _loggerService.LogInfoAsync($"Delivered chat message {message.Id} to {session.Peer.DisplayName}.", cancellationToken);
        }
        else
        {
            message.Status = ChatMessageStatus.Failed;
            message.LastError = receipt.FailureReason ?? "delivery_failed";
            await _loggerService.LogWarningAsync(
                $"Delivery failed for chat message {message.Id}: {message.LastError}.",
                cancellationToken);
        }

        await PersistMessagesAsync(cancellationToken);
    }

    private async Task AddMessageAsync(ChatMessage message, CancellationToken cancellationToken)
    {
        await _messagesGate.WaitAsync(cancellationToken);
        try
        {
            if (_messages.Any(existing => string.Equals(existing.Id, message.Id, StringComparison.OrdinalIgnoreCase)))
            {
                return;
            }

            _messages.Add(message);
            _messages.Sort((left, right) => left.TimestampUtc.CompareTo(right.TimestampUtc));
        }
        finally
        {
            _messagesGate.Release();
        }

        await PersistMessagesAsync(cancellationToken);
        MessageAdded?.Invoke(message);
    }

    private async Task PersistMessagesAsync(CancellationToken cancellationToken)
    {
        List<ChatMessage> snapshot;

        await _messagesGate.WaitAsync(cancellationToken);
        try
        {
            snapshot = _messages
                .OrderBy(message => message.TimestampUtc)
                .ToList();
        }
        finally
        {
            _messagesGate.Release();
        }

        await _chatRepository.SaveAsync(snapshot, cancellationToken);
    }
}
