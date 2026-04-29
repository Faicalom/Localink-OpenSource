using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Repositories;

public interface IChatRepository
{
    Task<IReadOnlyList<ChatMessage>> LoadAsync(CancellationToken cancellationToken = default);

    Task SaveAsync(IReadOnlyList<ChatMessage> messages, CancellationToken cancellationToken = default);
}
