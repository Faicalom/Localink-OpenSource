using System.Collections.ObjectModel;
using System.Windows;
using LocalBridge.Desktop.Core.Mvvm;
using LocalBridge.Desktop.Models;
using LocalBridge.Desktop.Services;

namespace LocalBridge.Desktop.Features.Chat;

public sealed class ChatFeatureViewModel : ObservableObject
{
    private readonly IChatService _chatService;
    private readonly IConnectionService _connectionService;
    private string _composeText = string.Empty;
    private string _conversationTitle = "No active conversation";
    private string _conversationStatus = "Connect to a local peer to start real-time messaging.";
    private bool _canSendMessages;
    private bool _isLoaded;

    public ChatFeatureViewModel(
        IChatService chatService,
        IConnectionService connectionService)
    {
        _chatService = chatService;
        _connectionService = connectionService;

        Messages = [];
        SendMessageCommand = new AsyncRelayCommand(_ => SendMessageAsync(), _ => CanSendMessages && !string.IsNullOrWhiteSpace(ComposeText));
        RetryMessageCommand = new AsyncRelayCommand(
            parameter => RetryMessageAsync(parameter as ChatMessage),
            parameter => parameter is ChatMessage);
    }

    public ObservableCollection<ChatMessage> Messages { get; }

    public AsyncRelayCommand SendMessageCommand { get; }

    public AsyncRelayCommand RetryMessageCommand { get; }

    public string ComposeText
    {
        get => _composeText;
        set
        {
            if (SetProperty(ref _composeText, value))
            {
                SendMessageCommand.RaiseCanExecuteChanged();
            }
        }
    }

    public string ConversationTitle
    {
        get => _conversationTitle;
        private set => SetProperty(ref _conversationTitle, value);
    }

    public string ConversationStatus
    {
        get => _conversationStatus;
        private set => SetProperty(ref _conversationStatus, value);
    }

    public bool CanSendMessages
    {
        get => _canSendMessages;
        private set
        {
            if (SetProperty(ref _canSendMessages, value))
            {
                SendMessageCommand.RaiseCanExecuteChanged();
            }
        }
    }

    public async Task LoadAsync()
    {
        if (!_isLoaded)
        {
            await _chatService.InitializeAsync();
            _chatService.MessageAdded += HandleMessageAdded;
            _connectionService.SessionChanged += HandleSessionChanged;
            _isLoaded = true;
        }

        var messages = await _chatService.GetRecentMessagesAsync();

        await DispatchAsync(() =>
        {
            Messages.Clear();
            foreach (var message in messages)
            {
                Messages.Add(message);
            }
        });

        await ApplySessionAsync(await _connectionService.GetActiveSessionAsync());
    }

    public async Task ShutdownAsync()
    {
        if (!_isLoaded)
        {
            return;
        }

        _chatService.MessageAdded -= HandleMessageAdded;
        _connectionService.SessionChanged -= HandleSessionChanged;
        await _chatService.ShutdownAsync();
        _isLoaded = false;
    }

    private async Task SendMessageAsync()
    {
        var text = ComposeText.Trim();
        if (string.IsNullOrWhiteSpace(text))
        {
            return;
        }

        var created = await _chatService.SendMessageAsync(text);
        if (created)
        {
            ComposeText = string.Empty;
        }
    }

    private Task RetryMessageAsync(ChatMessage? message)
    {
        return message is null
            ? Task.CompletedTask
            : _chatService.RetryMessageAsync(message);
    }

    public async Task<int> ClearHistoryAsync()
    {
        var removedCount = await _chatService.ClearHistoryAsync();

        await DispatchAsync(() =>
        {
            Messages.Clear();
        });

        return removedCount;
    }

    private void HandleMessageAdded(ChatMessage message)
    {
        _ = DispatchAsync(() =>
        {
            if (Messages.Any(existing => string.Equals(existing.Id, message.Id, StringComparison.OrdinalIgnoreCase)))
            {
                return;
            }

            Messages.Add(message);
        });
    }

    private void HandleSessionChanged(ConnectionSessionSnapshot? session)
    {
        _ = ApplySessionAsync(session);
    }

    private Task ApplySessionAsync(ConnectionSessionSnapshot? session)
    {
        return DispatchAsync(() =>
        {
            if (session is null || !session.IsConnected)
            {
                CanSendMessages = false;
                ConversationTitle = "No active conversation";
                ConversationStatus = "Connect to a nearby LocalBridge peer to send and receive messages.";
                return;
            }

            CanSendMessages = true;
            ConversationTitle = session.Peer.DisplayName;
            ConversationStatus = session.TransportMode == AppConnectionMode.BluetoothFallback
                ? $"Connected over Bluetooth RFCOMM with {session.Peer.EndpointLabel}. Bluetooth is slower and recommended mainly for messaging."
                : $"Connected over local Wi-Fi / Hotspot with {session.Peer.EndpointLabel}.";
        });
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
}
