using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Services;

public interface IChatService
{
    event Action<ChatMessage>? MessageAdded;

    Task InitializeAsync(CancellationToken cancellationToken = default);

    Task ShutdownAsync(CancellationToken cancellationToken = default);

    Task<IReadOnlyList<ChatMessage>> GetRecentMessagesAsync(CancellationToken cancellationToken = default);

    Task<bool> SendMessageAsync(string text, CancellationToken cancellationToken = default);

    Task RetryMessageAsync(ChatMessage message, CancellationToken cancellationToken = default);

    Task<int> ClearHistoryAsync(CancellationToken cancellationToken = default);
}
