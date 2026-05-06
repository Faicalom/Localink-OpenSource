using Localink.Desktop.Models;

namespace Localink.Desktop.Repositories;

public interface ITrustedDevicesRepository
{
    Task<IReadOnlyList<DevicePeer>> LoadAsync(CancellationToken cancellationToken = default);

    Task SaveAsync(IReadOnlyList<DevicePeer> peers, CancellationToken cancellationToken = default);
}
