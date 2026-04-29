using System.Text.Json.Serialization;
using System.Windows;
using LocalBridge.Desktop.Core.Mvvm;

namespace LocalBridge.Desktop.Models;

public sealed class ChatMessage : ObservableObject
{
    private string _id = Guid.NewGuid().ToString("N");
    private string _senderId = string.Empty;
    private string _receiverId = string.Empty;
    private string _senderName = string.Empty;
    private string _text = string.Empty;
    private DateTimeOffset _timestampUtc = DateTimeOffset.UtcNow;
    private ChatMessageStatus _status = ChatMessageStatus.Sent;
    private bool _isOutgoing;
    private int _deliveryAttempts;
    private string _lastError = string.Empty;

    public string Id
    {
        get => _id;
        set => SetProperty(ref _id, value);
    }

    public string SenderId
    {
        get => _senderId;
        set => SetProperty(ref _senderId, value);
    }

    public string ReceiverId
    {
        get => _receiverId;
        set => SetProperty(ref _receiverId, value);
    }

    public string SenderName
    {
        get => _senderName;
        set
        {
            if (SetProperty(ref _senderName, value))
            {
                RaisePropertyChanged(nameof(SenderLabel));
            }
        }
    }

    public string Text
    {
        get => _text;
        set => SetProperty(ref _text, value);
    }

    public DateTimeOffset TimestampUtc
    {
        get => _timestampUtc;
        set
        {
            if (SetProperty(ref _timestampUtc, value))
            {
                RaisePropertyChanged(nameof(TimeLabel));
                RaisePropertyChanged(nameof(TimestampLabel));
            }
        }
    }

    [JsonConverter(typeof(JsonStringEnumConverter))]
    public ChatMessageStatus Status
    {
        get => _status;
        set
        {
            if (SetProperty(ref _status, value))
            {
                RaisePropertyChanged(nameof(StatusLabel));
                RaisePropertyChanged(nameof(StatusGlyph));
                RaisePropertyChanged(nameof(IsRetryVisible));
            }
        }
    }

    public bool IsOutgoing
    {
        get => _isOutgoing;
        set
        {
            if (SetProperty(ref _isOutgoing, value))
            {
                RaisePropertyChanged(nameof(BubbleAlignment));
                RaisePropertyChanged(nameof(SenderLabel));
                RaisePropertyChanged(nameof(IsRetryVisible));
            }
        }
    }

    public int DeliveryAttempts
    {
        get => _deliveryAttempts;
        set => SetProperty(ref _deliveryAttempts, value);
    }

    public string LastError
    {
        get => _lastError;
        set => SetProperty(ref _lastError, value);
    }

    [JsonIgnore]
    public string TimeLabel => TimestampUtc.ToLocalTime().ToString("HH:mm");

    [JsonIgnore]
    public string TimestampLabel => TimestampUtc.ToLocalTime().ToString("MMM dd, HH:mm");

    [JsonIgnore]
    public HorizontalAlignment BubbleAlignment => IsOutgoing ? HorizontalAlignment.Right : HorizontalAlignment.Left;

    [JsonIgnore]
    public string SenderLabel => IsOutgoing ? "You" : SenderName;

    [JsonIgnore]
    public bool IsRetryVisible => IsOutgoing && Status == ChatMessageStatus.Failed;

    [JsonIgnore]
    public string StatusLabel => Status switch
    {
        ChatMessageStatus.Sending => "Sending",
        ChatMessageStatus.Sent => "Queued",
        ChatMessageStatus.Delivered => "Delivered",
        ChatMessageStatus.Failed => "Failed",
        _ => "Unknown"
    };

    [JsonIgnore]
    public string StatusGlyph => Status switch
    {
        ChatMessageStatus.Sending => "…",
        ChatMessageStatus.Sent => "•",
        ChatMessageStatus.Delivered => "✓",
        ChatMessageStatus.Failed => "!",
        _ => "?"
    };
}
