using System.Windows;
using LocalBridge.Core.Protocol;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Services;

public sealed class BridgeConnectionService : IConnectionService
{
    private readonly IConnectionService _lanConnectionService;
    private readonly BluetoothConnectionService _bluetoothConnectionService;
    private readonly ILoggerService _loggerService;
    private ConnectionStateModel? _boundState;

    public BridgeConnectionService(
        IConnectionService lanConnectionService,
        BluetoothConnectionService bluetoothConnectionService,
        ILoggerService loggerService)
    {
        _lanConnectionService = lanConnectionService;
        _bluetoothConnectionService = bluetoothConnectionService;
        _loggerService = loggerService;

        _lanConnectionService.SessionChanged += HandleLanSessionChanged;
        _lanConnectionService.ChatMessageReceived += HandleLanChatMessageReceived;
        _bluetoothConnectionService.SessionChanged += HandleBluetoothSessionChanged;
        _bluetoothConnectionService.ChatMessageReceived += HandleBluetoothChatMessageReceived;
    }

    public event Action<ConnectionSessionSnapshot?>? SessionChanged;

    public event Action<TextChatPacketDto>? ChatMessageReceived;

    public void RegisterFileTransferHandler(IFileTransferEndpointHandler handler)
    {
        _lanConnectionService.RegisterFileTransferHandler(handler);
        _bluetoothConnectionService.RegisterFileTransferHandler(handler);
    }

    public async Task InitializeAsync(ConnectionStateModel connectionState, CancellationToken cancellationToken = default)
    {
        _boundState = connectionState;
        await _lanConnectionService.InitializeAsync(connectionState, cancellationToken);
        _bluetoothConnectionService.UseSharedPairingCode(connectionState.LocalPairingCode);
        await _bluetoothConnectionService.InitializeAsync(connectionState, cancellationToken);
        await ApplyModeStatusAsync(connectionState, connectionState.CurrentMode, cancellationToken);
    }

    public async Task SetConnectionModeAsync(
        ConnectionStateModel connectionState,
        AppConnectionMode mode,
        CancellationToken cancellationToken = default)
    {
        _boundState = connectionState;
        await _lanConnectionService.SetConnectionModeAsync(connectionState, mode, cancellationToken);
        if (mode == AppConnectionMode.BluetoothFallback || mode == AppConnectionMode.Auto)
        {
            await _bluetoothConnectionService.EnsureAvailabilityAsync(connectionState, cancellationToken);
        }
        await _bluetoothConnectionService.SetConnectionModeAsync(connectionState, mode, cancellationToken);
        await ApplyModeStatusAsync(connectionState, mode, cancellationToken);
    }

    public async Task SetDiscoveryActivityAsync(
        ConnectionStateModel connectionState,
        bool isActive,
        CancellationToken cancellationToken = default)
    {
        await _lanConnectionService.SetDiscoveryActivityAsync(connectionState, isActive, cancellationToken);
        await _bluetoothConnectionService.SetDiscoveryActivityAsync(connectionState, isActive, cancellationToken);
        await ApplyModeStatusAsync(connectionState, connectionState.CurrentMode, cancellationToken);
    }

