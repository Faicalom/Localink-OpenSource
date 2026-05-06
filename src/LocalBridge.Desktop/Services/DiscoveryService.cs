using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using LocalBridge.Core;
using LocalBridge.Core.Discovery;
using LocalBridge.Core.Protocol;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Models;
using LocalBridge.Desktop.Repositories;

namespace LocalBridge.Desktop.Services;

public sealed class DiscoveryService : IDiscoveryService
{
    private readonly ILoggerService _loggerService;
    private readonly ILocalDeviceProfileRepository _localDeviceProfileRepository;
    private readonly SemaphoreSlim _lifecycleGate = new(1, 1);
    private readonly object _peersGate = new();
    private readonly Dictionary<string, DevicePeer> _peers = new(StringComparer.OrdinalIgnoreCase);

    private CancellationTokenSource? _cts;
    private Task? _listenTask;
    private Task? _announcementTask;
    private Task? _probeTask;
    private Task? _cleanupTask;
    private UdpClient? _listener;
    private UdpClient? _sender;
    private LocalDeviceProfile? _persistedLocalDevice;
    private bool _isStarted;

    public DiscoveryService(
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

            _listener = CreateListener();
            _sender = CreateSender();
            _cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);

            _listenTask = ListenLoopAsync(_cts.Token);
            _announcementTask = AnnouncementLoopAsync(_cts.Token);
            _probeTask = ProbeLoopAsync(_cts.Token);
            _cleanupTask = CleanupLoopAsync(_cts.Token);

            _isStarted = true;

            var localDevice = CreateRuntimeLocalDevice();
            var endpointSummary = localDevice.LocalIpAddresses.Length == 0
                ? "no active IPv4 address detected yet"
                : string.Join(", ", localDevice.LocalIpAddresses.Select(address => $"{address}:{localDevice.ApiPort}"));
            await _loggerService.LogInfoAsync(
                $"[DISCOVERY] Started for {localDevice.DeviceName} ({localDevice.DeviceId}) on UDP {localDevice.DiscoveryPort}. Local endpoints: {endpointSummary}.",
                cancellationToken);

            await SendAnnouncementAsync(cancellationToken);
            await SendProbeAsync(cancellationToken, logReason: "initial scan");
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

            if (_listenTask is not null)
            {
                await SafeAwaitAsync(_listenTask);
            }

            if (_announcementTask is not null)
            {
                await SafeAwaitAsync(_announcementTask);
            }

            if (_probeTask is not null)
            {
                await SafeAwaitAsync(_probeTask);
            }

            if (_cleanupTask is not null)
            {
                await SafeAwaitAsync(_cleanupTask);
            }

            _listener?.Dispose();
            _sender?.Dispose();
            _cts?.Dispose();

            _listener = null;
            _sender = null;
            _cts = null;
            _listenTask = null;
            _announcementTask = null;
            _probeTask = null;
            _cleanupTask = null;
            _isStarted = false;

            lock (_peersGate)
            {
                _peers.Clear();
            }

