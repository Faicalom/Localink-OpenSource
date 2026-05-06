using System.Net;
using System.Net.Http;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Windows;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using LocalBridge.Core;
using LocalBridge.Core.Discovery;
using LocalBridge.Core.Protocol;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Infrastructure;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Services;

public sealed class ConnectionService : IConnectionService
{
    private readonly IDiscoveryService _discoveryService;
    private readonly ITrustedDevicesService _trustedDevicesService;
    private readonly ILoggerService _loggerService;
    private readonly HttpClient _httpClient;
    private readonly SemaphoreSlim _lifecycleGate = new(1, 1);
    private readonly SemaphoreSlim _connectGate = new(1, 1);
    private readonly object _incomingSessionsGate = new();
    private readonly Dictionary<string, IncomingConnectionSession> _incomingSessions = new(StringComparer.OrdinalIgnoreCase);

    private WebApplication? _host;
    private LocalDeviceProfile? _localDevice;
    private IFileTransferEndpointHandler? _fileTransferHandler;
    private ConnectionStateModel? _boundState;
    private ActiveConnection? _activeConnection;
    private ReconnectPlan? _reconnectPlan;
    private CancellationTokenSource? _heartbeatCts;
    private Task? _heartbeatTask;
    private CancellationTokenSource? _reconnectCts;
    private Task? _reconnectTask;
    private CancellationTokenSource? _sessionCleanupCts;
    private Task? _sessionCleanupTask;
    private bool _isInitialized;
    private string _localPairingCode = GeneratePairingCode();

    public event Action<ConnectionSessionSnapshot?>? SessionChanged;

    public event Action<TextChatPacketDto>? ChatMessageReceived;

    public ConnectionService(
        IDiscoveryService discoveryService,
        ITrustedDevicesService trustedDevicesService,
        ILoggerService loggerService)
    {
        _discoveryService = discoveryService;
        _trustedDevicesService = trustedDevicesService;
        _loggerService = loggerService;
        _httpClient = new HttpClient
        {
            Timeout = TimeSpan.FromSeconds(AppConstants.ConnectionRequestTimeoutSeconds)
        };
    }

    public void RegisterFileTransferHandler(IFileTransferEndpointHandler handler)
    {
        _fileTransferHandler = handler;
    }

    public async Task InitializeAsync(ConnectionStateModel connectionState, CancellationToken cancellationToken = default)
    {
        await _lifecycleGate.WaitAsync(cancellationToken);

        try
        {
            _boundState = connectionState;
            _localDevice = await _discoveryService.GetLocalDeviceAsync(cancellationToken);

            if (!_isInitialized)
            {
                await StartLocalHostAsync(cancellationToken);
                StartIncomingSessionCleanupLoop();
                _isInitialized = true;
            }

            await UpdateStateAsync(connectionState, state =>
            {
                state.LifecycleState = ConnectionLifecycleState.Idle;
                state.IsDiscovering = false;
                state.IsConnected = false;
                state.CanAcceptIncomingConnections = true;
                state.IsServerListening = true;
                ApplyLocalRouteMetadata(state, _localDevice);
                state.LocalPairingCode = _localPairingCode;
                state.ActivePeerName = "No peer selected";
                state.ActivePeerId = string.Empty;
                state.SessionId = string.Empty;
                state.LastFailure = string.Empty;
                state.HandshakeSummary = "Waiting for a local peer connection.";
                state.StatusText = $"Local connection host is ready as {_localDevice.DeviceName} ({_localDevice.DeviceId}).";
                state.ActiveTransportLabel = BuildStandbyTransportLabel(state.CurrentMode);
                state.IsReconnectScheduled = false;
                state.ReconnectAttemptCount = 0;
            });

            await _loggerService.LogInfoAsync(
                $"Connection host is listening on TCP {_localDevice.ApiPort}. Local pairing code: {_localPairingCode}.",
                cancellationToken);
            await NotifySessionChangedAsync(cancellationToken);
        }
        finally
        {
            _lifecycleGate.Release();
        }
    }

