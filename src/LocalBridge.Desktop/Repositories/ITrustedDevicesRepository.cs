using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Repositories;

public interface ITrustedDevicesRepository
{
    Task<IReadOnlyList<DevicePeer>> LoadAsync(CancellationToken cancellationToken = default);

    Task SaveAsync(IReadOnlyList<DevicePeer> peers, CancellationToken cancellationToken = default);
}