            await _loggerService.LogInfoAsync("[DISCOVERY] Stopped and peer cache cleared.", cancellationToken);
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
            return;
        }

        await SendAnnouncementAsync(cancellationToken);
        await SendProbeAsync(cancellationToken, logReason: "manual refresh");
    }

    public Task<IReadOnlyList<DevicePeer>> GetKnownPeersAsync(CancellationToken cancellationToken = default)
    {
        lock (_peersGate)
        {
            IReadOnlyList<DevicePeer> peers = _peers.Values
                .OrderByDescending(peer => peer.IsOnline)
                .ThenByDescending(peer => peer.LastSeenAtUtc)
                .ThenBy(peer => peer.DisplayName, StringComparer.OrdinalIgnoreCase)
                .Select(ClonePeer)
                .ToList();

            return Task.FromResult(peers);
        }
    }

    public async Task<LocalDeviceProfile> GetLocalDeviceAsync(CancellationToken cancellationToken = default)
    {
        _persistedLocalDevice ??= await _localDeviceProfileRepository.LoadOrCreateAsync(cancellationToken);
        return CreateRuntimeLocalDevice();
    }

    private static UdpClient CreateListener()
    {
        var listener = new UdpClient(AddressFamily.InterNetwork)
        {
            EnableBroadcast = true
        };

        listener.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        listener.Client.Bind(new IPEndPoint(IPAddress.Any, AppConstants.DefaultDiscoveryPort));
        return listener;
    }

    private static UdpClient CreateSender()
    {
        return new UdpClient(AddressFamily.InterNetwork)
        {
            EnableBroadcast = true
        };
    }

    private static UdpClient CreateBoundSender(string localAddress)
    {
        var sender = new UdpClient(AddressFamily.InterNetwork)
        {
            EnableBroadcast = true
        };

        sender.Client.Bind(new IPEndPoint(IPAddress.Parse(localAddress), 0));
        return sender;
    }

    private async Task ListenLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                var result = await _listener!.ReceiveAsync(cancellationToken);
                await HandlePacketAsync(result, cancellationToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (Exception ex)
            {
                await _loggerService.LogErrorAsync($"[DISCOVERY] Listener error: {ex.Message}", cancellationToken);
                await Task.Delay(TimeSpan.FromSeconds(1), cancellationToken);
            }
        }
    }

    private async Task AnnouncementLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(AppConstants.DiscoveryAnnouncementIntervalSeconds), cancellationToken);
                await SendAnnouncementAsync(cancellationToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                await _loggerService.LogErrorAsync($"[DISCOVERY] Announcement error: {ex.Message}", cancellationToken);
            }
        }
    }

    private async Task ProbeLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(AppConstants.DiscoveryProbeIntervalSeconds), cancellationToken);
                await SendProbeAsync(cancellationToken, logReason: null);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                await _loggerService.LogErrorAsync($"[DISCOVERY] Probe error: {ex.Message}", cancellationToken);
            }
        }
    }

    private async Task CleanupLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(AppConstants.DiscoveryCleanupIntervalSeconds), cancellationToken);
                CleanupStalePeers();
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                await _loggerService.LogErrorAsync($"[DISCOVERY] Cleanup error: {ex.Message}", cancellationToken);
            }
        }
    }

    private async Task HandlePacketAsync(UdpReceiveResult result, CancellationToken cancellationToken)
    {
        ProtocolEnvelope<DiscoveryPacket>? envelope;

        try
        {
            var json = Encoding.UTF8.GetString(result.Buffer);
            envelope = JsonSerializer.Deserialize<ProtocolEnvelope<DiscoveryPacket>>(json, JsonDefaults.Options);
        }
        catch (JsonException ex)
        {
            await _loggerService.LogWarningAsync($"[DISCOVERY] Ignored malformed packet: {ex.Message}", cancellationToken);
            return;
        }

        var validation = ProtocolEnvelopeValidator.Validate(
            envelope,
            expectedPacketTypes:
            [
                DiscoveryPacketTypes.Probe,
                DiscoveryPacketTypes.Reply,
                DiscoveryPacketTypes.Announcement
            ]);

        if (!validation.IsValid)
        {
            if (!string.Equals(validation.ErrorCode, ProtocolErrorCodes.InvalidRequest, StringComparison.OrdinalIgnoreCase) ||
                envelope is not null)
            {
                await _loggerService.LogWarningAsync(
                    $"[DISCOVERY] Ignored packet from {result.RemoteEndPoint.Address}: {validation.ErrorMessage}",
                    cancellationToken);
            }

            return;
        }

        var packet = envelope!.Payload!;
        if (string.IsNullOrWhiteSpace(packet.DeviceId))
        {
            await _loggerService.LogWarningAsync(
                $"[DISCOVERY] Ignored packet from {result.RemoteEndPoint.Address} because the device id was missing.",
                cancellationToken);
            return;
        }

        var localDevice = await GetLocalDeviceAsync(cancellationToken);
        if (string.Equals(packet.DeviceId, localDevice.DeviceId, StringComparison.OrdinalIgnoreCase))
        {
            return;
        }

        await _loggerService.LogInfoAsync(
            $"[DISCOVERY] Received {envelope.Meta.PacketType} from {result.RemoteEndPoint.Address}.",
            cancellationToken);
        UpsertPeer(packet, result.RemoteEndPoint);

        if (string.Equals(envelope.Meta.PacketType, DiscoveryPacketTypes.Probe, StringComparison.OrdinalIgnoreCase))
        {
            await SendDirectReplyAsync(result.RemoteEndPoint.Address, cancellationToken);
        }
    }

    private void UpsertPeer(DiscoveryPacket packet, IPEndPoint remoteEndPoint)
    {
        var now = DateTimeOffset.UtcNow;
        var resolvedIp = ResolvePeerIp(packet.LocalIp, remoteEndPoint.Address);

        DevicePeer peer;
        bool isNewPeer;
        bool endpointChanged;
        bool preservedPreferredEndpoint = false;

        lock (_peersGate)
        {
            isNewPeer = !_peers.TryGetValue(packet.DeviceId, out peer!);

            if (isNewPeer)
            {
                peer = new DevicePeer
                {
                    Id = packet.DeviceId,
                    DisplayName = packet.DeviceName,
                    Platform = packet.Platform,
                    IpAddress = resolvedIp,
                    Port = packet.ApiPort,
                    BluetoothAddress = string.Empty,
                    AppVersion = packet.AppVersion,
                    SupportedModes = packet.SupportedModes?.ToArray() ?? [],
                    TransportMode = AppConnectionMode.LocalLan,
                    IsTrusted = false,
                    IsOnline = true,
                    FirstSeenAtUtc = now,
                    LastSeenAtUtc = now,
                    HasResolvedIdentity = true
                };

                _peers[peer.Id] = peer;
                endpointChanged = false;
            }
            else
            {
                var currentQuality = CalculatePeerEndpointQuality(peer.IpAddress);
                var candidateQuality = CalculatePeerEndpointQuality(resolvedIp);
                var candidateIsDifferent =
                    !string.Equals(peer.IpAddress, resolvedIp, StringComparison.OrdinalIgnoreCase) ||
                    peer.Port != packet.ApiPort;
                var shouldReplaceEndpoint = !candidateIsDifferent ||
                    candidateQuality >= currentQuality ||
                    string.IsNullOrWhiteSpace(peer.IpAddress) ||
                    string.Equals(peer.IpAddress, "0.0.0.0", StringComparison.OrdinalIgnoreCase);

                endpointChanged = candidateIsDifferent && shouldReplaceEndpoint;
                preservedPreferredEndpoint = candidateIsDifferent && !shouldReplaceEndpoint;

                peer.DisplayName = packet.DeviceName;
                peer.Platform = packet.Platform;
                if (shouldReplaceEndpoint)
                {
                    peer.IpAddress = resolvedIp;
                    peer.Port = packet.ApiPort;
                }
                peer.TransportMode = AppConnectionMode.LocalLan;
                peer.AppVersion = packet.AppVersion;
                peer.SupportedModes = packet.SupportedModes?.ToArray() ?? [];
                peer.IsOnline = true;
                peer.LastSeenAtUtc = now;
                peer.HasResolvedIdentity = true;
            }
        }

        if (isNewPeer)
        {
            _ = _loggerService.LogInfoAsync($"[DISCOVERY] Discovered device {packet.DeviceName} at {resolvedIp}:{packet.ApiPort}.");
        }
        else if (endpointChanged)
        {
            _ = _loggerService.LogInfoAsync($"[DISCOVERY] Updated device endpoint for {packet.DeviceName} to {resolvedIp}:{packet.ApiPort}.");
        }
        else if (preservedPreferredEndpoint)
        {
            _ = _loggerService.LogInfoAsync(
                $"[DISCOVERY] Ignored a lower-priority endpoint {resolvedIp}:{packet.ApiPort} for {packet.DeviceName} and kept {peer.IpAddress}:{peer.Port}.");
        }
    }

    private void CleanupStalePeers()
    {
        var threshold = DateTimeOffset.UtcNow.AddSeconds(-AppConstants.DiscoveryPeerStaleTimeoutSeconds);
        List<DevicePeer> removedPeers = [];

        lock (_peersGate)
        {
            foreach (var peer in _peers.Values.Where(peer => peer.LastSeenAtUtc < threshold).ToList())
            {
                removedPeers.Add(ClonePeer(peer));
                _peers.Remove(peer.Id);
            }
        }

        foreach (var peer in removedPeers)
        {
            _ = _loggerService.LogWarningAsync($"[DISCOVERY] Removed stale device {peer.DisplayName} after discovery timeout.");
        }
    }

    private async Task SendAnnouncementAsync(CancellationToken cancellationToken)
    {
        var packet = CreatePacket();
        await BroadcastAsync(DiscoveryPacketTypes.Announcement, packet, cancellationToken);
    }

    private async Task SendProbeAsync(CancellationToken cancellationToken, string? logReason)
    {
        var packet = CreatePacket();
        await BroadcastAsync(DiscoveryPacketTypes.Probe, packet, cancellationToken);

        if (!string.IsNullOrWhiteSpace(logReason))
        {
            await _loggerService.LogInfoAsync($"[DISCOVERY] Sent probe for {logReason}.", cancellationToken);
        }
    }

    private async Task SendDirectReplyAsync(IPAddress targetAddress, CancellationToken cancellationToken)
    {
        if (_sender is null)
        {
            return;
        }

        var packet = CreatePacket();
        var payload = SerializePacket(DiscoveryPacketTypes.Reply, packet);
        var destination = new IPEndPoint(targetAddress, AppConstants.DefaultDiscoveryPort);
        await _sender.SendAsync(payload, destination, cancellationToken);
    }

    private async Task BroadcastAsync(string packetType, DiscoveryPacket packet, CancellationToken cancellationToken)
    {
        var payload = SerializePacket(packetType, packet);
        var routes = GetBroadcastRoutes();

        if (routes.Count > 0)
        {
            foreach (var route in routes)
            {
                try
                {
                    using var sender = CreateBoundSender(route.Address);
                    await sender.SendAsync(
                        payload,
                        new IPEndPoint(IPAddress.Parse(route.BroadcastAddress!), AppConstants.DefaultDiscoveryPort),
                        cancellationToken);
                }
                catch (Exception ex)
                {
                    await _loggerService.LogWarningAsync(
                        $"[DISCOVERY] Broadcast skipped for {route.Address} -> {route.BroadcastAddress}: {ex.Message}",
                        cancellationToken);
                }
            }

            return;
        }

        if (_sender is null)
        {
            return;
        }

        try
        {
            await _sender.SendAsync(payload, new IPEndPoint(IPAddress.Broadcast, AppConstants.DefaultDiscoveryPort), cancellationToken);
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync(
                $"[DISCOVERY] Global broadcast skipped: {ex.Message}",
                cancellationToken);
        }
    }

    private DiscoveryPacket CreatePacket()
    {
        var local = CreateRuntimeLocalDevice();
        var preferredAddress = local.LocalIpAddresses.FirstOrDefault() ?? "0.0.0.0";

        return new DiscoveryPacket(
            DeviceId: local.DeviceId,
            DeviceName: local.DeviceName,
            Platform: local.Platform,
            LocalIp: preferredAddress,
            ApiPort: local.ApiPort,
            AppVersion: local.AppVersion,
            SupportedModes: local.SupportedModes,
            PairingRequired: true,
            SentAtUtc: DateTimeOffset.UtcNow);
    }

    private LocalDeviceProfile CreateRuntimeLocalDevice()
    {
        _persistedLocalDevice ??= _localDeviceProfileRepository.LoadOrCreateAsync().GetAwaiter().GetResult();
        var localRoutes = GetPreferredVisibleRoutes();

        return new LocalDeviceProfile
        {
            DeviceId = _persistedLocalDevice.DeviceId,
            DeviceName = _persistedLocalDevice.DeviceName,
            Platform = AppConstants.DesktopPlatformName,
            AppVersion = _persistedLocalDevice.AppVersion,
            SupportedModes = [DiscoverySupportedModes.LocalLan, DiscoverySupportedModes.BluetoothFallback],
            LocalIpAddresses = localRoutes
                .Select(route => route.Address)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToArray(),
            LocalNetworkRoutes = localRoutes
                .Select(FormatRouteSummary)
                .ToArray(),
            ApiPort = AppConstants.DefaultApiPort,
            DiscoveryPort = AppConstants.DefaultDiscoveryPort,
            CreatedAtUtc = _persistedLocalDevice.CreatedAtUtc
        };
    }

    private static byte[] SerializePacket(string packetType, DiscoveryPacket packet)
    {
        return Encoding.UTF8.GetBytes(JsonSerializer.Serialize(
            DiscoveryEnvelopeFactory.Create(packetType, packet),
            JsonDefaults.Options));
    }

    private static string ResolvePeerIp(string advertisedIp, IPAddress remoteAddress)
    {
        if (!remoteAddress.Equals(IPAddress.Any) && !remoteAddress.Equals(IPAddress.IPv6Any))
        {
            return NormalizeIpAddress(remoteAddress);
        }

        return NormalizeIpAddress(advertisedIp);
    }

    private static string NormalizeIpAddress(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6)
        {
            address = address.MapToIPv4();
        }

        return address.ToString();
    }

    private static string NormalizeIpAddress(string? address)
    {
        if (string.IsNullOrWhiteSpace(address))
        {
            return "0.0.0.0";
        }

        return IPAddress.TryParse(address, out var parsed)
            ? NormalizeIpAddress(parsed)
            : address.Trim();
    }

    private static IReadOnlyList<LocalRouteDescriptor> GetActiveLocalRoutes()
    {
        return NetworkInterface.GetAllNetworkInterfaces()
            .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up &&
                              adapter.NetworkInterfaceType is not NetworkInterfaceType.Loopback)
            .SelectMany(adapter => adapter.GetIPProperties().UnicastAddresses
                .Where(address => address.Address.AddressFamily == AddressFamily.InterNetwork)
                .Select(address => new LocalRouteDescriptor(
                    AdapterName: string.IsNullOrWhiteSpace(adapter.Name) ? adapter.Description : adapter.Name,
                    AdapterType: adapter.NetworkInterfaceType.ToString(),
                    Address: address.Address.ToString(),
                    Netmask: address.IPv4Mask?.ToString(),
                    BroadcastAddress: address.IPv4Mask is null
                        ? null
                        : ComputeBroadcastAddress(address.Address, address.IPv4Mask).ToString())))
            .GroupBy(route => route.Address, StringComparer.OrdinalIgnoreCase)
            .Select(group => group.First())
            .OrderBy(route => GetRoutePriority(route))
            .ThenBy(route => route.AdapterName, StringComparer.OrdinalIgnoreCase)
            .ThenBy(route => route.Address, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    private static IReadOnlyList<LocalRouteDescriptor> GetBroadcastRoutes()
    {
        var preferredRoutes = GetActiveLocalRoutes()
            .Where(route => !string.IsNullOrWhiteSpace(route.BroadcastAddress))
            .Where(IsPreferredDiscoveryRoute)
            .ToList();

        return preferredRoutes.Count > 0
            ? preferredRoutes
            : GetActiveLocalRoutes()
                .Where(route => !string.IsNullOrWhiteSpace(route.BroadcastAddress))
                .ToList();
    }

    private static IReadOnlyList<LocalRouteDescriptor> GetPreferredVisibleRoutes()
    {
        var activeRoutes = GetActiveLocalRoutes();
        var preferredRoutes = activeRoutes
            .Where(IsPreferredDiscoveryRoute)
            .ToList();

        return preferredRoutes.Count > 0
            ? preferredRoutes
            : activeRoutes;
    }

    private static string FormatRouteSummary(LocalRouteDescriptor route)
    {
        var addressSummary = string.IsNullOrWhiteSpace(route.Netmask)
            ? route.Address
            : $"{route.Address}/{route.Netmask}";
        return $"{route.AdapterName} [{route.AdapterType}] {addressSummary}";
    }

    private static int GetRoutePriority(LocalRouteDescriptor route)
    {
        var adapterName = route.AdapterName.ToLowerInvariant();
        if (IsVirtualOrOverlayRoute(adapterName))
        {
            return 50;
        }

        return route.AdapterType switch
        {
            nameof(NetworkInterfaceType.Wireless80211) => 0,
            nameof(NetworkInterfaceType.Ethernet) => 1,
            nameof(NetworkInterfaceType.GigabitEthernet) => 2,
            nameof(NetworkInterfaceType.FastEthernetFx) => 3,
            nameof(NetworkInterfaceType.FastEthernetT) => 4,
            nameof(NetworkInterfaceType.Ppp) => 5,
            nameof(NetworkInterfaceType.Tunnel) => 6,
            _ => 7
        };
    }

    private static bool IsPreferredDiscoveryRoute(LocalRouteDescriptor route)
    {
        if (string.IsNullOrWhiteSpace(route.BroadcastAddress))
        {
            return false;
        }

        if (route.AdapterType is nameof(NetworkInterfaceType.Ppp) or nameof(NetworkInterfaceType.Tunnel))
        {
            return false;
        }

        return !IsVirtualOrOverlayRoute(route.AdapterName);
    }

    private static bool IsVirtualOrOverlayRoute(string adapterName)
    {
        return adapterName.Contains("pdanet", StringComparison.OrdinalIgnoreCase) ||
               adapterName.Contains("vpn", StringComparison.OrdinalIgnoreCase) ||
               adapterName.Contains("proxy", StringComparison.OrdinalIgnoreCase) ||
               adapterName.Contains("tap", StringComparison.OrdinalIgnoreCase) ||
               adapterName.Contains("tun", StringComparison.OrdinalIgnoreCase) ||
               adapterName.Contains("wireguard", StringComparison.OrdinalIgnoreCase) ||
               adapterName.Contains("zerotier", StringComparison.OrdinalIgnoreCase) ||
               adapterName.Contains("tailscale", StringComparison.OrdinalIgnoreCase) ||
               adapterName.Contains("openvpn", StringComparison.OrdinalIgnoreCase) ||
               adapterName.Contains("virtual", StringComparison.OrdinalIgnoreCase) ||
               adapterName.Contains("vmware", StringComparison.OrdinalIgnoreCase) ||
               adapterName.Contains("hyper-v", StringComparison.OrdinalIgnoreCase) ||
               adapterName.Contains("bridge", StringComparison.OrdinalIgnoreCase);
    }

    private static int CalculatePeerEndpointQuality(string? address)
    {
        if (!IPAddress.TryParse(address, out var remoteAddress))
        {
            return 0;
        }

        if (remoteAddress.IsIPv4MappedToIPv6)
        {
            remoteAddress = remoteAddress.MapToIPv4();
        }

        var activeRoutes = GetActiveLocalRoutes();
        var preferredRoutes = activeRoutes.Where(IsPreferredDiscoveryRoute).ToList();

        if (preferredRoutes.Any(route => IsSameSubnet(remoteAddress, route)))
        {
            return 100;
        }

        if (activeRoutes.Any(route => IsSameSubnet(remoteAddress, route)))
        {
            return 70;
        }

        return remoteAddress switch
        {
            { AddressFamily: AddressFamily.InterNetwork } when remoteAddress.ToString().StartsWith("192.168.", StringComparison.Ordinal) => 40,
            { AddressFamily: AddressFamily.InterNetwork } when remoteAddress.ToString().StartsWith("10.", StringComparison.Ordinal) => 35,
            { AddressFamily: AddressFamily.InterNetwork } when remoteAddress.ToString().StartsWith("172.", StringComparison.Ordinal) => 30,
            _ => 10
        };
    }

    private static bool IsSameSubnet(IPAddress remoteAddress, LocalRouteDescriptor route)
    {
        if (string.IsNullOrWhiteSpace(route.Netmask) ||
            !IPAddress.TryParse(route.Address, out var localAddress) ||
            !IPAddress.TryParse(route.Netmask, out var subnetMask))
        {
            return false;
        }

        var remoteBytes = remoteAddress.GetAddressBytes();
        var localBytes = localAddress.GetAddressBytes();
        var maskBytes = subnetMask.GetAddressBytes();
        if (remoteBytes.Length != localBytes.Length || localBytes.Length != maskBytes.Length)
        {
            return false;
        }

        for (var index = 0; index < remoteBytes.Length; index++)
        {
            if ((remoteBytes[index] & maskBytes[index]) != (localBytes[index] & maskBytes[index]))
            {
                return false;
            }
        }

        return true;
    }

    private static IPAddress ComputeBroadcastAddress(IPAddress address, IPAddress subnetMask)
    {
        var addressBytes = address.GetAddressBytes();
        var maskBytes = subnetMask.GetAddressBytes();
        var broadcastBytes = new byte[addressBytes.Length];

        for (var index = 0; index < addressBytes.Length; index++)
        {
            broadcastBytes[index] = (byte)(addressBytes[index] | (maskBytes[index] ^ 255));
        }

        return new IPAddress(broadcastBytes);
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

    private sealed record LocalRouteDescriptor(
        string AdapterName,
        string AdapterType,
        string Address,
        string? Netmask,
        string? BroadcastAddress);

    private static async Task SafeAwaitAsync(Task task)
    {
        try
        {
            await task;
        }
        catch
        {
            // Ignore shutdown exceptions.
        }
    }
}