    public async Task SetConnectionModeAsync(
        ConnectionStateModel connectionState,
        AppConnectionMode mode,
        CancellationToken cancellationToken = default)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);

        await UpdateStateAsync(connectionState, state =>
        {
            state.CurrentMode = mode;
            ApplyLocalRouteMetadata(state, localDevice);

            if (mode == AppConnectionMode.BluetoothFallback)
            {
                state.LifecycleState = ConnectionLifecycleState.Idle;
                state.IsDiscovering = false;
                state.StatusText = "LAN hosting stays ready, but Bluetooth is the preferred fallback transport.";
            }
            else if (mode == AppConnectionMode.Auto)
            {
                if (!state.IsConnected &&
                    state.LifecycleState is ConnectionLifecycleState.Idle or ConnectionLifecycleState.Discovering)
                {
                    state.LifecycleState = state.IsDiscovering
                        ? ConnectionLifecycleState.Discovering
                        : ConnectionLifecycleState.Idle;
                }

                state.StatusText = "Auto mode prefers hotspot/LAN first and can fall back to Bluetooth if needed.";
            }
            else if (!state.IsConnected)
            {
                state.LifecycleState = state.IsDiscovering
                    ? ConnectionLifecycleState.Discovering
                    : ConnectionLifecycleState.Idle;
                state.StatusText = "Local hotspot/LAN is selected as the active connection transport.";
            }

            if (!state.IsConnected)
            {
                state.ActiveTransportLabel = BuildStandbyTransportLabel(mode);
            }
        });

        await _loggerService.LogInfoAsync($"[SESSION] Connection mode switched to {mode}.", cancellationToken);
    }

    public async Task SetDiscoveryActivityAsync(
        ConnectionStateModel connectionState,
        bool isActive,
        CancellationToken cancellationToken = default)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);

        await UpdateStateAsync(connectionState, state =>
        {
            state.IsDiscovering = isActive;
            ApplyLocalRouteMetadata(state, localDevice);

            if (!state.IsConnected &&
                state.LifecycleState is ConnectionLifecycleState.Idle or ConnectionLifecycleState.Discovering)
            {
                state.LifecycleState = isActive
                    ? ConnectionLifecycleState.Discovering
                    : ConnectionLifecycleState.Idle;
            }

            if (!state.IsConnected)
            {
                state.StatusText = isActive
                    ? "Scanning the local hotspot/LAN for compatible peers."
                    : "Discovery is paused. Start scanning or refresh to find peers.";
                state.ActiveTransportLabel = BuildStandbyTransportLabel(state.CurrentMode);
            }
        });

        await _loggerService.LogInfoAsync(isActive
            ? "[DISCOVERY] Local discovery marked as active."
            : "[DISCOVERY] Local discovery marked as inactive.", cancellationToken);
    }

    public async Task ConnectToPeerAsync(
        ConnectionStateModel connectionState,
        DevicePeer? peer,
        string pairingToken,
        CancellationToken cancellationToken = default)
    {
        await _connectGate.WaitAsync(cancellationToken);

        try
        {
            if (connectionState.CurrentMode == AppConnectionMode.BluetoothFallback)
            {
                await FailAsync(connectionState, "Switch to Auto or Local Wi-Fi / Hotspot before opening a LAN session.");
                return;
            }

            if (peer is null)
            {
                await FailAsync(connectionState, "Select a discovered device before starting a local connection.");
                return;
            }

            if (string.IsNullOrWhiteSpace(pairingToken))
            {
                await UpdateStateAsync(connectionState, state =>
                {
                    state.LifecycleState = ConnectionLifecycleState.WaitingForPairing;
                    state.ActivePeerName = peer.DisplayName;
                    state.ActivePeerId = peer.Id;
                    state.StatusText = $"Enter the confirmation code displayed on {peer.DisplayName} before connecting.";
                    state.LastFailure = string.Empty;
                });

                await _loggerService.LogWarningAsync($"[PAIRING] Connection to {peer.DisplayName} is waiting for a pairing token.", cancellationToken);
                return;
            }

            await CancelReconnectLoopAsync();

            if (_activeConnection is not null)
            {
                await DisconnectInternalAsync(connectionState, notifyRemote: true, markDisconnected: false, cancellationToken);
            }

            var success = await AttemptConnectAsync(
                connectionState,
                peer,
                pairingToken.Trim(),
                isReconnectAttempt: false,
                cancellationToken);

            if (!success)
            {
                await _loggerService.LogWarningAsync($"Connection attempt to {peer.DisplayName} did not complete.", cancellationToken);
            }
        }
        finally
        {
            _connectGate.Release();
        }
    }

    public async Task<ConnectionSessionSnapshot?> GetActiveSessionAsync(CancellationToken cancellationToken = default)
    {
        var connection = _activeConnection;
        if (connection is null)
        {
            return null;
        }

        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        return new ConnectionSessionSnapshot
        {
            SessionId = connection.SessionId,
            LocalDeviceId = localDevice.DeviceId,
            LocalDeviceName = localDevice.DeviceName,
            Peer = ClonePeer(connection.Peer),
            IsConnected = _boundState?.IsConnected ?? true,
            IsIncoming = connection.IsIncoming,
            TransportMode = AppConnectionMode.LocalLan
        };
    }

    public Task<LocalDeviceProfile> GetLocalDeviceProfileAsync(CancellationToken cancellationToken = default)
    {
        return EnsureLocalDeviceAsync(cancellationToken);
    }

    public async Task<TextChatDeliveryReceiptDto> SendChatMessageAsync(TextChatPacketDto packet, CancellationToken cancellationToken = default)
    {
        var connection = _activeConnection;
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);

        if (connection is null || !(_boundState?.IsConnected ?? true))
        {
            return CreateChatFailureReceipt(packet.Id, "not_connected", localDevice);
        }

        if (string.IsNullOrWhiteSpace(packet.Text))
        {
            return CreateChatFailureReceipt(packet.Id, "empty_message", localDevice);
        }

        var normalizedPacket = packet with
        {
            SessionId = connection.SessionId,
            SenderId = localDevice.DeviceId,
            SenderName = localDevice.DeviceName,
            ReceiverId = connection.Peer.Id,
            TimestampUtc = packet.TimestampUtc == default ? DateTimeOffset.UtcNow : packet.TimestampUtc
        };

        var envelope = ProtocolEnvelopeFactory.Create(
            packetType: ProtocolPacketTypes.ChatTextMessage,
            payload: normalizedPacket,
            senderDeviceId: localDevice.DeviceId,
            receiverDeviceId: connection.Peer.Id,
            sessionId: connection.SessionId,
            messageId: normalizedPacket.Id,
            sentAtUtc: normalizedPacket.TimestampUtc);

        try
        {
            using var response = await _httpClient.PostAsJsonAsync(
                BuildPeerUri(connection.Peer, AppConstants.ChatMessagesPath),
                envelope,
                JsonDefaults.Options,
                cancellationToken);

            var receiptEnvelope = await response.Content.ReadEnvelopeAsync<TextChatDeliveryReceiptDto>(
                cancellationToken);
            var receiptValidation = ProtocolEnvelopeValidator.Validate(
                receiptEnvelope,
                expectedPacketTypes: [ProtocolPacketTypes.ChatDeliveryReceipt]);
            var receipt = receiptValidation.IsValid ? receiptEnvelope!.Payload! : null;

            if (!response.IsSuccessStatusCode || receipt is null)
            {
                var failureReason = receiptEnvelope?.Error?.Code ??
                    receiptValidation.ErrorCode ??
                    $"chat_http_{(int)response.StatusCode}";
                await _loggerService.LogWarningAsync(
                    $"Chat delivery request failed for {connection.Peer.DisplayName}: {failureReason}.",
                    cancellationToken);

                return CreateChatFailureReceipt(packet.Id, failureReason, localDevice);
            }

            await _loggerService.LogInfoAsync(
                $"Chat message {packet.Id} sent to {connection.Peer.DisplayName}. Delivery state: {receipt.Status}.",
                cancellationToken);

            return receipt;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return CreateChatFailureReceipt(packet.Id, "chat_timeout", localDevice);
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync(
                $"Chat transport error while sending to {connection.Peer.DisplayName}: {ex.Message}",
                cancellationToken);

            return CreateChatFailureReceipt(packet.Id, ex.Message, localDevice);
        }
    }

    public async Task DisconnectAsync(ConnectionStateModel connectionState, CancellationToken cancellationToken = default)
    {
        await _connectGate.WaitAsync(cancellationToken);

        try
        {
            await CancelReconnectLoopAsync();
            _reconnectPlan = null;
            await DisconnectInternalAsync(connectionState, notifyRemote: true, markDisconnected: true, cancellationToken);
        }
        finally
        {
            _connectGate.Release();
        }
    }

    public async Task ShutdownAsync(ConnectionStateModel connectionState, CancellationToken cancellationToken = default)
    {
        await _lifecycleGate.WaitAsync(cancellationToken);

        try
        {
            await DisconnectAsync(connectionState, cancellationToken);

            _sessionCleanupCts?.Cancel();
            if (_sessionCleanupTask is not null)
            {
                await SafeAwaitAsync(_sessionCleanupTask);
            }

            if (_host is not null)
            {
                await _host.StopAsync(cancellationToken);
                await _host.DisposeAsync();
                _host = null;
            }

            _sessionCleanupCts?.Dispose();
            _sessionCleanupCts = null;
            _sessionCleanupTask = null;
            _isInitialized = false;

            await UpdateStateAsync(connectionState, state =>
            {
                state.IsServerListening = false;
                state.IsConnected = false;
                state.LifecycleState = ConnectionLifecycleState.Disconnected;
                state.StatusText = "Local connection host stopped.";
            });

            await NotifySessionChangedAsync(cancellationToken);
        }
        finally
        {
            _lifecycleGate.Release();
        }
    }

    private async Task<bool> AttemptConnectAsync(
        ConnectionStateModel connectionState,
        DevicePeer peer,
        string pairingToken,
        bool isReconnectAttempt,
        CancellationToken cancellationToken)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        var resolvedPairingToken = await ResolveOutgoingPairingTokenAsync(peer, pairingToken, cancellationToken);

        await UpdateStateAsync(connectionState, state =>
        {
            state.ActivePeerName = peer.DisplayName;
            state.ActivePeerId = peer.Id;
            state.LastFailure = string.Empty;
            state.LifecycleState = ConnectionLifecycleState.WaitingForPairing;
            state.StatusText = $"Preparing handshake with {peer.DisplayName}.";
            state.HandshakeSummary = $"Using confirmation code to connect to {peer.DisplayName}.";
        });

        await _loggerService.LogInfoAsync(
            isReconnectAttempt
                ? $"[RECONNECT] Attempt is preparing a handshake with {peer.DisplayName}."
                : $"[PAIRING] Starting handshake with discovered peer {peer.DisplayName} at {peer.IpAddress}:{peer.Port}.",
            cancellationToken);

        await UpdateStateAsync(connectionState, state =>
        {
            state.LifecycleState = ConnectionLifecycleState.Connecting;
            state.StatusText = $"Connecting to {peer.DisplayName} over the local network.";
        });

        ConnectionHandshakeResponseDto? handshakeResponse;

        try
        {
            using var requestCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            requestCancellation.CancelAfter(TimeSpan.FromSeconds(AppConstants.ConnectionRequestTimeoutSeconds));

            var request = new ConnectionHandshakeRequestDto(
                DeviceId: localDevice.DeviceId,
                DeviceName: localDevice.DeviceName,
                Platform: localDevice.Platform,
                AppVersion: localDevice.AppVersion,
                PairingToken: resolvedPairingToken,
                SupportedModes: localDevice.SupportedModes);

            var envelope = ProtocolEnvelopeFactory.Create(
                packetType: ProtocolPacketTypes.ConnectionHandshakeRequest,
                payload: request,
                senderDeviceId: localDevice.DeviceId,
                receiverDeviceId: peer.Id,
                sentAtUtc: DateTimeOffset.UtcNow);

            using var response = await _httpClient.PostAsJsonAsync(
                BuildPeerUri(peer, AppConstants.ConnectionHandshakePath),
                envelope,
                JsonDefaults.Options,
                requestCancellation.Token);

            var handshakeEnvelope = await response.Content.ReadEnvelopeAsync<ConnectionHandshakeResponseDto>(
                requestCancellation.Token);
            var handshakeValidation = ProtocolEnvelopeValidator.Validate(
                handshakeEnvelope,
                expectedPacketTypes: [ProtocolPacketTypes.ConnectionHandshakeResponse]);
            handshakeResponse = handshakeValidation.IsValid ? handshakeEnvelope!.Payload! : null;

            if (!response.IsSuccessStatusCode || handshakeResponse is null)
            {
                var failureReason = handshakeEnvelope?.Error?.Code ??
                    handshakeValidation.ErrorCode ??
                    $"HTTP {(int)response.StatusCode}";
                await FailAsync(connectionState, $"Handshake request to {peer.DisplayName} failed: {failureReason}.");
                return false;
            }
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            await FailAsync(connectionState, $"Handshake to {peer.DisplayName} timed out.");
            return false;
        }
        catch (Exception ex)
        {
            await FailAsync(connectionState, $"Could not reach {peer.DisplayName}: {ex.Message}");
            return false;
        }

        if (!handshakeResponse.Accepted)
        {
            var waitingForPairing = string.Equals(
                handshakeResponse.SessionState,
                ProtocolConstants.SessionStateWaitingForPairing,
                StringComparison.OrdinalIgnoreCase);

            await UpdateStateAsync(connectionState, state =>
            {
                state.LifecycleState = waitingForPairing
                    ? ConnectionLifecycleState.WaitingForPairing
                    : ConnectionLifecycleState.Failed;
                state.StatusText = waitingForPairing
                    ? $"Pairing confirmation is required by {peer.DisplayName}."
                    : $"Handshake with {peer.DisplayName} was rejected.";
                state.LastFailure = handshakeResponse.FailureReason ?? "unknown_error";
                state.HandshakeSummary = handshakeResponse.FailureReason ?? "Handshake rejected.";
                state.IsConnected = false;
            });

            await _loggerService.LogWarningAsync(
                $"Handshake rejected by {peer.DisplayName}: {handshakeResponse.FailureReason ?? "unknown_reason"}.",
                cancellationToken);

            return false;
        }

        var connectedPeer = ClonePeer(peer);
        connectedPeer.DisplayName = handshakeResponse.ServerDeviceName;
        connectedPeer.Platform = handshakeResponse.ServerPlatform;
        connectedPeer.AppVersion = handshakeResponse.ServerAppVersion;
        connectedPeer.SupportedModes = handshakeResponse.SupportedModes.ToArray();
        connectedPeer.IsTrusted = true;
        connectedPeer.IsOnline = true;
        connectedPeer.LastSeenAtUtc = DateTimeOffset.UtcNow;

        await _trustedDevicesService.TrustDeviceAsync(connectedPeer, cancellationToken);

        var activeConnection = new ActiveConnection(
            SessionId: handshakeResponse.SessionId ?? Guid.NewGuid().ToString("N"),
            Peer: connectedPeer,
            PairingToken: resolvedPairingToken,
            IsIncoming: false);

        _activeConnection = activeConnection;

        await UpdateStateAsync(connectionState, state =>
        {
            state.LifecycleState = ConnectionLifecycleState.Paired;
            state.IsConnected = false;
            state.ActivePeerName = connectedPeer.DisplayName;
            state.ActivePeerId = connectedPeer.Id;
            state.SessionId = activeConnection.SessionId;
            state.StatusText = $"Handshake accepted by {connectedPeer.DisplayName}. Validating the connection.";
            state.HandshakeSummary = $"Paired with {connectedPeer.DisplayName} using session {activeConnection.SessionId}.";
            state.ActiveTransportLabel = "Pairing over Local Wi-Fi / Hotspot";
            state.LastFailure = string.Empty;
        });

        await _loggerService.LogInfoAsync(
            $"[PAIRING] Handshake accepted by {connectedPeer.DisplayName}. Session {activeConnection.SessionId} created.",
            cancellationToken);

        var heartbeatOkay = await SendHeartbeatAsync(activeConnection, cancellationToken);
        if (!heartbeatOkay)
        {
            await FailAsync(connectionState, $"Handshake succeeded, but heartbeat validation failed for {connectedPeer.DisplayName}.");
            _activeConnection = null;
            return false;
        }

        _reconnectPlan = new ReconnectPlan(
            PeerId: connectedPeer.Id,
            PeerName: connectedPeer.DisplayName,
            LastKnownIp: connectedPeer.IpAddress,
            LastKnownPort: connectedPeer.Port,
            PairingToken: resolvedPairingToken);

        await UpdateStateAsync(connectionState, state =>
        {
            state.LifecycleState = ConnectionLifecycleState.Connected;
            state.IsConnected = true;
            state.IsReconnectScheduled = false;
            state.ReconnectAttemptCount = 0;
            state.StatusText = $"Connected to {connectedPeer.DisplayName} over the local network.";
            state.ActiveTransportLabel = "Connected via Local Wi-Fi / Hotspot";
        });

        await _loggerService.LogInfoAsync($"[SESSION] Local connection is now active with {connectedPeer.DisplayName}.", cancellationToken);

        StartHeartbeatLoop(connectionState, activeConnection);
        await NotifySessionChangedAsync(cancellationToken);
        return true;
    }

    private async Task<string> ResolveOutgoingPairingTokenAsync(
        DevicePeer peer,
        string pairingToken,
        CancellationToken cancellationToken)
    {
        var normalizedPairingToken = pairingToken.Trim();
        if (peer.TransportMode != AppConnectionMode.LocalLan)
        {
            return normalizedPairingToken;
        }

        try
        {
            using var requestCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            requestCancellation.CancelAfter(TimeSpan.FromSeconds(Math.Max(2, AppConstants.ConnectionRequestTimeoutSeconds / 2.0)));

            using var response = await _httpClient.GetAsync(
                BuildPeerUri(peer, AppConstants.ConnectionStatusPath),
                requestCancellation.Token);

            var statusEnvelope = await response.Content.ReadEnvelopeAsync<StatusResponseDto>(requestCancellation.Token);
            var statusValidation = ProtocolEnvelopeValidator.Validate(
                statusEnvelope,
                expectedPacketTypes: [ProtocolPacketTypes.ConnectionStatus]);
            var statusPayload = statusValidation.IsValid ? statusEnvelope?.Payload : null;
            var discoveredPairingCode = statusPayload?.PairingCode?.Trim();

            if (!response.IsSuccessStatusCode || string.IsNullOrWhiteSpace(discoveredPairingCode))
            {
                return normalizedPairingToken;
            }

            if (!string.Equals(normalizedPairingToken, discoveredPairingCode, StringComparison.Ordinal))
            {
                await _loggerService.LogInfoAsync(
                    $"[PAIRING] Refreshed LAN pairing code from {peer.DisplayName} before handshake. Using the peer's current six-digit confirmation code.");
            }

            return discoveredPairingCode;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return normalizedPairingToken;
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync(
                $"[PAIRING] Could not refresh the LAN pairing code from {peer.DisplayName} before the handshake. Falling back to the manually entered code. {ex.Message}",
                cancellationToken);
            return normalizedPairingToken;
        }
    }

    private async Task DisconnectInternalAsync(
        ConnectionStateModel connectionState,
        bool notifyRemote,
        bool markDisconnected,
        CancellationToken cancellationToken)
    {
        var connection = _activeConnection;
        _activeConnection = null;

        await CancelHeartbeatLoopAsync();

        if (connection is not null && notifyRemote && !connection.IsIncoming)
        {
            try
            {
                var request = new ConnectionDisconnectRequestDto(
                    SessionId: connection.SessionId,
                    DeviceId: _localDevice?.DeviceId ?? string.Empty,
                    SentAtUtc: DateTimeOffset.UtcNow);

                var envelope = ProtocolEnvelopeFactory.Create(
                    packetType: ProtocolPacketTypes.ConnectionDisconnectRequest,
                    payload: request,
                    senderDeviceId: request.DeviceId,
                    receiverDeviceId: connection.Peer.Id,
                    sessionId: connection.SessionId,
                    sentAtUtc: request.SentAtUtc);

                await _httpClient.PostAsJsonAsync(
                    BuildPeerUri(connection.Peer, AppConstants.ConnectionDisconnectPath),
                    envelope,
                    JsonDefaults.Options,
                    cancellationToken);
            }
            catch (Exception ex)
            {
                await _loggerService.LogWarningAsync($"Disconnect notification failed: {ex.Message}", cancellationToken);
            }
        }

        if (markDisconnected)
        {
            await UpdateStateAsync(connectionState, state =>
            {
                state.IsConnected = false;
                state.LifecycleState = ConnectionLifecycleState.Disconnected;
                state.StatusText = connection is null
                    ? "No active local connection to close."
                    : $"Disconnected from {connection.Peer.DisplayName}.";
                state.SessionId = string.Empty;
                state.IsReconnectScheduled = false;
                state.ReconnectAttemptCount = 0;
                state.ActiveTransportLabel = BuildStandbyTransportLabel(state.CurrentMode);
            });
        }

        if (connection is not null)
        {
            await _loggerService.LogInfoAsync($"Closed local connection with {connection.Peer.DisplayName}.", cancellationToken);
        }

        await NotifySessionChangedAsync(cancellationToken);
    }

    private async Task<bool> SendHeartbeatAsync(ActiveConnection connection, CancellationToken cancellationToken)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);

        try
        {
            using var requestCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            requestCancellation.CancelAfter(TimeSpan.FromSeconds(AppConstants.ConnectionRequestTimeoutSeconds));

            var request = new ConnectionHeartbeatRequestDto(
                SessionId: connection.SessionId,
                DeviceId: localDevice.DeviceId,
                DeviceName: localDevice.DeviceName,
                Platform: localDevice.Platform,
                AppVersion: localDevice.AppVersion);

            var envelope = ProtocolEnvelopeFactory.Create(
                packetType: ProtocolPacketTypes.ConnectionHeartbeatRequest,
                payload: request,
                senderDeviceId: localDevice.DeviceId,
                receiverDeviceId: connection.Peer.Id,
                sessionId: connection.SessionId,
                sentAtUtc: DateTimeOffset.UtcNow);

            using var response = await _httpClient.PostAsJsonAsync(
                BuildPeerUri(connection.Peer, AppConstants.ConnectionHeartbeatPath),
                envelope,
                JsonDefaults.Options,
                requestCancellation.Token);

            var heartbeatEnvelope = await response.Content.ReadEnvelopeAsync<ConnectionHeartbeatResponseDto>(
                requestCancellation.Token);
            var heartbeatValidation = ProtocolEnvelopeValidator.Validate(
                heartbeatEnvelope,
                expectedPacketTypes: [ProtocolPacketTypes.ConnectionHeartbeatResponse]);
            var heartbeatResponse = heartbeatValidation.IsValid ? heartbeatEnvelope!.Payload! : null;

            if (!response.IsSuccessStatusCode || heartbeatResponse is null || !heartbeatResponse.Alive)
            {
                await _loggerService.LogWarningAsync(
                    $"[SESSION] Heartbeat rejected by {connection.Peer.DisplayName}: {heartbeatResponse?.FailureReason ?? heartbeatEnvelope?.Error?.Code ?? heartbeatValidation.ErrorCode ?? response.StatusCode.ToString()}",
                    cancellationToken);
                return false;
            }

            return true;
        }
        catch (Exception ex) when (ex is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
        {
            await _loggerService.LogWarningAsync($"Heartbeat to {connection.Peer.DisplayName} failed: {ex.Message}", cancellationToken);
            return false;
        }
    }

    private void StartHeartbeatLoop(ConnectionStateModel connectionState, ActiveConnection activeConnection)
    {
        _heartbeatCts?.Cancel();
        _heartbeatCts?.Dispose();
        _heartbeatCts = new CancellationTokenSource();

        var token = _heartbeatCts.Token;
        _heartbeatTask = Task.Run(async () =>
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(TimeSpan.FromSeconds(AppConstants.ConnectionHeartbeatIntervalSeconds), token);
                    var ok = await SendHeartbeatAsync(activeConnection, token);

                    if (!ok)
                    {
                        await HandleConnectionDropAsync(
                            connectionState,
                            $"Heartbeat with {activeConnection.Peer.DisplayName} failed.",
                            token);
                        break;
                    }
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception ex)
                {
                    await HandleConnectionDropAsync(
                        connectionState,
                        $"Unexpected heartbeat error: {ex.Message}",
                        token);
                    break;
                }
            }
        }, token);
    }

    private async Task HandleConnectionDropAsync(
        ConnectionStateModel connectionState,
        string reason,
        CancellationToken cancellationToken)
    {
        var droppedConnection = _activeConnection;
        _activeConnection = null;

        await CancelHeartbeatLoopAsync();

        await UpdateStateAsync(connectionState, state =>
        {
            state.IsConnected = false;
            state.LifecycleState = ConnectionLifecycleState.Disconnected;
            state.StatusText = reason;
            state.LastFailure = reason;
            state.IsReconnectScheduled = droppedConnection is not null && !droppedConnection.IsIncoming;
        });

        await _loggerService.LogWarningAsync(reason, cancellationToken);
        await NotifySessionChangedAsync(cancellationToken);

        if (droppedConnection is not null && !droppedConnection.IsIncoming)
        {
            _reconnectPlan = new ReconnectPlan(
                PeerId: droppedConnection.Peer.Id,
                PeerName: droppedConnection.Peer.DisplayName,
                LastKnownIp: droppedConnection.Peer.IpAddress,
                LastKnownPort: droppedConnection.Peer.Port,
                PairingToken: droppedConnection.PairingToken);

            await _loggerService.LogInfoAsync(
                $"[RECONNECT] Scheduled for {droppedConnection.Peer.DisplayName} in {AppConstants.ConnectionReconnectDelaySeconds} second(s).",
                cancellationToken);
            StartReconnectLoop(connectionState);
        }
    }

    private void StartReconnectLoop(ConnectionStateModel connectionState)
    {
        if (_reconnectPlan is null)
        {
            return;
        }

        if (_reconnectTask is { IsCompleted: false })
        {
            return;
        }

        _reconnectCts?.Cancel();
        _reconnectCts?.Dispose();
        _reconnectCts = new CancellationTokenSource();

        var token = _reconnectCts.Token;
        var plan = _reconnectPlan;

        _reconnectTask = Task.Run(async () =>
        {
            var attempt = 0;

            while (!token.IsCancellationRequested && plan is not null)
            {
                attempt++;

                await UpdateStateAsync(connectionState, state =>
                {
                    state.IsReconnectScheduled = true;
                    state.ReconnectAttemptCount = attempt;
                    state.LifecycleState = ConnectionLifecycleState.Connecting;
                    state.StatusText = $"Reconnect attempt {attempt} to {plan.PeerName} is in progress.";
                });

                await _loggerService.LogInfoAsync($"[RECONNECT] Attempt {attempt} to {plan.PeerName} started.", token);

                try
                {
                    var peer = await ResolveReconnectPeerAsync(plan, token);
                    if (peer is null)
                    {
                        await UpdateStateAsync(connectionState, state =>
                        {
                            state.LifecycleState = ConnectionLifecycleState.Disconnected;
                            state.StatusText = $"Waiting for {plan.PeerName} to reappear on the local network.";
                        });

                        await _loggerService.LogInfoAsync(
                            $"[RECONNECT] Attempt {attempt} is waiting for {plan.PeerName} to reappear in discovery.",
                            token);
                    }
                    else
                    {
                        var connected = await AttemptConnectAsync(
                            connectionState,
                            peer,
                            plan.PairingToken,
                            isReconnectAttempt: true,
                            token);

                        if (connected)
                        {
                            await UpdateStateAsync(connectionState, state =>
                            {
                                state.IsReconnectScheduled = false;
                                state.ReconnectAttemptCount = 0;
                            });
                            return;
                        }
                    }
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception ex)
                {
                    await _loggerService.LogWarningAsync($"[RECONNECT] Attempt {attempt} failed: {ex.Message}", token);
                }

                await _loggerService.LogInfoAsync(
                    $"Next reconnect attempt for {plan.PeerName} will retry in {AppConstants.ConnectionReconnectDelaySeconds} second(s).",
                    token);
                await Task.Delay(TimeSpan.FromSeconds(AppConstants.ConnectionReconnectDelaySeconds), token);
            }
        }, token);
    }

    private async Task<DevicePeer?> ResolveReconnectPeerAsync(ReconnectPlan plan, CancellationToken cancellationToken)
    {
        var peers = await _discoveryService.GetKnownPeersAsync(cancellationToken);
        var currentPeer = peers.FirstOrDefault(peer => string.Equals(peer.Id, plan.PeerId, StringComparison.OrdinalIgnoreCase));

        if (currentPeer is not null)
        {
            return currentPeer;
        }

        return new DevicePeer
        {
            Id = plan.PeerId,
            DisplayName = plan.PeerName,
            Platform = "Unknown",
            IpAddress = plan.LastKnownIp,
            Port = plan.LastKnownPort,
            BluetoothAddress = string.Empty,
            AppVersion = "unknown",
            SupportedModes = [DiscoverySupportedModes.LocalLan, DiscoverySupportedModes.BluetoothFallback],
            TransportMode = AppConnectionMode.LocalLan,
            IsTrusted = true,
            IsOnline = false,
            FirstSeenAtUtc = DateTimeOffset.UtcNow,
            LastSeenAtUtc = DateTimeOffset.UtcNow,
            HasResolvedIdentity = true
        };
    }

    private async Task StartLocalHostAsync(CancellationToken cancellationToken)
    {
        if (_host is not null)
        {
            return;
        }

        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            Args = [],
            ApplicationName = typeof(ConnectionService).Assembly.FullName,
            ContentRootPath = AppContext.BaseDirectory
        });

        builder.WebHost.UseKestrel(options => options.ListenAnyIP(AppConstants.DefaultApiPort));
        builder.Logging.ClearProviders();

        var app = builder.Build();

        app.MapGet(AppConstants.ConnectionStatusPath, HandleStatusAsync);
        app.MapPost(AppConstants.ConnectionHandshakePath, HandleHandshakeAsync);
        app.MapPost(AppConstants.ConnectionHeartbeatPath, HandleHeartbeatAsync);
        app.MapPost(AppConstants.ConnectionDisconnectPath, HandleDisconnectAsync);
        app.MapPost(AppConstants.ChatMessagesPath, HandleChatMessageAsync);
        app.MapPost(AppConstants.TransferPreparePath, HandleTransferPrepareAsync);
        app.MapPost(AppConstants.TransferChunkPath, HandleTransferChunkAsync);
        app.MapPost(AppConstants.TransferCompletePath, HandleTransferCompleteAsync);
        app.MapPost(AppConstants.TransferCancelPath, HandleTransferCancelAsync);

        await app.StartAsync(cancellationToken);
        _host = app;
    }

    private async Task HandleStatusAsync(HttpContext context)
    {
        LocalDeviceProfile? localDevice = null;
        try
        {
            localDevice = await EnsureLocalDeviceAsync(context.RequestAborted);

            await context.Response.WriteEnvelopeAsync(
                CreateServerEnvelope(
                    packetType: ProtocolPacketTypes.ConnectionStatus,
                    payload: new StatusResponseDto(
                    ServerDeviceId: localDevice.DeviceId,
                    ServerName: localDevice.DeviceName,
                    PairingCode: _localPairingCode,
                    ApiPort: localDevice.ApiPort,
                    DiscoveryPort: localDevice.DiscoveryPort,
                    LocalAddresses: localDevice.LocalIpAddresses),
                    localDevice: localDevice),
                context.RequestAborted);
        }
        catch (Exception ex)
        {
            await _loggerService.LogErrorAsync($"Connection status endpoint failed: {ex.Message}", context.RequestAborted);
            context.Response.StatusCode = StatusCodes.Status500InternalServerError;
            if (localDevice is not null)
            {
                await context.Response.WriteEnvelopeAsync(
                    CreateServerErrorEnvelope(
                        packetType: ProtocolPacketTypes.ConnectionStatus,
                        errorCode: ProtocolErrorCodes.InvalidRequest,
                        errorMessage: ex.Message,
                        payload: new StatusResponseDto(
                            ServerDeviceId: localDevice.DeviceId,
                            ServerName: localDevice.DeviceName,
                            PairingCode: _localPairingCode,
                            ApiPort: localDevice.ApiPort,
                            DiscoveryPort: localDevice.DiscoveryPort,
                            LocalAddresses: localDevice.LocalIpAddresses),
                        localDevice: localDevice),
                    context.RequestAborted);
            }
        }
    }

    private async Task HandleHandshakeAsync(HttpContext context)
    {
        var requestEnvelope = await context.Request.ReadEnvelopeAsync<ConnectionHandshakeRequestDto>(context.RequestAborted);
        var localDevice = await EnsureLocalDeviceAsync(context.RequestAborted);
        var correlationId = requestEnvelope?.Meta.MessageId;
        var validation = ProtocolEnvelopeValidator.Validate(
            requestEnvelope,
            expectedPacketTypes: [ProtocolPacketTypes.ConnectionHandshakeRequest]);
        var request = requestEnvelope?.Payload;

        if (!validation.IsValid)
        {
            context.Response.StatusCode = GetProtocolValidationStatusCode(validation);
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ConnectionHandshakeResponse,
                    errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidRequest,
                    errorMessage: validation.ErrorMessage ?? "Handshake request is malformed.",
                    payload: CreateHandshakeFailureResponse(
                        localDevice,
                        ProtocolConstants.SessionStateFailed,
                        validation.ErrorCode ?? ProtocolErrorCodes.InvalidRequest),
                    localDevice: localDevice,
                    receiverDeviceId: request?.DeviceId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        request = requestEnvelope!.Payload!;

        if (_boundState?.CurrentMode == AppConnectionMode.BluetoothFallback)
        {
            context.Response.StatusCode = StatusCodes.Status409Conflict;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ConnectionHandshakeResponse,
                    errorCode: ProtocolErrorCodes.NotConnected,
                    errorMessage: "Local Wi-Fi / Hotspot mode is disabled on this host right now.",
                    payload: CreateHandshakeFailureResponse(localDevice, ProtocolConstants.SessionStateFailed, ProtocolErrorCodes.NotConnected),
                    localDevice: localDevice,
                    receiverDeviceId: request.DeviceId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        if (string.IsNullOrWhiteSpace(request.DeviceId) ||
            string.IsNullOrWhiteSpace(request.DeviceName) ||
            string.IsNullOrWhiteSpace(request.Platform) ||
            string.IsNullOrWhiteSpace(request.AppVersion))
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ConnectionHandshakeResponse,
                    errorCode: ProtocolErrorCodes.InvalidRequest,
                    errorMessage: "Handshake request is missing required fields.",
                    payload: CreateHandshakeFailureResponse(localDevice, ProtocolConstants.SessionStateFailed, ProtocolErrorCodes.InvalidRequest),
                    localDevice: localDevice,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        if (string.Equals(request.DeviceId, localDevice.DeviceId, StringComparison.OrdinalIgnoreCase))
        {
            context.Response.StatusCode = StatusCodes.Status409Conflict;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ConnectionHandshakeResponse,
                    errorCode: ProtocolErrorCodes.SelfConnectionNotAllowed,
                    errorMessage: "A device cannot pair with itself.",
                    payload: CreateHandshakeFailureResponse(localDevice, ProtocolConstants.SessionStateFailed, ProtocolErrorCodes.SelfConnectionNotAllowed),
                    localDevice: localDevice,
                    receiverDeviceId: request.DeviceId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        if (string.IsNullOrWhiteSpace(request.PairingToken))
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ConnectionHandshakeResponse,
                    errorCode: ProtocolErrorCodes.PairingTokenRequired,
                    errorMessage: "The pairing token is required for the first connection.",
                    payload: CreateHandshakeFailureResponse(localDevice, ProtocolConstants.SessionStateWaitingForPairing, ProtocolErrorCodes.PairingTokenRequired),
                    localDevice: localDevice,
                    receiverDeviceId: request.DeviceId,
                    correlationId: correlationId),
                context.RequestAborted);
            await _loggerService.LogWarningAsync($"[PAIRING] Handshake from {request.DeviceName} rejected because no pairing token was provided.", context.RequestAborted);
            return;
        }

        if (!string.Equals(request.PairingToken, _localPairingCode, StringComparison.Ordinal))
        {
            context.Response.StatusCode = StatusCodes.Status401Unauthorized;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ConnectionHandshakeResponse,
                    errorCode: ProtocolErrorCodes.InvalidPairingToken,
                    errorMessage: "The supplied pairing token is not valid on this host.",
                    payload: CreateHandshakeFailureResponse(localDevice, ProtocolConstants.SessionStateWaitingForPairing, ProtocolErrorCodes.InvalidPairingToken),
                    localDevice: localDevice,
                    receiverDeviceId: request.DeviceId,
                    correlationId: correlationId),
                context.RequestAborted);
            await _loggerService.LogWarningAsync($"[PAIRING] Handshake from {request.DeviceName} rejected because the pairing token was invalid.", context.RequestAborted);
            return;
        }

        var sessionId = Guid.NewGuid().ToString("N");
        var session = new IncomingConnectionSession(
            sessionId: sessionId,
            peer: new DevicePeer
            {
                Id = request.DeviceId,
                DisplayName = request.DeviceName,
                Platform = request.Platform,
                IpAddress = NormalizeIpAddress(context.Connection.RemoteIpAddress),
                Port = AppConstants.DefaultApiPort,
                BluetoothAddress = string.Empty,
                AppVersion = request.AppVersion,
                SupportedModes = request.SupportedModes.ToArray(),
                TransportMode = AppConnectionMode.LocalLan,
                IsTrusted = true,
                IsOnline = true,
                FirstSeenAtUtc = DateTimeOffset.UtcNow,
                LastSeenAtUtc = DateTimeOffset.UtcNow,
                HasResolvedIdentity = true
            },
            createdAtUtc: DateTimeOffset.UtcNow,
            lastHeartbeatUtc: DateTimeOffset.UtcNow);

        lock (_incomingSessionsGate)
        {
            foreach (var existing in _incomingSessions.Values.Where(existing => existing.Peer.Id == request.DeviceId).Select(existing => existing.SessionId).ToList())
            {
                _incomingSessions.Remove(existing);
            }

            _incomingSessions[session.SessionId] = session;
        }

        await _trustedDevicesService.TrustDeviceAsync(session.Peer, context.RequestAborted);

        if (_activeConnection is null || string.Equals(_activeConnection.Peer.Id, session.Peer.Id, StringComparison.OrdinalIgnoreCase))
        {
            _activeConnection = new ActiveConnection(session.SessionId, ClonePeer(session.Peer), string.Empty, IsIncoming: true);

            if (_boundState is not null)
            {
                await UpdateStateAsync(_boundState, state =>
                {
                    state.ActivePeerName = session.Peer.DisplayName;
                    state.ActivePeerId = session.Peer.Id;
                    state.SessionId = session.SessionId;
                    state.LifecycleState = ConnectionLifecycleState.Paired;
                    state.StatusText = $"Accepted local handshake from {session.Peer.DisplayName}.";
                    state.HandshakeSummary = $"Incoming session {session.SessionId} paired successfully.";
                    state.IsConnected = false;
                    state.LastFailure = string.Empty;
                });
            }
        }

        await _loggerService.LogInfoAsync($"Accepted handshake from {session.Peer.DisplayName}. Session {session.SessionId} created.", context.RequestAborted);

        await context.Response.WriteEnvelopeAsync(
            CreateServerEnvelope(
                packetType: ProtocolPacketTypes.ConnectionHandshakeResponse,
                payload: new ConnectionHandshakeResponseDto(
                Accepted: true,
                SessionState: ProtocolConstants.SessionStatePaired,
                SessionId: session.SessionId,
                FailureReason: null,
                ServerDeviceId: localDevice.DeviceId,
                ServerDeviceName: localDevice.DeviceName,
                ServerPlatform: localDevice.Platform,
                ServerAppVersion: localDevice.AppVersion,
                SupportedModes: localDevice.SupportedModes,
                IssuedAtUtc: DateTimeOffset.UtcNow),
                localDevice: localDevice,
                receiverDeviceId: request.DeviceId,
                sessionId: session.SessionId,
                correlationId: correlationId),
            context.RequestAborted);
    }

    private async Task HandleHeartbeatAsync(HttpContext context)
    {
        var requestEnvelope = await context.Request.ReadEnvelopeAsync<ConnectionHeartbeatRequestDto>(context.RequestAborted);
        var localDevice = await EnsureLocalDeviceAsync(context.RequestAborted);
        var correlationId = requestEnvelope?.Meta.MessageId;
        var validation = ProtocolEnvelopeValidator.Validate(
            requestEnvelope,
            expectedPacketTypes: [ProtocolPacketTypes.ConnectionHeartbeatRequest]);
        var request = requestEnvelope?.Payload;

        if (!validation.IsValid)
        {
            context.Response.StatusCode = GetProtocolValidationStatusCode(validation);
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ConnectionHeartbeatResponse,
                    errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidRequest,
                    errorMessage: validation.ErrorMessage ?? "Heartbeat request is malformed.",
                    payload: new ConnectionHeartbeatResponseDto(
                        Alive: false,
                        SessionState: ProtocolConstants.SessionStateFailed,
                        FailureReason: validation.ErrorCode ?? ProtocolErrorCodes.InvalidRequest,
                        ServerDeviceId: localDevice.DeviceId,
                        ServerDeviceName: localDevice.DeviceName,
                        ServerPlatform: localDevice.Platform,
                        ServerAppVersion: localDevice.AppVersion,
                        ReceivedAtUtc: DateTimeOffset.UtcNow),
                    localDevice: localDevice,
                    receiverDeviceId: request?.DeviceId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        request = requestEnvelope!.Payload!;

        if (string.IsNullOrWhiteSpace(request.SessionId) || string.IsNullOrWhiteSpace(request.DeviceId))
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ConnectionHeartbeatResponse,
                    errorCode: ProtocolErrorCodes.InvalidRequest,
                    errorMessage: "Heartbeat request is missing a valid session or device identifier.",
                    payload: new ConnectionHeartbeatResponseDto(
                    Alive: false,
                    SessionState: ProtocolConstants.SessionStateFailed,
                    FailureReason: ProtocolErrorCodes.InvalidRequest,
                    ServerDeviceId: localDevice.DeviceId,
                    ServerDeviceName: localDevice.DeviceName,
                    ServerPlatform: localDevice.Platform,
                    ServerAppVersion: localDevice.AppVersion,
                    ReceivedAtUtc: DateTimeOffset.UtcNow),
                    localDevice: localDevice,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        IncomingConnectionSession? session;
        lock (_incomingSessionsGate)
        {
            _incomingSessions.TryGetValue(request.SessionId, out session);

            if (session is not null)
            {
                session.Peer.LastSeenAtUtc = DateTimeOffset.UtcNow;
                session.LastHeartbeatUtc = DateTimeOffset.UtcNow;
            }
        }

        if (session is null || !string.Equals(session.Peer.Id, request.DeviceId, StringComparison.OrdinalIgnoreCase))
        {
            context.Response.StatusCode = StatusCodes.Status404NotFound;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ConnectionHeartbeatResponse,
                    errorCode: ProtocolErrorCodes.SessionNotFound,
                    errorMessage: "The requested session is not active on this host.",
                    payload: new ConnectionHeartbeatResponseDto(
                    Alive: false,
                    SessionState: ProtocolConstants.SessionStateDisconnected,
                    FailureReason: ProtocolErrorCodes.SessionNotFound,
                    ServerDeviceId: localDevice.DeviceId,
                    ServerDeviceName: localDevice.DeviceName,
                    ServerPlatform: localDevice.Platform,
                    ServerAppVersion: localDevice.AppVersion,
                    ReceivedAtUtc: DateTimeOffset.UtcNow),
                    localDevice: localDevice,
                    receiverDeviceId: request.DeviceId,
                    sessionId: request.SessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        if (_boundState is not null && (_activeConnection is null || string.Equals(_activeConnection.Peer.Id, session.Peer.Id, StringComparison.OrdinalIgnoreCase)))
        {
            _activeConnection = new ActiveConnection(session.SessionId, ClonePeer(session.Peer), string.Empty, IsIncoming: true);

            await UpdateStateAsync(_boundState, state =>
            {
                state.ActivePeerName = session.Peer.DisplayName;
                state.ActivePeerId = session.Peer.Id;
                state.SessionId = session.SessionId;
                state.IsConnected = true;
                state.LifecycleState = ConnectionLifecycleState.Connected;
                state.StatusText = $"Connected with {session.Peer.DisplayName}.";
                state.HandshakeSummary = $"Heartbeat validated incoming session {session.SessionId}.";
                state.ActiveTransportLabel = "Connected via Local Wi-Fi / Hotspot";
            });

            await NotifySessionChangedAsync(context.RequestAborted);
        }

        await _loggerService.LogInfoAsync($"[SESSION] Heartbeat accepted for session {session.SessionId} from {session.Peer.DisplayName}.", context.RequestAborted);

        await context.Response.WriteEnvelopeAsync(
            CreateServerEnvelope(
                packetType: ProtocolPacketTypes.ConnectionHeartbeatResponse,
                payload: new ConnectionHeartbeatResponseDto(
                Alive: true,
                SessionState: ProtocolConstants.SessionStateConnected,
                FailureReason: null,
                ServerDeviceId: localDevice.DeviceId,
                ServerDeviceName: localDevice.DeviceName,
                ServerPlatform: localDevice.Platform,
                ServerAppVersion: localDevice.AppVersion,
                ReceivedAtUtc: DateTimeOffset.UtcNow),
                localDevice: localDevice,
                receiverDeviceId: request.DeviceId,
                sessionId: request.SessionId,
                correlationId: correlationId),
            context.RequestAborted);
    }

    private async Task HandleDisconnectAsync(HttpContext context)
    {
        var requestEnvelope = await context.Request.ReadEnvelopeAsync<ConnectionDisconnectRequestDto>(context.RequestAborted);
        var localDevice = await EnsureLocalDeviceAsync(context.RequestAborted);
        var validation = ProtocolEnvelopeValidator.Validate(
            requestEnvelope,
            expectedPacketTypes: [ProtocolPacketTypes.ConnectionDisconnectRequest]);
        var request = requestEnvelope?.Payload;

        if (!validation.IsValid)
        {
            context.Response.StatusCode = GetProtocolValidationStatusCode(validation);
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ConnectionDisconnectResponse,
                    errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidRequest,
                    errorMessage: validation.ErrorMessage ?? "Disconnect request is malformed.",
                    payload: new ConnectionDisconnectResponseDto(
                        Acknowledged: false,
                        SessionId: string.Empty,
                        ReceivedAtUtc: DateTimeOffset.UtcNow),
                    localDevice: localDevice,
                    receiverDeviceId: request?.DeviceId,
                    correlationId: requestEnvelope?.Meta.MessageId),
                context.RequestAborted);
            return;
        }

        request = requestEnvelope!.Payload!;

        if (string.IsNullOrWhiteSpace(request.SessionId) || string.IsNullOrWhiteSpace(request.DeviceId))
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ConnectionDisconnectResponse,
                    errorCode: ProtocolErrorCodes.InvalidRequest,
                    errorMessage: "Disconnect request is missing required fields.",
                    payload: new ConnectionDisconnectResponseDto(
                        Acknowledged: false,
                        SessionId: string.Empty,
                        ReceivedAtUtc: DateTimeOffset.UtcNow),
                    localDevice: localDevice,
                    receiverDeviceId: request.DeviceId,
                    correlationId: requestEnvelope.Meta.MessageId),
                context.RequestAborted);
            return;
        }

        IncomingConnectionSession? removedSession = null;

        lock (_incomingSessionsGate)
        {
            if (_incomingSessions.TryGetValue(request.SessionId, out var existing) &&
                string.Equals(existing.Peer.Id, request.DeviceId, StringComparison.OrdinalIgnoreCase))
            {
                removedSession = existing;
                _incomingSessions.Remove(request.SessionId);
            }
        }

        if (removedSession is not null)
        {
            await _loggerService.LogInfoAsync($"Remote peer {removedSession.Peer.DisplayName} closed session {removedSession.SessionId}.", context.RequestAborted);

            if (_boundState is not null && string.Equals(_boundState.SessionId, removedSession.SessionId, StringComparison.OrdinalIgnoreCase))
            {
                await UpdateStateAsync(_boundState, state =>
                {
                    state.IsConnected = false;
                    state.LifecycleState = ConnectionLifecycleState.Disconnected;
                    state.StatusText = $"{removedSession.Peer.DisplayName} disconnected from the local session.";
                    state.SessionId = string.Empty;
                    state.ActiveTransportLabel = BuildStandbyTransportLabel(state.CurrentMode);
                });
            }

            await NotifySessionChangedAsync(context.RequestAborted);
        }

        var currentLocalDevice = await EnsureLocalDeviceAsync(context.RequestAborted);
        context.Response.StatusCode = StatusCodes.Status200OK;
        await context.Response.WriteEnvelopeAsync(
            CreateServerEnvelope(
                packetType: ProtocolPacketTypes.ConnectionDisconnectResponse,
                payload: new ConnectionDisconnectResponseDto(
                    Acknowledged: true,
                    SessionId: request.SessionId,
                    ReceivedAtUtc: DateTimeOffset.UtcNow),
                localDevice: currentLocalDevice,
                receiverDeviceId: request.DeviceId,
                sessionId: request.SessionId,
                correlationId: requestEnvelope?.Meta.MessageId),
            context.RequestAborted);
    }

    private async Task HandleChatMessageAsync(HttpContext context)
    {
        var packetEnvelope = await context.Request.ReadEnvelopeAsync<TextChatPacketDto>(context.RequestAborted);
        var localDevice = await EnsureLocalDeviceAsync(context.RequestAborted);
        var correlationId = packetEnvelope?.Meta.MessageId;
        var validation = ProtocolEnvelopeValidator.Validate(
            packetEnvelope,
            expectedPacketTypes: [ProtocolPacketTypes.ChatTextMessage]);
        var packet = packetEnvelope?.Payload;

        if (!validation.IsValid)
        {
            context.Response.StatusCode = GetProtocolValidationStatusCode(validation);
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ChatDeliveryReceipt,
                    errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidRequest,
                    errorMessage: validation.ErrorMessage ?? "Chat packet is malformed.",
                    payload: CreateChatFailureReceipt(packet?.Id ?? string.Empty, validation.ErrorCode ?? ProtocolErrorCodes.InvalidRequest, localDevice),
                    localDevice: localDevice,
                    receiverDeviceId: packet?.SenderId,
                    sessionId: packet?.SessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        packet = packetEnvelope!.Payload!;

        if (string.IsNullOrWhiteSpace(packet.Id) ||
            string.IsNullOrWhiteSpace(packet.SessionId) ||
            string.IsNullOrWhiteSpace(packet.SenderId) ||
            string.IsNullOrWhiteSpace(packet.Text))
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ChatDeliveryReceipt,
                    errorCode: ProtocolErrorCodes.InvalidRequest,
                    errorMessage: "Chat packet is missing required fields.",
                    payload: CreateChatFailureReceipt(packet?.Id ?? string.Empty, ProtocolErrorCodes.InvalidRequest, localDevice),
                    localDevice: localDevice,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        if (!string.IsNullOrWhiteSpace(packet.ReceiverId) &&
            !string.Equals(packet.ReceiverId, localDevice.DeviceId, StringComparison.OrdinalIgnoreCase))
        {
            context.Response.StatusCode = StatusCodes.Status409Conflict;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ChatDeliveryReceipt,
                    errorCode: ProtocolErrorCodes.WrongReceiver,
                    errorMessage: "The receiver id does not match this host.",
                    payload: CreateChatFailureReceipt(packet.Id, ProtocolErrorCodes.WrongReceiver, localDevice),
                    localDevice: localDevice,
                    receiverDeviceId: packet.SenderId,
                    sessionId: packet.SessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        var activeConnection = _activeConnection;
        var sessionMatchesActive = activeConnection is not null &&
                                   string.Equals(activeConnection.SessionId, packet.SessionId, StringComparison.OrdinalIgnoreCase) &&
                                   string.Equals(activeConnection.Peer.Id, packet.SenderId, StringComparison.OrdinalIgnoreCase);

        IncomingConnectionSession? incomingSession = null;
        lock (_incomingSessionsGate)
        {
            if (_incomingSessions.TryGetValue(packet.SessionId, out var existing) &&
                string.Equals(existing.Peer.Id, packet.SenderId, StringComparison.OrdinalIgnoreCase))
            {
                incomingSession = existing;
                existing.LastHeartbeatUtc = DateTimeOffset.UtcNow;
                existing.Peer.LastSeenAtUtc = DateTimeOffset.UtcNow;
            }
        }

        if (!sessionMatchesActive && incomingSession is null)
        {
            context.Response.StatusCode = StatusCodes.Status404NotFound;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.ChatDeliveryReceipt,
                    errorCode: ProtocolErrorCodes.SessionNotFound,
                    errorMessage: "The chat session is not active on this host.",
                    payload: CreateChatFailureReceipt(packet.Id, ProtocolErrorCodes.SessionNotFound, localDevice),
                    localDevice: localDevice,
                    receiverDeviceId: packet.SenderId,
                    sessionId: packet.SessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        try
        {
            ChatMessageReceived?.Invoke(packet);
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync($"Chat message event dispatch failed: {ex.Message}", context.RequestAborted);
        }

        await _loggerService.LogInfoAsync(
            $"Accepted chat message {packet.Id} from {packet.SenderName}.",
            context.RequestAborted);

        await context.Response.WriteEnvelopeAsync(
            CreateServerEnvelope(
                packetType: ProtocolPacketTypes.ChatDeliveryReceipt,
                payload: new TextChatDeliveryReceiptDto(
                Accepted: true,
                MessageId: packet.Id,
                Status: ProtocolConstants.DeliveryStatusDelivered,
                FailureReason: null,
                ReceiverDeviceId: localDevice.DeviceId,
                ReceiverDeviceName: localDevice.DeviceName,
                ReceivedAtUtc: DateTimeOffset.UtcNow),
                localDevice: localDevice,
                receiverDeviceId: packet.SenderId,
                sessionId: packet.SessionId,
                correlationId: correlationId),
                context.RequestAborted);
    }

    private async Task HandleTransferPrepareAsync(HttpContext context)
    {
        var requestEnvelope = await context.Request.ReadEnvelopeAsync<FileTransferPrepareRequestDto>(context.RequestAborted);
        var request = requestEnvelope?.Payload;
        var localDevice = await EnsureLocalDeviceAsync(context.RequestAborted);
        var correlationId = requestEnvelope?.Meta.MessageId;

        if (_fileTransferHandler is null)
        {
            context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferPrepareResponse,
                    errorCode: ProtocolErrorCodes.TransferServiceUnavailable,
                    errorMessage: "The transfer service is not available.",
                    payload: CreateTransferPrepareFailureResponse(request?.TransferId ?? string.Empty, ProtocolErrorCodes.TransferServiceUnavailable, localDevice),
                    localDevice: localDevice,
                    receiverDeviceId: request?.SenderId,
                    sessionId: request?.SessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        var validation = ProtocolEnvelopeValidator.Validate(
            requestEnvelope,
            expectedPacketTypes: [ProtocolPacketTypes.TransferPrepareRequest]);

        if (!validation.IsValid)
        {
            context.Response.StatusCode = GetProtocolValidationStatusCode(validation);
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferPrepareResponse,
                    errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferPrepare,
                    errorMessage: validation.ErrorMessage ?? "Transfer prepare request is malformed.",
                    payload: CreateTransferPrepareFailureResponse(request?.TransferId ?? string.Empty, validation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferPrepare, localDevice),
                    localDevice: localDevice,
                    receiverDeviceId: request?.SenderId,
                    sessionId: request?.SessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        request = requestEnvelope!.Payload!;

        if (string.IsNullOrWhiteSpace(request.TransferId) ||
            string.IsNullOrWhiteSpace(request.SessionId) ||
            string.IsNullOrWhiteSpace(request.SenderId) ||
            string.IsNullOrWhiteSpace(request.FileName) ||
            request.FileSize <= 0 ||
            request.ChunkSize <= 0 ||
            request.TotalChunks <= 0)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferPrepareResponse,
                    errorCode: ProtocolErrorCodes.InvalidTransferPrepare,
                    errorMessage: "Transfer prepare request is missing required metadata.",
                    payload: CreateTransferPrepareFailureResponse(request?.TransferId ?? string.Empty, ProtocolErrorCodes.InvalidTransferPrepare, localDevice),
                    localDevice: localDevice,
                    receiverDeviceId: request?.SenderId,
                    sessionId: request?.SessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }


        var session = await ResolveSessionSnapshotAsync(request.SessionId, request.SenderId, context.RequestAborted);
        if (session is null)
        {
            context.Response.StatusCode = StatusCodes.Status404NotFound;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferPrepareResponse,
                    errorCode: ProtocolErrorCodes.SessionNotFound,
                    errorMessage: "The transfer session is not active on this host.",
                    payload: CreateTransferPrepareFailureResponse(request.TransferId, ProtocolErrorCodes.SessionNotFound, localDevice),
                    localDevice: localDevice,
                    receiverDeviceId: request.SenderId,
                    sessionId: request.SessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        try
        {
            var response = await _fileTransferHandler.PrepareIncomingTransferAsync(request, session, context.RequestAborted);
            context.Response.StatusCode = response.Accepted ? StatusCodes.Status200OK : StatusCodes.Status409Conflict;
            var envelope = response.Accepted
                ? CreateServerEnvelope(
                    packetType: ProtocolPacketTypes.TransferPrepareResponse,
                    payload: response,
                    localDevice: localDevice,
                    receiverDeviceId: request.SenderId,
                    sessionId: request.SessionId,
                    correlationId: correlationId)
                : CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferPrepareResponse,
                    errorCode: response.FailureReason ?? ProtocolErrorCodes.InvalidTransferPrepare,
                    errorMessage: response.FailureReason ?? "Transfer prepare failed.",
                    payload: response,
                    localDevice: localDevice,
                    receiverDeviceId: request.SenderId,
                    sessionId: request.SessionId,
                    correlationId: correlationId);
            await context.Response.WriteEnvelopeAsync(envelope, context.RequestAborted);
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync($"Transfer prepare failed: {ex.Message}", context.RequestAborted);
            context.Response.StatusCode = StatusCodes.Status500InternalServerError;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferPrepareResponse,
                    errorCode: ProtocolErrorCodes.InvalidTransferPrepare,
                    errorMessage: ex.Message,
                    payload: CreateTransferPrepareFailureResponse(request.TransferId, ex.Message, localDevice),
                    localDevice: localDevice,
                    receiverDeviceId: request.SenderId,
                    sessionId: request.SessionId,
                    correlationId: correlationId),
                context.RequestAborted);
        }
    }

    private async Task HandleTransferChunkAsync(HttpContext context)
    {
        var localDevice = await EnsureLocalDeviceAsync(context.RequestAborted);

        if (_fileTransferHandler is null)
        {
            context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferChunkResponse,
                    errorCode: ProtocolErrorCodes.TransferServiceUnavailable,
                    errorMessage: "The transfer service is not available.",
                    payload: CreateTransferChunkFailureResponse(string.Empty, -1, ProtocolErrorCodes.TransferServiceUnavailable),
                    localDevice: localDevice),
                context.RequestAborted);
            return;
        }

        var transferId = string.Empty;
        var sessionId = string.Empty;
        var senderId = string.Empty;
        var chunkIndex = -1;
        var chunkOffset = -1L;
        var chunkLength = -1;
        var correlationId = string.Empty;
        ProtocolEnvelope<FileTransferChunkDescriptorDto>? descriptorEnvelope = null;
        IFormFile? chunkFile = null;

        if (!context.Request.HasFormContentType)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferChunkResponse,
                    errorCode: ProtocolErrorCodes.InvalidTransferChunk,
                    errorMessage: "Transfer chunk requests must use multipart/form-data.",
                    payload: CreateTransferChunkFailureResponse(string.Empty, -1, ProtocolErrorCodes.InvalidTransferChunk),
                    localDevice: localDevice),
                context.RequestAborted);
            return;
        }

        try
        {
            var form = await context.Request.ReadFormAsync(context.RequestAborted);
            var metadataJson = form[ProtocolConstants.MultipartMetadataPartName].ToString();
            chunkFile = form.Files.GetFile(ProtocolConstants.MultipartBinaryPartName);
            descriptorEnvelope = string.IsNullOrWhiteSpace(metadataJson)
                ? null
                : System.Text.Json.JsonSerializer.Deserialize<ProtocolEnvelope<FileTransferChunkDescriptorDto>>(metadataJson, JsonDefaults.Options);

            var descriptorPayload = descriptorEnvelope?.Payload;
            transferId = descriptorPayload?.TransferId ?? string.Empty;
            sessionId = descriptorPayload?.SessionId ?? string.Empty;
            senderId = descriptorPayload?.SenderId ?? string.Empty;
            chunkIndex = descriptorPayload?.ChunkIndex ?? -1;
            chunkOffset = descriptorPayload?.ChunkOffset ?? -1L;
            chunkLength = descriptorPayload?.ChunkLength ?? -1;
            correlationId = descriptorEnvelope?.Meta.MessageId ?? string.Empty;
        }
        catch (Exception ex)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferChunkResponse,
                    errorCode: ProtocolErrorCodes.InvalidTransferChunk,
                    errorMessage: ex.Message,
                    payload: CreateTransferChunkFailureResponse(string.Empty, -1, ProtocolErrorCodes.InvalidTransferChunk),
                    localDevice: localDevice),
                context.RequestAborted);
            return;
        }

        var descriptorValidation = ProtocolEnvelopeValidator.Validate(
            descriptorEnvelope,
            expectedPacketTypes: [ProtocolPacketTypes.TransferChunkRequest]);

        if (!descriptorValidation.IsValid)
        {
            context.Response.StatusCode = GetProtocolValidationStatusCode(descriptorValidation);
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferChunkResponse,
                    errorCode: descriptorValidation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferChunk,
                    errorMessage: descriptorValidation.ErrorMessage ?? "Chunk metadata is malformed.",
                    payload: CreateTransferChunkFailureResponse(transferId, chunkIndex, descriptorValidation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferChunk),
                    localDevice: localDevice,
                    receiverDeviceId: senderId,
                    sessionId: sessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        if (chunkFile is null ||
            string.IsNullOrWhiteSpace(transferId) ||
            string.IsNullOrWhiteSpace(sessionId) ||
            string.IsNullOrWhiteSpace(senderId) ||
            chunkIndex < 0 ||
            chunkOffset < 0 ||
            chunkLength <= 0)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferChunkResponse,
                    errorCode: ProtocolErrorCodes.InvalidTransferChunk,
                    errorMessage: "Chunk metadata or binary content is missing.",
                    payload: CreateTransferChunkFailureResponse(transferId, chunkIndex, ProtocolErrorCodes.InvalidTransferChunk),
                    localDevice: localDevice,
                    receiverDeviceId: senderId,
                    sessionId: sessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        var session = await ResolveSessionSnapshotAsync(sessionId, senderId, context.RequestAborted);
        if (session is null)
        {
            context.Response.StatusCode = StatusCodes.Status404NotFound;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferChunkResponse,
                    errorCode: ProtocolErrorCodes.SessionNotFound,
                    errorMessage: "The transfer session is not active on this host.",
                    payload: CreateTransferChunkFailureResponse(transferId, chunkIndex, ProtocolErrorCodes.SessionNotFound),
                    localDevice: localDevice,
                    receiverDeviceId: senderId,
                    sessionId: sessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        var descriptor = descriptorEnvelope!.Payload!;

        try
        {
            await using var chunkStream = chunkFile.OpenReadStream();
            var response = await _fileTransferHandler.ReceiveChunkAsync(descriptor, chunkStream, session, context.RequestAborted);
            context.Response.StatusCode = response.Accepted ? StatusCodes.Status200OK : StatusCodes.Status409Conflict;
            var envelope = response.Accepted
                ? CreateServerEnvelope(
                    packetType: ProtocolPacketTypes.TransferChunkResponse,
                    payload: response,
                    localDevice: localDevice,
                    receiverDeviceId: senderId,
                    sessionId: sessionId,
                    correlationId: correlationId)
                : CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferChunkResponse,
                    errorCode: response.FailureReason ?? ProtocolErrorCodes.InvalidTransferChunk,
                    errorMessage: response.FailureReason ?? "Transfer chunk was rejected.",
                    payload: response,
                    localDevice: localDevice,
                    receiverDeviceId: senderId,
                    sessionId: sessionId,
                    correlationId: correlationId);
            await context.Response.WriteEnvelopeAsync(envelope, context.RequestAborted);
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync($"Transfer chunk failed: {ex.Message}", context.RequestAborted);
            context.Response.StatusCode = StatusCodes.Status500InternalServerError;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferChunkResponse,
                    errorCode: ProtocolErrorCodes.InvalidTransferChunk,
                    errorMessage: ex.Message,
                    payload: CreateTransferChunkFailureResponse(transferId, chunkIndex, ex.Message),
                    localDevice: localDevice,
                    receiverDeviceId: senderId,
                    sessionId: sessionId,
                    correlationId: correlationId),
                context.RequestAborted);
        }
    }

    private async Task HandleTransferCompleteAsync(HttpContext context)
    {
        var requestEnvelope = await context.Request.ReadEnvelopeAsync<FileTransferCompleteRequestDto>(context.RequestAborted);
        var request = requestEnvelope?.Payload;
        var localDevice = await EnsureLocalDeviceAsync(context.RequestAborted);
        var correlationId = requestEnvelope?.Meta.MessageId;

        if (_fileTransferHandler is null)
        {
            context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferCompleteResponse,
                    errorCode: ProtocolErrorCodes.TransferServiceUnavailable,
                    errorMessage: "The transfer service is not available.",
                    payload: CreateTransferCompleteFailureResponse(request?.TransferId ?? string.Empty, ProtocolErrorCodes.TransferServiceUnavailable),
                    localDevice: localDevice,
                    receiverDeviceId: request?.SenderId,
                    sessionId: request?.SessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        var validation = ProtocolEnvelopeValidator.Validate(
            requestEnvelope,
            expectedPacketTypes: [ProtocolPacketTypes.TransferCompleteRequest]);

        if (!validation.IsValid)
        {
            context.Response.StatusCode = GetProtocolValidationStatusCode(validation);
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferCompleteResponse,
                    errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferComplete,
                    errorMessage: validation.ErrorMessage ?? "Transfer completion request is malformed.",
                    payload: CreateTransferCompleteFailureResponse(request?.TransferId ?? string.Empty, validation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferComplete),
                    localDevice: localDevice,
                    receiverDeviceId: request?.SenderId,
                    sessionId: request?.SessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        request = requestEnvelope!.Payload!;

        if (string.IsNullOrWhiteSpace(request.TransferId) ||
            string.IsNullOrWhiteSpace(request.SessionId) ||
            string.IsNullOrWhiteSpace(request.SenderId))
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferCompleteResponse,
                    errorCode: ProtocolErrorCodes.InvalidTransferComplete,
                    errorMessage: "Transfer completion request is missing required metadata.",
                    payload: CreateTransferCompleteFailureResponse(request?.TransferId ?? string.Empty, ProtocolErrorCodes.InvalidTransferComplete),
                    localDevice: localDevice,
                    receiverDeviceId: request?.SenderId,
                    sessionId: request?.SessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        var session = await ResolveSessionSnapshotAsync(request.SessionId, request.SenderId, context.RequestAborted);
        if (session is null)
        {
            context.Response.StatusCode = StatusCodes.Status404NotFound;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferCompleteResponse,
                    errorCode: ProtocolErrorCodes.SessionNotFound,
                    errorMessage: "The transfer session is not active on this host.",
                    payload: CreateTransferCompleteFailureResponse(request.TransferId, ProtocolErrorCodes.SessionNotFound),
                    localDevice: localDevice,
                    receiverDeviceId: request.SenderId,
                    sessionId: request.SessionId,
                    correlationId: correlationId),
                context.RequestAborted);
            return;
        }

        try
        {
            var response = await _fileTransferHandler.CompleteIncomingTransferAsync(request, session, context.RequestAborted);
            context.Response.StatusCode = response.Accepted ? StatusCodes.Status200OK : StatusCodes.Status409Conflict;
            var envelope = response.Accepted
                ? CreateServerEnvelope(
                    packetType: ProtocolPacketTypes.TransferCompleteResponse,
                    payload: response,
                    localDevice: localDevice,
                    receiverDeviceId: request.SenderId,
                    sessionId: request.SessionId,
                    correlationId: correlationId)
                : CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferCompleteResponse,
                    errorCode: response.FailureReason ?? ProtocolErrorCodes.InvalidTransferComplete,
                    errorMessage: response.FailureReason ?? "Transfer completion was rejected.",
                    payload: response,
                    localDevice: localDevice,
                    receiverDeviceId: request.SenderId,
                    sessionId: request.SessionId,
                    correlationId: correlationId);
            await context.Response.WriteEnvelopeAsync(envelope, context.RequestAborted);
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync($"Transfer completion failed: {ex.Message}", context.RequestAborted);
            context.Response.StatusCode = StatusCodes.Status500InternalServerError;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferCompleteResponse,
                    errorCode: ProtocolErrorCodes.InvalidTransferComplete,
                    errorMessage: ex.Message,
                    payload: CreateTransferCompleteFailureResponse(request.TransferId, ex.Message),
                    localDevice: localDevice,
                    receiverDeviceId: request.SenderId,
                    sessionId: request.SessionId,
                    correlationId: correlationId),
                context.RequestAborted);
        }
    }

    private async Task HandleTransferCancelAsync(HttpContext context)
    {
        var requestEnvelope = await context.Request.ReadEnvelopeAsync<FileTransferCancelRequestDto>(context.RequestAborted);
        var request = requestEnvelope?.Payload;
        var localDevice = await EnsureLocalDeviceAsync(context.RequestAborted);

        if (_fileTransferHandler is null)
        {
            context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferCancelResponse,
                    errorCode: ProtocolErrorCodes.TransferServiceUnavailable,
                    errorMessage: "The transfer service is not available.",
                    payload: CreateTransferCancelFailureResponse(request?.TransferId ?? string.Empty, ProtocolErrorCodes.TransferServiceUnavailable),
                    localDevice: localDevice,
                    receiverDeviceId: request?.SenderId,
                    sessionId: request?.SessionId,
                    correlationId: requestEnvelope?.Meta.MessageId),
                context.RequestAborted);
            return;
        }

        var validation = ProtocolEnvelopeValidator.Validate(
            requestEnvelope,
            expectedPacketTypes: [ProtocolPacketTypes.TransferCancelRequest]);

        if (!validation.IsValid)
        {
            context.Response.StatusCode = GetProtocolValidationStatusCode(validation);
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferCancelResponse,
                    errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferCancel,
                    errorMessage: validation.ErrorMessage ?? "Transfer cancel request is malformed.",
                    payload: CreateTransferCancelFailureResponse(request?.TransferId ?? string.Empty, validation.ErrorCode ?? ProtocolErrorCodes.InvalidTransferCancel),
                    localDevice: localDevice,
                    receiverDeviceId: request?.SenderId,
                    sessionId: request?.SessionId,
                    correlationId: requestEnvelope?.Meta.MessageId),
                context.RequestAborted);
            return;
        }

        request = requestEnvelope!.Payload!;

        if (string.IsNullOrWhiteSpace(request.TransferId) ||
            string.IsNullOrWhiteSpace(request.SessionId) ||
            string.IsNullOrWhiteSpace(request.SenderId))
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferCancelResponse,
                    errorCode: ProtocolErrorCodes.InvalidTransferCancel,
                    errorMessage: "Transfer cancel request is missing required metadata.",
                    payload: CreateTransferCancelFailureResponse(request.TransferId, ProtocolErrorCodes.InvalidTransferCancel),
                    localDevice: localDevice,
                    receiverDeviceId: request.SenderId,
                    sessionId: request.SessionId,
                    correlationId: requestEnvelope.Meta.MessageId),
                context.RequestAborted);
            return;
        }

        var session = await ResolveSessionSnapshotAsync(request.SessionId, request.SenderId, context.RequestAborted);
        if (session is null)
        {
            context.Response.StatusCode = StatusCodes.Status404NotFound;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferCancelResponse,
                    errorCode: ProtocolErrorCodes.SessionNotFound,
                    errorMessage: "The transfer session is not active on this host.",
                    payload: CreateTransferCancelFailureResponse(request.TransferId, ProtocolErrorCodes.SessionNotFound),
                    localDevice: localDevice,
                    receiverDeviceId: request.SenderId,
                    sessionId: request.SessionId,
                    correlationId: requestEnvelope?.Meta.MessageId),
                context.RequestAborted);
            return;
        }

        try
        {
            var response = await _fileTransferHandler.CancelIncomingTransferAsync(request, session, context.RequestAborted);
            context.Response.StatusCode = response.Accepted ? StatusCodes.Status200OK : StatusCodes.Status409Conflict;
            var envelope = response.Accepted
                ? CreateServerEnvelope(
                    packetType: ProtocolPacketTypes.TransferCancelResponse,
                    payload: response,
                    localDevice: localDevice,
                    receiverDeviceId: request.SenderId,
                    sessionId: request.SessionId,
                    correlationId: requestEnvelope?.Meta.MessageId)
                : CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferCancelResponse,
                    errorCode: response.FailureReason ?? ProtocolErrorCodes.InvalidTransferCancel,
                    errorMessage: response.FailureReason ?? "Transfer cancel was rejected.",
                    payload: response,
                    localDevice: localDevice,
                    receiverDeviceId: request.SenderId,
                    sessionId: request.SessionId,
                    correlationId: requestEnvelope?.Meta.MessageId);
            await context.Response.WriteEnvelopeAsync(envelope, context.RequestAborted);
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync($"Transfer cancel failed: {ex.Message}", context.RequestAborted);
            context.Response.StatusCode = StatusCodes.Status500InternalServerError;
            await context.Response.WriteEnvelopeAsync(
                CreateServerErrorEnvelope(
                    packetType: ProtocolPacketTypes.TransferCancelResponse,
                    errorCode: ProtocolErrorCodes.InvalidTransferCancel,
                    errorMessage: ex.Message,
                    payload: CreateTransferCancelFailureResponse(request.TransferId, ex.Message),
                    localDevice: localDevice,
                    receiverDeviceId: request.SenderId,
                    sessionId: request.SessionId,
                    correlationId: requestEnvelope?.Meta.MessageId),
                context.RequestAborted);
        }
    }

    private void StartIncomingSessionCleanupLoop()
    {
        if (_sessionCleanupTask is { IsCompleted: false })
        {
            return;
        }

        _sessionCleanupCts?.Cancel();
        _sessionCleanupCts?.Dispose();
        _sessionCleanupCts = new CancellationTokenSource();

        var token = _sessionCleanupCts.Token;
        _sessionCleanupTask = Task.Run(async () =>
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(TimeSpan.FromSeconds(AppConstants.ConnectionHeartbeatIntervalSeconds * 2), token);
                    await CleanupExpiredIncomingSessionsAsync(token);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception ex)
                {
                    await _loggerService.LogWarningAsync($"Connection session cleanup error: {ex.Message}", token);
                }
            }
        }, token);
    }

    private async Task CleanupExpiredIncomingSessionsAsync(CancellationToken cancellationToken)
    {
        var expirationThreshold = DateTimeOffset.UtcNow.AddSeconds(-(AppConstants.ConnectionHeartbeatIntervalSeconds * 3));
        List<IncomingConnectionSession> expiredSessions;

        lock (_incomingSessionsGate)
        {
            expiredSessions = _incomingSessions.Values
                .Where(session => session.LastHeartbeatUtc < expirationThreshold)
                .Select(session => new IncomingConnectionSession(
                    session.SessionId,
                    ClonePeer(session.Peer),
                    session.CreatedAtUtc,
                    session.LastHeartbeatUtc))
                .ToList();

            foreach (var expired in expiredSessions)
            {
                _incomingSessions.Remove(expired.SessionId);
            }
        }

        foreach (var expired in expiredSessions)
        {
            await _loggerService.LogWarningAsync(
                $"Connection session {expired.SessionId} with {expired.Peer.DisplayName} expired after heartbeat timeout.",
                cancellationToken);

            if (_boundState is not null && string.Equals(_boundState.SessionId, expired.SessionId, StringComparison.OrdinalIgnoreCase))
            {
                await UpdateStateAsync(_boundState, state =>
                {
                    state.IsConnected = false;
                    state.LifecycleState = ConnectionLifecycleState.Disconnected;
                    state.StatusText = $"Lost connection with {expired.Peer.DisplayName}.";
                    state.SessionId = string.Empty;
                    state.ActiveTransportLabel = BuildStandbyTransportLabel(state.CurrentMode);
                });
            }

            await NotifySessionChangedAsync(cancellationToken);
        }
    }

    private async Task<ConnectionSessionSnapshot?> ResolveSessionSnapshotAsync(
        string sessionId,
        string deviceId,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(sessionId) || string.IsNullOrWhiteSpace(deviceId))
        {
            return null;
        }

        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        var activeConnection = _activeConnection;

        if (activeConnection is not null &&
            string.Equals(activeConnection.SessionId, sessionId, StringComparison.OrdinalIgnoreCase) &&
            string.Equals(activeConnection.Peer.Id, deviceId, StringComparison.OrdinalIgnoreCase))
        {
            return new ConnectionSessionSnapshot
            {
                SessionId = activeConnection.SessionId,
                LocalDeviceId = localDevice.DeviceId,
                LocalDeviceName = localDevice.DeviceName,
                Peer = ClonePeer(activeConnection.Peer),
                IsConnected = _boundState?.IsConnected ?? true,
                IsIncoming = activeConnection.IsIncoming,
                TransportMode = AppConnectionMode.LocalLan
            };
        }

        lock (_incomingSessionsGate)
        {
            if (_incomingSessions.TryGetValue(sessionId, out var incomingSession) &&
                string.Equals(incomingSession.Peer.Id, deviceId, StringComparison.OrdinalIgnoreCase))
            {
                incomingSession.LastHeartbeatUtc = DateTimeOffset.UtcNow;
                incomingSession.Peer.LastSeenAtUtc = DateTimeOffset.UtcNow;

                return new ConnectionSessionSnapshot
                {
                    SessionId = incomingSession.SessionId,
                    LocalDeviceId = localDevice.DeviceId,
                    LocalDeviceName = localDevice.DeviceName,
                    Peer = ClonePeer(incomingSession.Peer),
                    IsConnected = true,
                    IsIncoming = true,
                    TransportMode = AppConnectionMode.LocalLan
                };
            }
        }

        return null;
    }

    private static ProtocolEnvelope<TPayload> CreateServerEnvelope<TPayload>(
        string packetType,
        TPayload payload,
        LocalDeviceProfile localDevice,
        string? receiverDeviceId = null,
        string? sessionId = null,
        string? correlationId = null)
    {
        return ProtocolEnvelopeFactory.Create(
            packetType: packetType,
            payload: payload,
            senderDeviceId: localDevice.DeviceId,
            receiverDeviceId: receiverDeviceId,
            sessionId: sessionId,
            correlationId: correlationId);
    }

    private static ProtocolEnvelope<TPayload> CreateServerErrorEnvelope<TPayload>(
        string packetType,
        string errorCode,
        string errorMessage,
        TPayload payload,
        LocalDeviceProfile localDevice,
        string? receiverDeviceId = null,
        string? sessionId = null,
        string? correlationId = null,
        bool isRetryable = false)
    {
        return ProtocolEnvelopeFactory.CreateError(
            packetType: packetType,
            code: errorCode,
            message: errorMessage,
            payload: payload,
            senderDeviceId: localDevice.DeviceId,
            receiverDeviceId: receiverDeviceId,
            sessionId: sessionId,
            correlationId: correlationId,
            isRetryable: isRetryable);
    }

    private static int GetProtocolValidationStatusCode(ProtocolEnvelopeValidationResult validation)
    {
        return string.Equals(validation.ErrorCode, ProtocolErrorCodes.ProtocolMismatch, StringComparison.OrdinalIgnoreCase)
            ? StatusCodes.Status426UpgradeRequired
            : StatusCodes.Status400BadRequest;
    }

    private static FileTransferPrepareResponseDto CreateTransferPrepareFailureResponse(
        string transferId,
        string failureReason,
        LocalDeviceProfile localDevice)
    {
        return new FileTransferPrepareResponseDto(
            Accepted: false,
            TransferId: transferId,
            Status: ProtocolConstants.TransferStateFailed,
            FailureReason: failureReason,
            NextExpectedChunkIndex: 0,
            ReceivedBytes: 0,
            ReceiverDeviceId: localDevice.DeviceId,
            ReceiverDeviceName: localDevice.DeviceName,
            SuggestedFilePath: null,
            RespondedAtUtc: DateTimeOffset.UtcNow);
    }

    private static FileTransferChunkResponseDto CreateTransferChunkFailureResponse(
        string transferId,
        int chunkIndex,
        string failureReason)
    {
        return new FileTransferChunkResponseDto(
            Accepted: false,
            TransferId: transferId,
            ChunkIndex: chunkIndex,
            Status: ProtocolConstants.TransferStateFailed,
            FailureReason: failureReason,
            NextExpectedChunkIndex: 0,
            ReceivedBytes: 0,
            RespondedAtUtc: DateTimeOffset.UtcNow);
    }

    private static FileTransferCompleteResponseDto CreateTransferCompleteFailureResponse(
        string transferId,
        string failureReason)
    {
        return new FileTransferCompleteResponseDto(
            Accepted: false,
            TransferId: transferId,
            Status: ProtocolConstants.TransferStateFailed,
            FailureReason: failureReason,
            SavedFilePath: null,
            CompletedAtUtc: DateTimeOffset.UtcNow);
    }

    private static FileTransferCancelResponseDto CreateTransferCancelFailureResponse(
        string transferId,
        string failureReason)
    {
        return new FileTransferCancelResponseDto(
            Accepted: false,
            TransferId: transferId,
            Status: ProtocolConstants.TransferStateFailed,
            FailureReason: failureReason,
            CanceledAtUtc: DateTimeOffset.UtcNow);
    }

    private Task CancelHeartbeatLoopAsync()
    {
        _heartbeatCts?.Cancel();
        _heartbeatCts?.Dispose();
        _heartbeatCts = null;
        _heartbeatTask = null;
        return Task.CompletedTask;
    }

    private Task CancelReconnectLoopAsync()
    {
        _reconnectCts?.Cancel();
        _reconnectCts?.Dispose();
        _reconnectCts = null;
        _reconnectTask = null;
        return Task.CompletedTask;
    }

    private async Task FailAsync(ConnectionStateModel connectionState, string message)
    {
        await UpdateStateAsync(connectionState, state =>
        {
            state.IsConnected = false;
            state.LifecycleState = ConnectionLifecycleState.Failed;
            state.StatusText = message;
            state.LastFailure = message;
            state.IsReconnectScheduled = false;
            state.ActiveTransportLabel = BuildStandbyTransportLabel(state.CurrentMode);
        });
    }

    private async Task<LocalDeviceProfile> EnsureLocalDeviceAsync(CancellationToken cancellationToken)
    {
        _localDevice = await _discoveryService.GetLocalDeviceAsync(cancellationToken);
        return _localDevice;
    }

    private static void ApplyLocalRouteMetadata(ConnectionStateModel state, LocalDeviceProfile localDevice)
    {
        state.LocalEndpointSummary = BuildEndpointSummary(localDevice);
        state.AvailableTransportsLabel = BuildAvailableTransportsLabel(localDevice);
        state.NetworkRoutesSummary = BuildNetworkRoutesSummary(localDevice);
    }

    private static string BuildEndpointSummary(LocalDeviceProfile localDevice)
    {
        var endpointSummary = localDevice.LocalIpAddresses.Length == 0
            ? $"TCP {localDevice.ApiPort}"
            : string.Join(", ", localDevice.LocalIpAddresses.Select(address => $"{address}:{localDevice.ApiPort}"));

        return $"API {endpointSummary} | UDP {localDevice.DiscoveryPort}";
    }

    private static string BuildAvailableTransportsLabel(LocalDeviceProfile localDevice)
    {
        var routeCount = Math.Max(localDevice.LocalNetworkRoutes.Length, localDevice.LocalIpAddresses.Length);
        var lanLabel = routeCount == 0
            ? "LAN routes pending detection"
            : $"LAN routes ready: {routeCount}";
        return $"{lanLabel} · Bluetooth fallback standby";
    }

    private static string BuildNetworkRoutesSummary(LocalDeviceProfile localDevice)
    {
        return localDevice.LocalNetworkRoutes.Length == 0
            ? "No active IPv4 routes detected yet."
            : string.Join(" • ", localDevice.LocalNetworkRoutes);
    }

    private static string BuildStandbyTransportLabel(AppConnectionMode mode)
    {
        return mode switch
        {
            AppConnectionMode.LocalLan => "Standby: Local Wi-Fi / Hotspot",
            AppConnectionMode.BluetoothFallback => "Standby: Bluetooth fallback",
            _ => "Standby: Auto (LAN preferred, Bluetooth fallback ready)"
        };
    }

    private static Uri BuildPeerUri(DevicePeer peer, string path)
    {
        return new UriBuilder(Uri.UriSchemeHttp, NormalizeIpAddress(peer.IpAddress), peer.Port, path).Uri;
    }

    private static string NormalizeIpAddress(IPAddress? address)
    {
        if (address is null)
        {
            return "0.0.0.0";
        }

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

    private static ConnectionHandshakeResponseDto CreateHandshakeFailureResponse(
        LocalDeviceProfile localDevice,
        string sessionState,
        string failureReason)
    {
        return new ConnectionHandshakeResponseDto(
            Accepted: false,
            SessionState: sessionState,
            SessionId: null,
            FailureReason: failureReason,
            ServerDeviceId: localDevice.DeviceId,
            ServerDeviceName: localDevice.DeviceName,
            ServerPlatform: localDevice.Platform,
            ServerAppVersion: localDevice.AppVersion,
            SupportedModes: localDevice.SupportedModes,
            IssuedAtUtc: DateTimeOffset.UtcNow);
    }

    private static TextChatDeliveryReceiptDto CreateChatFailureReceipt(
        string messageId,
        string failureReason,
        LocalDeviceProfile localDevice)
    {
        return new TextChatDeliveryReceiptDto(
            Accepted: false,
            MessageId: messageId,
            Status: ProtocolConstants.DeliveryStatusFailed,
            FailureReason: failureReason,
            ReceiverDeviceId: localDevice.DeviceId,
            ReceiverDeviceName: localDevice.DeviceName,
            ReceivedAtUtc: DateTimeOffset.UtcNow);
    }

    private static string GeneratePairingCode()
    {
        return RandomNumberGenerator.GetInt32(100000, 999999).ToString();
    }

    private static Task SafeAwaitAsync(Task task)
    {
        return task.ContinueWith(
            _ => { },
            CancellationToken.None,
            TaskContinuationOptions.ExecuteSynchronously,
            TaskScheduler.Default);
    }

    private async Task NotifySessionChangedAsync(CancellationToken cancellationToken = default)
    {
        var handler = SessionChanged;
        if (handler is null)
        {
            return;
        }

        var snapshot = await GetActiveSessionAsync(cancellationToken);
        handler.Invoke(snapshot is { IsConnected: true } ? snapshot : null);
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

    private sealed class IncomingConnectionSession
    {
        public IncomingConnectionSession(
            string sessionId,
            DevicePeer peer,
            DateTimeOffset createdAtUtc,
            DateTimeOffset lastHeartbeatUtc)
        {
            SessionId = sessionId;
            Peer = peer;
            CreatedAtUtc = createdAtUtc;
            LastHeartbeatUtc = lastHeartbeatUtc;
        }

        public string SessionId { get; }

        public DevicePeer Peer { get; }

        public DateTimeOffset CreatedAtUtc { get; }

        public DateTimeOffset LastHeartbeatUtc { get; set; }
    }

    private sealed record ActiveConnection(
        string SessionId,
        DevicePeer Peer,
        string PairingToken,
        bool IsIncoming);

    private sealed record ReconnectPlan(
        string PeerId,
        string PeerName,
        string LastKnownIp,
        int LastKnownPort,
        string PairingToken);
}
