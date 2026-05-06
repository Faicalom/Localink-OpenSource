using LocalBridge.Core.Discovery;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Services;

public sealed class BridgeDiscoveryService : IDiscoveryService
{
    private readonly IDiscoveryService _lanDiscoveryService;
    private readonly IDiscoveryService _bluetoothDiscoveryService;

    public BridgeDiscoveryService(
        IDiscoveryService lanDiscoveryService,
        IDiscoveryService bluetoothDiscoveryService)
    {
        _lanDiscoveryService = lanDiscoveryService;
        _bluetoothDiscoveryService = bluetoothDiscoveryService;
    }

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        await _lanDiscoveryService.StartAsync(cancellationToken);
        await _bluetoothDiscoveryService.StartAsync(cancellationToken);
    }

    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        await _bluetoothDiscoveryService.StopAsync(cancellationToken);
        await _lanDiscoveryService.StopAsync(cancellationToken);
    }

    public async Task RefreshAsync(CancellationToken cancellationToken = default)
    {
        await _lanDiscoveryService.RefreshAsync(cancellationToken);
        await _bluetoothDiscoveryService.RefreshAsync(cancellationToken);
    }

    public async Task<IReadOnlyList<DevicePeer>> GetKnownPeersAsync(CancellationToken cancellationToken = default)
    {
        var lanPeers = await _lanDiscoveryService.GetKnownPeersAsync(cancellationToken);
        var bluetoothPeers = await _bluetoothDiscoveryService.GetKnownPeersAsync(cancellationToken);

        return lanPeers
            .Concat(bluetoothPeers)
            .OrderByDescending(peer => peer.IsOnline)
            .ThenBy(peer => peer.TransportMode)
            .ThenBy(peer => peer.DisplayName, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    public async Task<LocalDeviceProfile> GetLocalDeviceAsync(CancellationToken cancellationToken = default)
    {
        var lanDevice = await _lanDiscoveryService.GetLocalDeviceAsync(cancellationToken);
        lanDevice.SupportedModes = [DiscoverySupportedModes.LocalLan, DiscoverySupportedModes.BluetoothFallback];
        return lanDevice;
    }
}
