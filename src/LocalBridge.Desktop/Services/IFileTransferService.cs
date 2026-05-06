using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Services;

public interface IFileTransferService
{
    event Action<TransferItem>? TransferAdded;

    Task InitializeAsync(CancellationToken cancellationToken = default);

    Task ShutdownAsync(CancellationToken cancellationToken = default);

    Task<IReadOnlyList<TransferItem>> GetTransfersAsync(CancellationToken cancellationToken = default);

    Task QueueFilesAsync(IEnumerable<string> filePaths, CancellationToken cancellationToken = default);

    Task PauseTransferAsync(TransferItem transferItem, CancellationToken cancellationToken = default);

    Task ResumeTransferAsync(TransferItem transferItem, CancellationToken cancellationToken = default);

    Task CancelTransferAsync(TransferItem transferItem, CancellationToken cancellationToken = default);

    Task OpenTransferAsync(TransferItem transferItem, CancellationToken cancellationToken = default);

    Task<int> ClearHistoryAsync(CancellationToken cancellationToken = default);
}
