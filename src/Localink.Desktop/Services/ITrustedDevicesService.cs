using Localink.Desktop.Models;

namespace Localink.Desktop.Services;

public interface ITrustedDevicesService
{
    Task<IReadOnlyList<DevicePeer>> GetTrustedDevicesAsync(CancellationToken cancellationToken = default);

    Task TrustDeviceAsync(DevicePeer peer, CancellationToken cancellationToken = default);

    Task UntrustDeviceAsync(string peerId, CancellationToken cancellationToken = default);
}