    public async Task ConnectToPeerAsync(
        ConnectionStateModel connectionState,
        DevicePeer? peer,
        string pairingToken,
        CancellationToken cancellationToken = default)
    {
        var targetMode = ResolveTargetMode(connectionState.CurrentMode, peer);
        if (targetMode is null)
        {
            await UpdateStateAsync(connectionState, state =>
            {
                state.LifecycleState = ConnectionLifecycleState.Failed;
                state.StatusText = "Select a transport-compatible peer before connecting.";
                state.LastFailure = "transport_not_selected";
            });
            return;
        }

        if (targetMode == AppConnectionMode.BluetoothFallback && !_bluetoothConnectionService.IsAvailable)
        {
            await _bluetoothConnectionService.EnsureAvailabilityAsync(connectionState, cancellationToken);
        }

        if (targetMode == AppConnectionMode.BluetoothFallback && !_bluetoothConnectionService.IsAvailable)
        {
            await UpdateStateAsync(connectionState, state =>
            {
                state.LifecycleState = ConnectionLifecycleState.Failed;
                state.StatusText = "Bluetooth fallback is unavailable on this Windows PC. Use Local Wi-Fi / Hotspot instead.";
                state.LastFailure = "bluetooth_unavailable";
                state.ActiveTransportLabel = BuildStandbyTransportLabel(state.CurrentMode, bluetoothAvailable: false);
            });
            await _loggerService.LogWarningAsync(
                $"Bluetooth fallback was requested but RFCOMM is unavailable on this Windows PC. {_bluetoothConnectionService.AvailabilityReason}",
                cancellationToken);
            return;
        }

        if (targetMode == AppConnectionMode.LocalLan)
        {
            await _bluetoothConnectionService.DisconnectAsync(connectionState, cancellationToken);
            await _lanConnectionService.ConnectToPeerAsync(connectionState, peer, pairingToken, cancellationToken);
        }
        else
        {
            await _lanConnectionService.DisconnectAsync(connectionState, cancellationToken);
            await _bluetoothConnectionService.ConnectToPeerAsync(connectionState, peer, pairingToken, cancellationToken);
        }
    }

    public async Task<ConnectionSessionSnapshot?> GetActiveSessionAsync(CancellationToken cancellationToken = default)
    {
        return await _bluetoothConnectionService.GetActiveSessionAsync(cancellationToken)
            ?? await _lanConnectionService.GetActiveSessionAsync(cancellationToken);
    }

    public Task<LocalDeviceProfile> GetLocalDeviceProfileAsync(CancellationToken cancellationToken = default)
    {
        return _lanConnectionService.GetLocalDeviceProfileAsync(cancellationToken);
    }

    public async Task<TextChatDeliveryReceiptDto> SendChatMessageAsync(TextChatPacketDto packet, CancellationToken cancellationToken = default)
    {
        var session = await GetActiveSessionAsync(cancellationToken);
        if (session?.TransportMode == AppConnectionMode.BluetoothFallback)
        {
            return await _bluetoothConnectionService.SendChatMessageAsync(packet, cancellationToken);
        }

        return await _lanConnectionService.SendChatMessageAsync(packet, cancellationToken);
    }

    public async Task DisconnectAsync(ConnectionStateModel connectionState, CancellationToken cancellationToken = default)
    {
        var session = await GetActiveSessionAsync(cancellationToken);
        if (session?.TransportMode == AppConnectionMode.BluetoothFallback)
        {
            await _bluetoothConnectionService.DisconnectAsync(connectionState, cancellationToken);
            return;
        }

        await _lanConnectionService.DisconnectAsync(connectionState, cancellationToken);
        await _bluetoothConnectionService.DisconnectAsync(connectionState, cancellationToken);
    }

    public async Task ShutdownAsync(ConnectionStateModel connectionState, CancellationToken cancellationToken = default)
    {
        await _bluetoothConnectionService.ShutdownAsync(connectionState, cancellationToken);
        await _lanConnectionService.ShutdownAsync(connectionState, cancellationToken);
    }

    private void HandleLanSessionChanged(ConnectionSessionSnapshot? session)
    {
        _ = ApplySessionTransportStateAsync(session);
        SessionChanged?.Invoke(session);
    }

    private void HandleBluetoothSessionChanged(ConnectionSessionSnapshot? session)
    {
        _ = ApplySessionTransportStateAsync(session);
        SessionChanged?.Invoke(session);
    }

    private void HandleLanChatMessageReceived(TextChatPacketDto packet)
    {
        ChatMessageReceived?.Invoke(packet);
    }

    private void HandleBluetoothChatMessageReceived(TextChatPacketDto packet)
    {
        ChatMessageReceived?.Invoke(packet);
    }

