using Localink.Desktop.Models;

namespace Localink.Desktop.Services;

public interface IDiscoveryService
{
    Task StartAsync(CancellationToken cancellationToken = default);

    Task StopAsync(CancellationToken cancellationToken = default);

    Task RefreshAsync(CancellationToken cancellationToken = default);

    Task<IReadOnlyList<DevicePeer>> GetKnownPeersAsync(CancellationToken cancellationToken = default);

    Task<LocalDeviceProfile> GetLocalDeviceAsync(CancellationToken cancellationToken = default);
}

