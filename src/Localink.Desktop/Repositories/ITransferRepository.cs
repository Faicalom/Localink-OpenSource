using Localink.Desktop.Models;

namespace Localink.Desktop.Repositories;

public interface ITransferRepository
{
    Task<IReadOnlyList<TransferItem>> LoadAsync(CancellationToken cancellationToken = default);

    Task SaveAsync(IReadOnlyList<TransferItem> transfers, CancellationToken cancellationToken = default);
}