    private async Task ApplyModeStatusAsync(
        ConnectionStateModel connectionState,
        AppConnectionMode mode,
        CancellationToken cancellationToken)
    {
        await UpdateStateAsync(connectionState, state =>
        {
            state.CurrentMode = mode;
            state.LocalPairingCode = string.IsNullOrWhiteSpace(state.LocalPairingCode)
                ? "------"
                : state.LocalPairingCode;
            state.TransportHint = mode switch
            {
                AppConnectionMode.Auto when !_bluetoothConnectionService.IsAvailable =>
                    "Auto currently uses Local Wi-Fi / Hotspot only on this PC because Bluetooth RFCOMM is unavailable.",
                AppConnectionMode.Auto => "Auto scans hotspot/LAN and Bluetooth, but still prefers hotspot/LAN for speed.",
                AppConnectionMode.BluetoothFallback when !_bluetoothConnectionService.IsAvailable =>
                    "Bluetooth fallback is unavailable on this Windows PC. Keep using Local Wi-Fi / Hotspot on this host.",
                AppConnectionMode.BluetoothFallback => "Bluetooth is slower than local Wi-Fi and is recommended mainly for messages and very small payloads.",
                _ => "Local Wi-Fi / Hotspot remains the fastest and recommended path for chat and transfers."
            };

            if (!_bluetoothConnectionService.IsAvailable)
            {
                state.AvailableTransportsLabel = state.AvailableTransportsLabel.Replace(
                    "Bluetooth fallback standby",
                    "Bluetooth unavailable on this PC",
                    StringComparison.OrdinalIgnoreCase);
            }

            if (!state.IsConnected)
            {
                state.ActiveTransportLabel = BuildStandbyTransportLabel(mode, _bluetoothConnectionService.IsAvailable);
            }
        });

        await _loggerService.LogInfoAsync($"Bridge transport mode is now {mode}.", cancellationToken);
    }

    private async Task ApplySessionTransportStateAsync(ConnectionSessionSnapshot? session)
    {
        if (_boundState is null)
        {
            return;
        }

        await UpdateStateAsync(_boundState, state =>
        {
            if (session is null)
            {
                if (!state.IsConnected)
                {
                    state.ActiveTransportLabel = BuildStandbyTransportLabel(state.CurrentMode, _bluetoothConnectionService.IsAvailable);
                }

                return;
            }

            if (session.TransportMode == AppConnectionMode.BluetoothFallback)
            {
                state.ActiveTransportLabel = "Connected via Bluetooth fallback";
                state.AvailableTransportsLabel = "Bluetooth active · LAN listener still ready";
            }
            else
            {
                state.ActiveTransportLabel = "Connected via Local Wi-Fi / Hotspot";
                state.AvailableTransportsLabel = _bluetoothConnectionService.IsAvailable
                    ? "Local Wi-Fi / Hotspot active · Bluetooth fallback standby"
                    : "Local Wi-Fi / Hotspot active · Bluetooth unavailable on this PC";
            }
        });
    }

    private static string BuildStandbyTransportLabel(AppConnectionMode mode, bool bluetoothAvailable)
    {
        return mode switch
        {
            AppConnectionMode.LocalLan => "Standby: Local Wi-Fi / Hotspot",
            AppConnectionMode.BluetoothFallback => bluetoothAvailable
                ? "Standby: Bluetooth fallback"
                : "Standby: Bluetooth unavailable on this PC",
            _ => bluetoothAvailable
                ? "Standby: Auto (LAN preferred, Bluetooth fallback ready)"
                : "Standby: Auto (LAN only on this PC)"
        };
    }

    private static AppConnectionMode? ResolveTargetMode(AppConnectionMode selectedMode, DevicePeer? peer)
    {
        if (peer is null)
        {
            return null;
        }

        return selectedMode switch
        {
            AppConnectionMode.LocalLan when peer.TransportMode == AppConnectionMode.LocalLan => AppConnectionMode.LocalLan,
            AppConnectionMode.BluetoothFallback when peer.TransportMode == AppConnectionMode.BluetoothFallback => AppConnectionMode.BluetoothFallback,
            AppConnectionMode.Auto => peer.TransportMode,
            _ => null
        };
    }

    private static async Task UpdateStateAsync(ConnectionStateModel connectionState, Action<ConnectionStateModel> update)
    {
        var dispatcher = Application.Current?.Dispatcher;

        if (dispatcher is null || dispatcher.CheckAccess())
        {
            update(connectionState);
            return;
        }

        await dispatcher.InvokeAsync(() => update(connectionState));
    }
}
