using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Services;

public interface ITrustedDevicesService
{
    Task<IReadOnlyList<DevicePeer>> GetTrustedDevicesAsync(CancellationToken cancellationToken = default);

    Task TrustDeviceAsync(DevicePeer peer, CancellationToken cancellationToken = default);

    Task UntrustDeviceAsync(string peerId, CancellationToken cancellationToken = default);
}
