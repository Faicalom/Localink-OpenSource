using InTheHand.Net.Sockets;
using LocalBridge.Core.Discovery;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Models;
using LocalBridge.Desktop.Repositories;
using Windows.Devices.Bluetooth;
using Windows.Devices.Enumeration;

namespace LocalBridge.Desktop.Services;

public sealed class BluetoothDiscoveryService : IDiscoveryService
{
    private readonly ILoggerService _loggerService;
    private readonly ILocalDeviceProfileRepository _localDeviceProfileRepository;
    private readonly SemaphoreSlim _lifecycleGate = new(1, 1);
    private readonly object _peersGate = new();
    private readonly Dictionary<string, DevicePeer> _peers = new(StringComparer.OrdinalIgnoreCase);

    private CancellationTokenSource? _cts;
    private Task? _refreshTask;
    private LocalDeviceProfile? _persistedLocalDevice;
    private bool _isStarted;

    public BluetoothDiscoveryService(
        ILoggerService loggerService,
        ILocalDeviceProfileRepository localDeviceProfileRepository)
    {
        _loggerService = loggerService;
        _localDeviceProfileRepository = localDeviceProfileRepository;
    }

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        await _lifecycleGate.WaitAsync(cancellationToken);

        try
        {
            if (_isStarted)
            {
                return;
            }

            _persistedLocalDevice = await _localDeviceProfileRepository.LoadOrCreateAsync(cancellationToken);
            _cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            _refreshTask = Task.Run(() => RefreshLoopAsync(_cts.Token), _cts.Token);
            _isStarted = true;

            await _loggerService.LogInfoAsync(
                $"Bluetooth discovery started for {_persistedLocalDevice.DeviceName}. Nearby scans will refresh every {AppConstants.BluetoothDiscoveryRefreshSeconds} seconds.",
                cancellationToken);
        }
        finally
        {
            _lifecycleGate.Release();
        }
    }

    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        await _lifecycleGate.WaitAsync(cancellationToken);

        try
        {
            if (!_isStarted)
            {
                return;
            }

            _cts?.Cancel();
            if (_refreshTask is not null)
            {
                await SafeAwaitAsync(_refreshTask);
            }

            _cts?.Dispose();
            _cts = null;
            _refreshTask = null;
            _isStarted = false;

            lock (_peersGate)
            {
                _peers.Clear();
            }

            await _loggerService.LogInfoAsync("Bluetooth discovery stopped and the nearby device cache was cleared.", cancellationToken);
        }
        finally
        {
            _lifecycleGate.Release();
        }
    }

    public async Task RefreshAsync(CancellationToken cancellationToken = default)
    {
        if (!_isStarted)
        {
            await StartAsync(cancellationToken);
        }

        await RefreshKnownPeersAsync(cancellationToken);
    }

    public Task<IReadOnlyList<DevicePeer>> GetKnownPeersAsync(CancellationToken cancellationToken = default)
    {
        lock (_peersGate)
        {
            IReadOnlyList<DevicePeer> peers = _peers.Values
                .OrderByDescending(peer => peer.IsOnline)
                .ThenBy(peer => peer.DisplayName, StringComparer.OrdinalIgnoreCase)
                .Select(ClonePeer)
                .ToList();

            return Task.FromResult(peers);
        }
    }

    public async Task<LocalDeviceProfile> GetLocalDeviceAsync(CancellationToken cancellationToken = default)
    {
        _persistedLocalDevice ??= await _localDeviceProfileRepository.LoadOrCreateAsync(cancellationToken);
        return new LocalDeviceProfile
        {
            DeviceId = _persistedLocalDevice.DeviceId,
            DeviceName = _persistedLocalDevice.DeviceName,
            Platform = AppConstants.DesktopPlatformName,
            AppVersion = _persistedLocalDevice.AppVersion,
            SupportedModes = [DiscoverySupportedModes.LocalLan, DiscoverySupportedModes.BluetoothFallback],
            LocalIpAddresses = _persistedLocalDevice.LocalIpAddresses,
            ApiPort = AppConstants.DefaultApiPort,
            DiscoveryPort = AppConstants.DefaultDiscoveryPort,
            CreatedAtUtc = _persistedLocalDevice.CreatedAtUtc
        };
    }

    private async Task RefreshLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await RefreshKnownPeersAsync(cancellationToken);
                await Task.Delay(TimeSpan.FromSeconds(AppConstants.BluetoothDiscoveryRefreshSeconds), cancellationToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                await _loggerService.LogWarningAsync($"Bluetooth discovery refresh failed: {ex.Message}", cancellationToken);
                await Task.Delay(TimeSpan.FromSeconds(4), cancellationToken);
            }
        }
    }

    private async Task RefreshKnownPeersAsync(CancellationToken cancellationToken)
    {
        IReadOnlyCollection<BluetoothDeviceInfo> discoveredDevices = Array.Empty<BluetoothDeviceInfo>();
        var scanFailed = false;

        try
        {
            discoveredDevices = await Task.Run(() =>
            {
                using var client = new BluetoothClient();
                return client.DiscoverDevices(255);
            }, cancellationToken);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            scanFailed = true;
            await _loggerService.LogWarningAsync(
                $"Bluetooth scan did not complete. This usually means the radio is unavailable, disabled, or blocked by the adapter driver. {ex.Message}",
                cancellationToken);
        }

        var now = DateTimeOffset.UtcNow;
        var refreshedPeerIds = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        lock (_peersGate)
        {
            foreach (var device in discoveredDevices)
            {
                var bluetoothAddress = device.DeviceAddress.ToString();
                UpsertPeer(
                    CreateBluetoothPeer(
                        bluetoothAddress,
                        string.IsNullOrWhiteSpace(device.DeviceName) ? bluetoothAddress : device.DeviceName,
                        isTrusted: device.Authenticated,
                        isOnline: true,
                        now),
                    refreshedPeerIds);
            }
        }

        var pairedPeers = await LoadPairedWindowsPeersAsync(now, cancellationToken);

        lock (_peersGate)
        {
            foreach (var peer in pairedPeers)
            {
                UpsertPeer(peer, refreshedPeerIds);
            }

            var staleThreshold = now.AddSeconds(-AppConstants.BluetoothPeerStaleTimeoutSeconds);
            foreach (var stalePeer in _peers.Values
                .Where(peer => !refreshedPeerIds.Contains(peer.Id) && peer.LastSeenAtUtc < staleThreshold)
                .Select(peer => peer.Id)
                .ToList())
            {
                _peers.Remove(stalePeer);
            }
        }

        await _loggerService.LogInfoAsync(
            scanFailed
                ? $"Bluetooth discovery used the paired-device fallback. {_peers.Count} Bluetooth peer(s) are currently visible."
                : $"Bluetooth discovery scan finished. {_peers.Count} Bluetooth peer(s) are currently visible.",
            cancellationToken);
    }

    private async Task<IReadOnlyList<DevicePeer>> LoadPairedWindowsPeersAsync(DateTimeOffset now, CancellationToken cancellationToken)
    {
        try
        {
            var selector = BluetoothDevice.GetDeviceSelectorFromPairingState(true);
            var pairedDevices = await DeviceInformation.FindAllAsync(selector).AsTask(cancellationToken);
            var peers = new List<DevicePeer>(pairedDevices.Count);

            foreach (var device in pairedDevices)
            {
                cancellationToken.ThrowIfCancellationRequested();

                try
                {
                    var bluetoothDevice = await BluetoothDevice.FromIdAsync(device.Id);
                    if (bluetoothDevice is null || bluetoothDevice.BluetoothAddress == 0)
                    {
                        continue;
                    }

                    var bluetoothAddress = bluetoothDevice.BluetoothAddress.ToString("X12");
                    var isPresent = TryGetBooleanProperty(device, "System.Devices.Aep.IsPresent");
                    peers.Add(CreateBluetoothPeer(
                        bluetoothAddress,
                        string.IsNullOrWhiteSpace(device.Name) ? bluetoothAddress : device.Name,
                        isTrusted: device.Pairing.IsPaired,
                        isOnline: isPresent,
                        now));
                }
                catch
                {
                    // Some Windows Bluetooth device entries are stale or malformed.
                    // Skip them so one bad entry does not hide all valid paired devices.
                }
            }

            return peers;
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            await _loggerService.LogWarningAsync(
                $"Bluetooth paired-device fallback failed: {ex.Message}",
                cancellationToken);
            return Array.Empty<DevicePeer>();
        }
    }

    private void UpsertPeer(DevicePeer peer, ISet<string> refreshedPeerIds)
    {
        refreshedPeerIds.Add(peer.Id);

        if (_peers.TryGetValue(peer.Id, out var existing))
        {
            existing.DisplayName = string.IsNullOrWhiteSpace(peer.DisplayName) ? existing.DisplayName : peer.DisplayName;
            existing.Platform = peer.Platform;
            existing.BluetoothAddress = string.IsNullOrWhiteSpace(peer.BluetoothAddress) ? existing.BluetoothAddress : peer.BluetoothAddress;
            existing.AppVersion = peer.AppVersion;
            existing.SupportedModes = peer.SupportedModes;
            existing.TransportMode = peer.TransportMode;
            existing.IsTrusted = existing.IsTrusted || peer.IsTrusted;
            existing.IsOnline = existing.IsOnline || peer.IsOnline;
            existing.LastSeenAtUtc = peer.LastSeenAtUtc;
            existing.HasResolvedIdentity = existing.HasResolvedIdentity || peer.HasResolvedIdentity;
            return;
        }

        _peers[peer.Id] = peer;
    }

    private static DevicePeer CreateBluetoothPeer(
        string bluetoothAddress,
        string displayName,
        bool isTrusted,
        bool isOnline,
        DateTimeOffset now)
    {
        return new DevicePeer
        {
            Id = BuildTemporaryPeerId(bluetoothAddress),
            DisplayName = displayName,
            Platform = "Bluetooth device",
            IpAddress = string.Empty,
            Port = 0,
            BluetoothAddress = bluetoothAddress,
            AppVersion = isTrusted ? "paired" : "unpaired",
            SupportedModes = [DiscoverySupportedModes.BluetoothFallback],
            TransportMode = AppConnectionMode.BluetoothFallback,
            IsTrusted = isTrusted,
            IsOnline = isOnline,
            FirstSeenAtUtc = now,
            LastSeenAtUtc = now,
            HasResolvedIdentity = false
        };
    }

    private static bool TryGetBooleanProperty(DeviceInformation device, string propertyName)
    {
        if (device.Properties.TryGetValue(propertyName, out var rawValue) && rawValue is bool booleanValue)
        {
            return booleanValue;
        }

        return false;
    }

    private static string BuildTemporaryPeerId(string bluetoothAddress)
    {
        var normalized = bluetoothAddress
            .Replace(":", string.Empty, StringComparison.Ordinal)
            .Replace("-", string.Empty, StringComparison.Ordinal);
        return $"bt-{normalized}";
    }

    private static DevicePeer ClonePeer(DevicePeer peer)
    {
        return new DevicePeer
        {
            Id = peer.Id,
            DisplayName = peer.DisplayName,
            Platform = peer.Platform,
            IpAddress = peer.IpAddress,
            Port = peer.Port,
            BluetoothAddress = peer.BluetoothAddress,
            AppVersion = peer.AppVersion,
            SupportedModes = peer.SupportedModes.ToArray(),
            TransportMode = peer.TransportMode,
            IsTrusted = peer.IsTrusted,
            IsOnline = peer.IsOnline,
            FirstSeenAtUtc = peer.FirstSeenAtUtc,
            LastSeenAtUtc = peer.LastSeenAtUtc,
            HasResolvedIdentity = peer.HasResolvedIdentity
        };
    }

    private static Task SafeAwaitAsync(Task task)
    {
        return task.ContinueWith(
            _ => { },
            CancellationToken.None,
            TaskContinuationOptions.ExecuteSynchronously,
            TaskScheduler.Default);
    }
}
