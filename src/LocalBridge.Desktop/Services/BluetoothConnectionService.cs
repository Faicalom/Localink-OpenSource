using System.Collections.Concurrent;
using System.IO;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using InTheHand.Net;
using InTheHand.Net.Bluetooth;
using System.Windows;
using InTheHand.Net.Sockets;
using LocalBridge.Core;
using LocalBridge.Core.Discovery;
using LocalBridge.Core.Protocol;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Models;
using LocalBridge.Desktop.Repositories;

namespace LocalBridge.Desktop.Services;

public sealed partial class BluetoothConnectionService
{
    private readonly ILocalDeviceProfileRepository _localDeviceProfileRepository;
    private readonly ITrustedDevicesService _trustedDevicesService;
    private readonly ILoggerService _loggerService;
    private readonly SemaphoreSlim _lifecycleGate = new(1, 1);
    private readonly SemaphoreSlim _connectGate = new(1, 1);

    private BluetoothListener? _listener;
    private CancellationTokenSource? _acceptLoopCts;
    private Task? _acceptLoopTask;
    private CancellationTokenSource? _heartbeatCts;
    private Task? _heartbeatTask;
    private CancellationTokenSource? _reconnectCts;
    private Task? _reconnectTask;
    private ConnectionStateModel? _boundState;
    private LocalDeviceProfile? _localDevice;
    private BluetoothSession? _activeSession;
    private ReconnectPlan? _reconnectPlan;
    private string _sharedPairingCode = GeneratePairingCode();
    private bool _isInitialized;

    public bool IsAvailable { get; private set; }

    public string AvailabilityReason { get; private set; } = "Bluetooth RFCOMM listener has not been initialized yet.";

    public BluetoothConnectionService(
        ILocalDeviceProfileRepository localDeviceProfileRepository,
        ITrustedDevicesService trustedDevicesService,
        ILoggerService loggerService)
    {
        _localDeviceProfileRepository = localDeviceProfileRepository;
        _trustedDevicesService = trustedDevicesService;
        _loggerService = loggerService;
    }

    public event Action<ConnectionSessionSnapshot?>? SessionChanged;

    public event Action<TextChatPacketDto>? ChatMessageReceived;

    public void UseSharedPairingCode(string pairingCode)
    {
        if (!string.IsNullOrWhiteSpace(pairingCode))
        {
            _sharedPairingCode = pairingCode.Trim();
        }
    }

    public async Task InitializeAsync(ConnectionStateModel connectionState, CancellationToken cancellationToken = default)
    {
        await _lifecycleGate.WaitAsync(cancellationToken);

        try
        {
            _boundState = connectionState;
            _localDevice = await EnsureLocalDeviceAsync(cancellationToken);

            if (_isInitialized)
            {
                return;
            }

            try
            {
                _listener = new BluetoothListener(AppConstants.BluetoothServiceId)
                {
                    ServiceName = AppConstants.BluetoothServiceName
                };
                _listener.Start();

                _acceptLoopCts = new CancellationTokenSource();
                _acceptLoopTask = Task.Run(() => AcceptLoopAsync(_acceptLoopCts.Token), _acceptLoopCts.Token);
                _isInitialized = true;
                IsAvailable = true;
                AvailabilityReason = "Bluetooth RFCOMM listener is ready.";

                await UpdateStateAsync(connectionState, state =>
                {
                    state.CanAcceptIncomingConnections = true;
                    state.IsServerListening = true;
                    if (string.IsNullOrWhiteSpace(state.LocalPairingCode) || state.LocalPairingCode == "------")
                    {
                        state.LocalPairingCode = _sharedPairingCode;
                    }
                });

                await _loggerService.LogInfoAsync(
                    $"Bluetooth RFCOMM listener started with service id {AppConstants.BluetoothServiceId}.",
                    cancellationToken);
            }
            catch (Exception ex)
            {
                IsAvailable = false;
                AvailabilityReason = ex.Message;
                await _loggerService.LogWarningAsync(
                    $"Bluetooth listener could not start. This usually means the Windows Bluetooth adapter or driver is unavailable for RFCOMM. {ex.Message}",
                    cancellationToken);
            }
        }
        finally
        {
            _lifecycleGate.Release();
        }
    }

    public async Task EnsureAvailabilityAsync(ConnectionStateModel connectionState, CancellationToken cancellationToken = default)
    {
        if (IsAvailable && _listener is not null)
        {
            return;
        }

        await InitializeAsync(connectionState, cancellationToken);
    }

    public async Task SetConnectionModeAsync(
        ConnectionStateModel connectionState,
        AppConnectionMode mode,
        CancellationToken cancellationToken = default)
    {
        await UpdateStateAsync(connectionState, state =>
        {
            if (state.IsConnected)
            {
                return;
            }

            switch (mode)
            {
                case AppConnectionMode.BluetoothFallback:
                    state.StatusText = "Bluetooth fallback is selected. It is slower than hotspot/LAN and best for messages and tiny files.";
                    break;
                case AppConnectionMode.Auto:
                    state.StatusText = "Auto mode prefers hotspot/LAN first and keeps Bluetooth ready as a fallback.";
                    break;
            }
        });

        await _loggerService.LogInfoAsync($"Bluetooth transport observed mode switch to {mode}.", cancellationToken);
    }

    public async Task SetDiscoveryActivityAsync(
        ConnectionStateModel connectionState,
        bool isActive,
        CancellationToken cancellationToken = default)
    {
        if (connectionState.CurrentMode == AppConnectionMode.BluetoothFallback)
        {
            await UpdateStateAsync(connectionState, state =>
            {
                if (!state.IsConnected)
                {
                    state.StatusText = isActive
                        ? "Scanning for nearby Bluetooth peers."
                        : "Bluetooth scanning is paused.";
                }
            });
        }
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
            if (!IsAvailable)
            {
                await FailAsync(
                    connectionState,
                    "Bluetooth RFCOMM is unavailable on this Windows PC. Keep using Local Wi-Fi / Hotspot on this host.");
                return;
            }

            if (peer is null)
            {
                await FailAsync(connectionState, "Select a Bluetooth device before connecting.");
                return;
            }

            if (string.IsNullOrWhiteSpace(peer.BluetoothAddress))
            {
                await FailAsync(connectionState, "The selected Bluetooth peer does not expose a usable Bluetooth address.");
                return;
            }

            if (connectionState.CurrentMode == AppConnectionMode.LocalLan)
            {
                await FailAsync(connectionState, "Switch to Auto or Bluetooth mode before opening a Bluetooth session.");
                return;
            }

            if (string.IsNullOrWhiteSpace(pairingToken))
            {
                await UpdateStateAsync(connectionState, state =>
                {
                    state.LifecycleState = ConnectionLifecycleState.WaitingForPairing;
                    state.ActivePeerName = peer.DisplayName;
                    state.ActivePeerId = peer.Id;
                    state.StatusText = $"Enter the LocalBridge confirmation code before connecting to {peer.DisplayName}.";
                    state.LastFailure = string.Empty;
                });
                return;
            }

            if (_reconnectCts is null || cancellationToken != _reconnectCts.Token)
            {
                await CancelReconnectLoopAsync();
            }
            await CloseActiveSessionAsync(
                "Switching Bluetooth sessions.",
                scheduleReconnect: false,
                notifyRemote: true,
                cancellationToken);

            await UpdateStateAsync(connectionState, state =>
            {
                state.LifecycleState = ConnectionLifecycleState.Connecting;
                state.ActivePeerName = peer.DisplayName;
                state.ActivePeerId = peer.Id;
                state.StatusText = $"Opening a Bluetooth RFCOMM link to {peer.DisplayName}.";
                state.HandshakeSummary = "Waiting for the Bluetooth socket before LocalBridge pairing starts.";
                state.LastFailure = string.Empty;
            });

            var (client, connectMethod, connectFailure) = await OpenOutgoingBluetoothClientAsync(peer, cancellationToken);
            if (client is null)
            {
                await FailAsync(
                    connectionState,
                    $"Bluetooth socket to {peer.DisplayName} could not be opened. Pair the devices in Windows Bluetooth settings first if needed, then use the LocalBridge six-digit code inside the app. {connectFailure}");
                return;
            }

            await _loggerService.LogInfoAsync(
                $"[BT-CONNECT] Opened outgoing Bluetooth socket to {peer.DisplayName} using {connectMethod}.",
                cancellationToken);

            var outgoingPeer = ClonePeer(peer);
            outgoingPeer.TransportMode = AppConnectionMode.BluetoothFallback;
            outgoingPeer.IsOnline = true;

            var session = CreateSession(
                client,
                outgoingPeer,
                pairingToken.Trim(),
                isIncoming: false);

            _activeSession = session;
            session.ReadLoopTask = Task.Run(() => RunReadLoopAsync(session, CancellationToken.None));

            var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
            var handshakeResponse = await SendRequestAsync<ConnectionHandshakeRequestDto, ConnectionHandshakeResponseDto>(
                session,
                packetType: ProtocolPacketTypes.ConnectionHandshakeRequest,
                expectedResponsePacketType: ProtocolPacketTypes.ConnectionHandshakeResponse,
                payload: new ConnectionHandshakeRequestDto(
                    DeviceId: localDevice.DeviceId,
                    DeviceName: localDevice.DeviceName,
                    Platform: localDevice.Platform,
                    AppVersion: localDevice.AppVersion,
                    PairingToken: pairingToken.Trim(),
                    SupportedModes: localDevice.SupportedModes),
                receiverDeviceId: peer.Id,
                sessionId: null,
                cancellationToken: cancellationToken);

            if (handshakeResponse is null || !handshakeResponse.Accepted || string.IsNullOrWhiteSpace(handshakeResponse.SessionId))
            {
                var failureReason = handshakeResponse?.FailureReason;
                var normalizedPairingToken = pairingToken.Trim();
                var failureMessage =
                    string.Equals(failureReason, ProtocolErrorCodes.InvalidPairingToken, StringComparison.OrdinalIgnoreCase) &&
                    string.Equals(normalizedPairingToken, connectionState.LocalPairingCode, StringComparison.Ordinal)
                        ? $"Bluetooth pairing with {peer.DisplayName} failed because this PC's own six-digit code was entered. Type the code shown on {peer.DisplayName} when this PC starts the Bluetooth session, or keep this PC open and enter this PC code on Android when the phone starts the session."
                        : failureReason is { Length: > 0 }
                            ? $"Bluetooth pairing with {peer.DisplayName} failed: {failureReason}."
                            : $"Bluetooth pairing with {peer.DisplayName} failed.";

                await CloseActiveSessionAsync(
                    failureMessage,
                    scheduleReconnect: false,
                    notifyRemote: false,
                    cancellationToken);
                return;
            }

            session.SessionId = handshakeResponse.SessionId!;
            var resolvedPeer = ClonePeer(session.Peer);
            resolvedPeer.Id = handshakeResponse.ServerDeviceId;
            resolvedPeer.DisplayName = handshakeResponse.ServerDeviceName;
            resolvedPeer.Platform = handshakeResponse.ServerPlatform;
            resolvedPeer.AppVersion = handshakeResponse.ServerAppVersion;
            resolvedPeer.SupportedModes = handshakeResponse.SupportedModes.ToArray();
            resolvedPeer.TransportMode = AppConnectionMode.BluetoothFallback;
            resolvedPeer.IsTrusted = true;
            resolvedPeer.IsOnline = true;
            resolvedPeer.HasResolvedIdentity = true;
            session.Peer = resolvedPeer;

            _reconnectPlan = new ReconnectPlan(
                session.Peer.Id,
                session.Peer.DisplayName,
                session.Peer.BluetoothAddress,
                pairingToken.Trim());

            await _trustedDevicesService.TrustDeviceAsync(session.Peer, cancellationToken);

            await UpdateStateAsync(connectionState, state =>
            {
                state.ActivePeerName = session.Peer.DisplayName;
                state.ActivePeerId = session.Peer.Id;
                state.SessionId = session.SessionId;
                state.LifecycleState = ConnectionLifecycleState.Paired;
                state.StatusText = $"Bluetooth pairing accepted by {session.Peer.DisplayName}.";
                state.HandshakeSummary = $"RFCOMM is live and LocalBridge session {session.SessionId} is awaiting heartbeat validation.";
                state.LastFailure = string.Empty;
                state.IsConnected = false;
            });

            var heartbeatAccepted = await SendHeartbeatAsync(session, transitionToConnected: true, cancellationToken);
            if (!heartbeatAccepted)
            {
                await CloseActiveSessionAsync(
                    "Bluetooth heartbeat validation failed.",
                    scheduleReconnect: true,
                    notifyRemote: false,
                    cancellationToken);
                return;
            }

            StartHeartbeatLoop();
            await NotifySessionChangedAsync(cancellationToken);
        }
        finally
        {
            _connectGate.Release();
        }
    }

    public async Task<ConnectionSessionSnapshot?> GetActiveSessionAsync(CancellationToken cancellationToken = default)
    {
        if (_activeSession is null)
        {
            return null;
        }

        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        return new ConnectionSessionSnapshot
        {
            SessionId = _activeSession.SessionId,
            LocalDeviceId = localDevice.DeviceId,
            LocalDeviceName = localDevice.DeviceName,
            Peer = ClonePeer(_activeSession.Peer),
            IsConnected = _boundState?.IsConnected ?? true,
            IsIncoming = _activeSession.IsIncoming,
            TransportMode = AppConnectionMode.BluetoothFallback
        };
    }

    public async Task<TextChatDeliveryReceiptDto> SendChatMessageAsync(TextChatPacketDto packet, CancellationToken cancellationToken = default)
    {
        var session = _activeSession;
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        if (session is null || _boundState?.IsConnected != true)
        {
            return CreateFailedReceipt(packet.Id, ProtocolErrorCodes.NotConnected, localDevice);
        }

        var payload = packet with
        {
            SessionId = session.SessionId,
            SenderId = localDevice.DeviceId,
            SenderName = localDevice.DeviceName,
            ReceiverId = session.Peer.Id,
            TimestampUtc = packet.TimestampUtc == default ? DateTimeOffset.UtcNow : packet.TimestampUtc
        };

        return await SendRequestAsync<TextChatPacketDto, TextChatDeliveryReceiptDto>(
                   session,
                   packetType: ProtocolPacketTypes.ChatTextMessage,
                   expectedResponsePacketType: ProtocolPacketTypes.ChatDeliveryReceipt,
                   payload: payload,
                   receiverDeviceId: session.Peer.Id,
                   sessionId: session.SessionId,
                   messageId: payload.Id,
                   sentAtUtc: payload.TimestampUtc,
                   cancellationToken: cancellationToken)
               ?? CreateFailedReceipt(packet.Id, "bluetooth_chat_timeout", localDevice);
    }

    public async Task DisconnectAsync(ConnectionStateModel connectionState, CancellationToken cancellationToken = default)
    {
        await CancelReconnectLoopAsync();
        await CloseActiveSessionAsync(
            "Bluetooth session disconnected.",
            scheduleReconnect: false,
            notifyRemote: true,
            cancellationToken);

        await UpdateStateAsync(connectionState, state =>
        {
            if (state.CurrentMode == AppConnectionMode.BluetoothFallback && !state.IsConnected)
            {
                state.StatusText = "Bluetooth fallback is idle.";
            }
        });
    }

    public async Task ShutdownAsync(ConnectionStateModel connectionState, CancellationToken cancellationToken = default)
    {
        await CancelReconnectLoopAsync();
        await CancelHeartbeatLoopAsync();
        await CloseActiveSessionAsync(
            "Bluetooth transport stopped.",
            scheduleReconnect: false,
            notifyRemote: false,
            cancellationToken);

        _acceptLoopCts?.Cancel();
        if (_acceptLoopTask is not null)
        {
            await SafeAwaitAsync(_acceptLoopTask);
        }

        _listener?.Stop();
        _listener = null;
        _acceptLoopCts?.Dispose();
        _acceptLoopCts = null;
        _acceptLoopTask = null;
        _isInitialized = false;

        await UpdateStateAsync(connectionState, state =>
        {
            if (state.CurrentMode == AppConnectionMode.BluetoothFallback)
            {
                state.IsServerListening = false;
                state.CanAcceptIncomingConnections = false;
            }
        });
    }

    private async Task AcceptLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                if (_listener is null)
                {
                    return;
                }

                var client = await Task.Run(() => _listener.AcceptBluetoothClient(), cancellationToken);
                var session = CreateSession(client, CreateTemporaryPeer(client), string.Empty, isIncoming: true);
                session.ReadLoopTask = Task.Run(() => RunReadLoopAsync(session, session.CancellationSource.Token));

                await _loggerService.LogInfoAsync(
                    $"Incoming Bluetooth socket accepted from {session.Peer.EndpointLabel}.",
                    cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (Exception ex)
            {
                if (cancellationToken.IsCancellationRequested)
                {
                    return;
                }

                await _loggerService.LogWarningAsync($"Bluetooth accept loop failed: {ex.Message}", cancellationToken);
                await Task.Delay(1000, cancellationToken);
            }
        }
    }

    private async Task RunReadLoopAsync(BluetoothSession session, CancellationToken cancellationToken)
    {
        try
        {
            while (!session.CancellationSource.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
            {
                var frame = await BluetoothTransportFrameCodec.ReadAsync(session.Stream, session.CancellationSource.Token);
                if (frame is null)
                {
                    break;
                }

                var envelope = JsonSerializer.Deserialize<ProtocolEnvelope<JsonElement>>(frame.MetadataJson, JsonDefaults.Options);
                if (envelope is null)
                {
                    continue;
                }

                if (!string.IsNullOrWhiteSpace(envelope.Meta.CorrelationId) &&
                    session.PendingResponses.TryRemove(envelope.Meta.CorrelationId, out var waiter))
                {
                    waiter.TrySetResult(envelope);
                    continue;
                }

                await HandleIncomingEnvelopeAsync(session, envelope, frame.BinaryPayload, session.CancellationSource.Token);
            }
        }
        catch (OperationCanceledException)
        {
            // Expected during shutdown.
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync($"Bluetooth read loop failed for {session.Peer.DisplayName}: {ex.Message}");
        }
        finally
        {
            if (ReferenceEquals(_activeSession, session) || session.IsIncoming)
            {
                await CloseSessionAsync(
                    session,
                    "The Bluetooth link closed.",
                    scheduleReconnect: !session.IsIncoming,
                    notifyRemote: false,
                    CancellationToken.None);
            }
        }
    }

    private async Task<TResponse?> SendRequestAsync<TRequest, TResponse>(
        BluetoothSession session,
        string packetType,
        string expectedResponsePacketType,
        TRequest payload,
        string? receiverDeviceId,
        string? sessionId,
        string? messageId = null,
        DateTimeOffset? sentAtUtc = null,
        CancellationToken cancellationToken = default)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        var resolvedMessageId = string.IsNullOrWhiteSpace(messageId) ? Guid.NewGuid().ToString("N") : messageId;
        var envelope = ProtocolEnvelopeFactory.Create(
            packetType: packetType,
            payload: payload,
            senderDeviceId: localDevice.DeviceId,
            receiverDeviceId: receiverDeviceId,
            sessionId: sessionId,
            messageId: resolvedMessageId,
            sentAtUtc: sentAtUtc ?? DateTimeOffset.UtcNow);

        var waiter = new TaskCompletionSource<ProtocolEnvelope<JsonElement>?>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        session.PendingResponses[resolvedMessageId] = waiter;

        try
        {
            await SendEnvelopeAsync(session, envelope, cancellationToken);

            using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeoutCts.CancelAfter(TimeSpan.FromSeconds(AppConstants.ConnectionRequestTimeoutSeconds));
            using var registration = timeoutCts.Token.Register(() => waiter.TrySetCanceled(timeoutCts.Token));

            var responseEnvelope = await waiter.Task;
            var validation = ProtocolEnvelopeValidator.Validate(
                responseEnvelope,
                expectedPacketTypes: [expectedResponsePacketType]);
            if (!validation.IsValid)
            {
                return default;
            }

            return DeserializePayload<TResponse>(responseEnvelope!);
        }
        catch (OperationCanceledException)
        {
            return default;
        }
        finally
        {
            session.PendingResponses.TryRemove(resolvedMessageId, out _);
        }
    }

    private async Task SendEnvelopeAsync<TPayload>(
        BluetoothSession session,
        ProtocolEnvelope<TPayload> envelope,
        CancellationToken cancellationToken)
    {
        var json = JsonSerializer.Serialize(envelope, JsonDefaults.Options);
        await session.SendGate.WaitAsync(cancellationToken);
        try
        {
            await BluetoothTransportFrameCodec.WriteJsonEnvelopeAsync(session.Stream, json, cancellationToken);
        }
        finally
        {
            session.SendGate.Release();
        }
    }

    private async Task<bool> SendHeartbeatAsync(
        BluetoothSession session,
        bool transitionToConnected,
        CancellationToken cancellationToken)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        var response = await SendRequestAsync<ConnectionHeartbeatRequestDto, ConnectionHeartbeatResponseDto>(
            session,
            packetType: ProtocolPacketTypes.ConnectionHeartbeatRequest,
            expectedResponsePacketType: ProtocolPacketTypes.ConnectionHeartbeatResponse,
            payload: new ConnectionHeartbeatRequestDto(
                SessionId: session.SessionId,
                DeviceId: localDevice.DeviceId,
                DeviceName: localDevice.DeviceName,
                Platform: localDevice.Platform,
                AppVersion: localDevice.AppVersion),
            receiverDeviceId: session.Peer.Id,
            sessionId: session.SessionId,
            cancellationToken: cancellationToken);

        if (response is null || !response.Alive)
        {
            return false;
        }

        session.LastHeartbeatUtc = DateTimeOffset.UtcNow;

        if (transitionToConnected)
        {
            await UpdateStateAsync(_boundState, state =>
            {
                state.ActivePeerName = session.Peer.DisplayName;
                state.ActivePeerId = session.Peer.Id;
                state.SessionId = session.SessionId;
                state.IsConnected = true;
                state.LifecycleState = ConnectionLifecycleState.Connected;
                state.StatusText = $"Connected to {session.Peer.DisplayName} over Bluetooth RFCOMM.";
                state.HandshakeSummary = $"Heartbeat validated Bluetooth session {session.SessionId}.";
                state.IsReconnectScheduled = false;
                state.ReconnectAttemptCount = 0;
                state.LastFailure = string.Empty;
            });
        }

        return true;
    }

    private void StartHeartbeatLoop()
    {
        if (_activeSession is null || _heartbeatTask is { IsCompleted: false })
        {
            return;
        }

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
                    var session = _activeSession;
                    if (session is null)
                    {
                        return;
                    }

                    var isAlive = await SendHeartbeatAsync(session, transitionToConnected: false, token);
                    if (!isAlive)
                    {
                        await CloseActiveSessionAsync(
                            "Bluetooth heartbeat timed out.",
                            scheduleReconnect: !session.IsIncoming,
                            notifyRemote: false,
                            token);
                        return;
                    }
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                catch (Exception ex)
                {
                    await _loggerService.LogWarningAsync($"Bluetooth heartbeat loop failed: {ex.Message}", token);
                }
            }
        }, token);
    }

    private async Task HandleIncomingEnvelopeAsync(
        BluetoothSession session,
        ProtocolEnvelope<JsonElement> envelope,
        byte[]? binaryPayload,
        CancellationToken cancellationToken)
    {
        switch (envelope.Meta.PacketType)
        {
            case ProtocolPacketTypes.ConnectionHandshakeRequest:
                await HandleHandshakeRequestAsync(session, envelope, cancellationToken);
                return;
            case ProtocolPacketTypes.ConnectionHeartbeatRequest:
                await HandleHeartbeatRequestAsync(session, envelope, cancellationToken);
                return;
            case ProtocolPacketTypes.ConnectionDisconnectRequest:
                await HandleDisconnectRequestAsync(session, envelope, cancellationToken);
                return;
            case ProtocolPacketTypes.ChatTextMessage:
                await HandleChatMessageAsync(session, envelope, cancellationToken);
                return;
            case ProtocolPacketTypes.TransferPrepareRequest:
                await HandleTransferPrepareRequestAsync(session, envelope, cancellationToken);
                return;
            case ProtocolPacketTypes.TransferChunkRequest:
                await HandleTransferChunkRequestAsync(session, envelope, binaryPayload, cancellationToken);
                return;
            case ProtocolPacketTypes.TransferCompleteRequest:
                await HandleTransferCompleteRequestAsync(session, envelope, cancellationToken);
                return;
            case ProtocolPacketTypes.TransferCancelRequest:
                await HandleTransferCancelRequestAsync(session, envelope, cancellationToken);
                return;
            default:
                await _loggerService.LogWarningAsync(
                    $"Bluetooth transport received unsupported packet type '{envelope.Meta.PacketType}'.",
                    cancellationToken);
                return;
        }
    }

    private async Task HandleHandshakeRequestAsync(
        BluetoothSession session,
        ProtocolEnvelope<JsonElement> envelope,
        CancellationToken cancellationToken)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        var validation = ProtocolEnvelopeValidator.Validate(
            envelope,
            expectedPacketTypes: [ProtocolPacketTypes.ConnectionHandshakeRequest]);
        var request = DeserializePayload<ConnectionHandshakeRequestDto>(envelope);

        if (!validation.IsValid)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ConnectionHandshakeResponse,
                errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidRequest,
                errorMessage: validation.ErrorMessage ?? "Bluetooth handshake request is malformed.",
                payload: CreateHandshakeFailureResponse(
                    localDevice,
                    ProtocolConstants.SessionStateFailed,
                    validation.ErrorCode ?? ProtocolErrorCodes.InvalidRequest),
                receiverDeviceId: request?.DeviceId,
                sessionId: null,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (_boundState?.CurrentMode == AppConnectionMode.LocalLan)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ConnectionHandshakeResponse,
                errorCode: ProtocolErrorCodes.NotConnected,
                errorMessage: "Bluetooth fallback is disabled while Local Wi-Fi / Hotspot mode is locked in.",
                payload: CreateHandshakeFailureResponse(
                    localDevice,
                    ProtocolConstants.SessionStateFailed,
                    ProtocolErrorCodes.NotConnected),
                receiverDeviceId: request?.DeviceId,
                sessionId: null,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (request is null ||
            string.IsNullOrWhiteSpace(request.DeviceId) ||
            string.IsNullOrWhiteSpace(request.DeviceName) ||
            string.IsNullOrWhiteSpace(request.Platform) ||
            string.IsNullOrWhiteSpace(request.AppVersion))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ConnectionHandshakeResponse,
                errorCode: ProtocolErrorCodes.InvalidRequest,
                errorMessage: "Bluetooth handshake request is missing required fields.",
                payload: CreateHandshakeFailureResponse(
                    localDevice,
                    ProtocolConstants.SessionStateFailed,
                    ProtocolErrorCodes.InvalidRequest),
                receiverDeviceId: request?.DeviceId,
                sessionId: null,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (string.Equals(request.DeviceId, localDevice.DeviceId, StringComparison.OrdinalIgnoreCase))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ConnectionHandshakeResponse,
                errorCode: ProtocolErrorCodes.SelfConnectionNotAllowed,
                errorMessage: "A device cannot pair with itself over Bluetooth.",
                payload: CreateHandshakeFailureResponse(
                    localDevice,
                    ProtocolConstants.SessionStateFailed,
                    ProtocolErrorCodes.SelfConnectionNotAllowed),
                receiverDeviceId: request.DeviceId,
                sessionId: null,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        var pairingCode = ResolvePairingCode(request.PairingToken);
        if (pairingCode is null || !string.Equals(pairingCode, _sharedPairingCode, StringComparison.Ordinal))
        {
            var errorCode = string.IsNullOrWhiteSpace(request.PairingToken)
                ? ProtocolErrorCodes.PairingTokenRequired
                : ProtocolErrorCodes.InvalidPairingToken;

            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ConnectionHandshakeResponse,
                errorCode: errorCode,
                errorMessage: errorCode == ProtocolErrorCodes.PairingTokenRequired
                    ? "The LocalBridge confirmation code is required for Bluetooth pairing."
                    : "The supplied Bluetooth pairing code is not valid on this host.",
                payload: CreateHandshakeFailureResponse(
                    localDevice,
                    ProtocolConstants.SessionStateWaitingForPairing,
                    errorCode),
                receiverDeviceId: request.DeviceId,
                sessionId: null,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);

            await _loggerService.LogWarningAsync(
                $"Bluetooth handshake from {request.DeviceName} was rejected because the confirmation code was invalid.",
                cancellationToken);
            return;
        }

        var existingActiveSession = _activeSession;
        if (existingActiveSession is not null &&
            !ReferenceEquals(existingActiveSession, session) &&
            !string.Equals(existingActiveSession.Peer.Id, request.DeviceId, StringComparison.OrdinalIgnoreCase))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ConnectionHandshakeResponse,
                errorCode: ProtocolErrorCodes.InvalidRequest,
                errorMessage: "Another Bluetooth session is already active on this host.",
                payload: CreateHandshakeFailureResponse(
                    localDevice,
                    ProtocolConstants.SessionStateFailed,
                    ProtocolErrorCodes.InvalidRequest),
                receiverDeviceId: request.DeviceId,
                sessionId: null,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        var resolvedPeer = ClonePeer(session.Peer);
        resolvedPeer.Id = request.DeviceId;
        resolvedPeer.DisplayName = request.DeviceName;
        resolvedPeer.Platform = request.Platform;
        resolvedPeer.AppVersion = request.AppVersion;
        resolvedPeer.SupportedModes = request.SupportedModes.ToArray();
        resolvedPeer.TransportMode = AppConnectionMode.BluetoothFallback;
        resolvedPeer.IsTrusted = true;
        resolvedPeer.IsOnline = true;
        resolvedPeer.HasResolvedIdentity = true;

        session.Peer = resolvedPeer;
        session.PairingToken = pairingCode;
        session.SessionId = Guid.NewGuid().ToString("N");
        session.LastHeartbeatUtc = DateTimeOffset.UtcNow;
        _activeSession = session;
        _reconnectPlan = null;

        await _trustedDevicesService.TrustDeviceAsync(session.Peer, cancellationToken);

        await UpdateStateAsync(_boundState, state =>
        {
            state.ActivePeerName = session.Peer.DisplayName;
            state.ActivePeerId = session.Peer.Id;
            state.SessionId = session.SessionId;
            state.IsConnected = false;
            state.LifecycleState = ConnectionLifecycleState.Paired;
            state.StatusText = $"Accepted Bluetooth pairing from {session.Peer.DisplayName}.";
            state.HandshakeSummary = $"Incoming Bluetooth session {session.SessionId} paired successfully.";
            state.LastFailure = string.Empty;
        });

        await _loggerService.LogInfoAsync(
            $"Accepted Bluetooth handshake from {session.Peer.DisplayName}. Session {session.SessionId} created.",
            cancellationToken);

        await SendResponseEnvelopeAsync(
            session,
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
            receiverDeviceId: request.DeviceId,
            sessionId: session.SessionId,
            correlationId: envelope.Meta.MessageId,
            cancellationToken: cancellationToken);
    }

    private async Task HandleHeartbeatRequestAsync(
        BluetoothSession session,
        ProtocolEnvelope<JsonElement> envelope,
        CancellationToken cancellationToken)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        var validation = ProtocolEnvelopeValidator.Validate(
            envelope,
            expectedPacketTypes: [ProtocolPacketTypes.ConnectionHeartbeatRequest]);
        var request = DeserializePayload<ConnectionHeartbeatRequestDto>(envelope);

        if (!validation.IsValid)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ConnectionHeartbeatResponse,
                errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidRequest,
                errorMessage: validation.ErrorMessage ?? "Bluetooth heartbeat request is malformed.",
                payload: CreateHeartbeatFailureResponse(
                    localDevice,
                    ProtocolConstants.SessionStateFailed,
                    validation.ErrorCode ?? ProtocolErrorCodes.InvalidRequest),
                receiverDeviceId: request?.DeviceId,
                sessionId: request?.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (request is null ||
            string.IsNullOrWhiteSpace(request.SessionId) ||
            string.IsNullOrWhiteSpace(request.DeviceId))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ConnectionHeartbeatResponse,
                errorCode: ProtocolErrorCodes.InvalidRequest,
                errorMessage: "Bluetooth heartbeat request is missing the session or device id.",
                payload: CreateHeartbeatFailureResponse(
                    localDevice,
                    ProtocolConstants.SessionStateFailed,
                    ProtocolErrorCodes.InvalidRequest),
                receiverDeviceId: request?.DeviceId,
                sessionId: request?.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (!string.Equals(session.SessionId, request.SessionId, StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(session.Peer.Id, request.DeviceId, StringComparison.OrdinalIgnoreCase))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ConnectionHeartbeatResponse,
                errorCode: ProtocolErrorCodes.SessionNotFound,
                errorMessage: "The Bluetooth session is not active on this host.",
                payload: CreateHeartbeatFailureResponse(
                    localDevice,
                    ProtocolConstants.SessionStateDisconnected,
                    ProtocolErrorCodes.SessionNotFound),
                receiverDeviceId: request.DeviceId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        session.LastHeartbeatUtc = DateTimeOffset.UtcNow;
        session.Peer.LastSeenAtUtc = DateTimeOffset.UtcNow;
        session.Peer.IsOnline = true;
        _activeSession = session;

        await UpdateStateAsync(_boundState, state =>
        {
            state.ActivePeerName = session.Peer.DisplayName;
            state.ActivePeerId = session.Peer.Id;
            state.SessionId = session.SessionId;
            state.IsConnected = true;
            state.LifecycleState = ConnectionLifecycleState.Connected;
            state.StatusText = $"Connected with {session.Peer.DisplayName} over Bluetooth RFCOMM.";
            state.HandshakeSummary = $"Heartbeat validated Bluetooth session {session.SessionId}.";
            state.LastFailure = string.Empty;
            state.IsReconnectScheduled = false;
            state.ReconnectAttemptCount = 0;
        });

        await NotifySessionChangedAsync(cancellationToken);
        await _loggerService.LogInfoAsync(
            $"Bluetooth heartbeat accepted for session {session.SessionId} from {session.Peer.DisplayName}.",
            cancellationToken);

        await SendResponseEnvelopeAsync(
            session,
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
            receiverDeviceId: request.DeviceId,
            sessionId: request.SessionId,
            correlationId: envelope.Meta.MessageId,
            cancellationToken: cancellationToken);
    }

    private async Task HandleDisconnectRequestAsync(
        BluetoothSession session,
        ProtocolEnvelope<JsonElement> envelope,
        CancellationToken cancellationToken)
    {
        var validation = ProtocolEnvelopeValidator.Validate(
            envelope,
            expectedPacketTypes: [ProtocolPacketTypes.ConnectionDisconnectRequest]);
        var request = DeserializePayload<ConnectionDisconnectRequestDto>(envelope);

        if (!validation.IsValid)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ConnectionDisconnectResponse,
                errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidRequest,
                errorMessage: validation.ErrorMessage ?? "Bluetooth disconnect request is malformed.",
                payload: new ConnectionDisconnectResponseDto(
                    Acknowledged: false,
                    SessionId: request?.SessionId ?? string.Empty,
                    ReceivedAtUtc: DateTimeOffset.UtcNow),
                receiverDeviceId: request?.DeviceId,
                sessionId: request?.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (request is null ||
            string.IsNullOrWhiteSpace(request.SessionId) ||
            string.IsNullOrWhiteSpace(request.DeviceId))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ConnectionDisconnectResponse,
                errorCode: ProtocolErrorCodes.InvalidRequest,
                errorMessage: "Bluetooth disconnect request is missing required metadata.",
                payload: new ConnectionDisconnectResponseDto(
                    Acknowledged: false,
                    SessionId: request?.SessionId ?? string.Empty,
                    ReceivedAtUtc: DateTimeOffset.UtcNow),
                receiverDeviceId: request?.DeviceId,
                sessionId: request?.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (!string.Equals(session.SessionId, request.SessionId, StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(session.Peer.Id, request.DeviceId, StringComparison.OrdinalIgnoreCase))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ConnectionDisconnectResponse,
                errorCode: ProtocolErrorCodes.SessionNotFound,
                errorMessage: "The Bluetooth session is not active on this host.",
                payload: new ConnectionDisconnectResponseDto(
                    Acknowledged: false,
                    SessionId: request.SessionId,
                    ReceivedAtUtc: DateTimeOffset.UtcNow),
                receiverDeviceId: request.DeviceId,
                sessionId: request.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        await SendResponseEnvelopeAsync(
            session,
            packetType: ProtocolPacketTypes.ConnectionDisconnectResponse,
            payload: new ConnectionDisconnectResponseDto(
                Acknowledged: true,
                SessionId: request.SessionId,
                ReceivedAtUtc: DateTimeOffset.UtcNow),
            receiverDeviceId: request.DeviceId,
            sessionId: request.SessionId,
            correlationId: envelope.Meta.MessageId,
            cancellationToken: cancellationToken);

        await CloseSessionAsync(
            session,
            $"{session.Peer.DisplayName} disconnected the Bluetooth session.",
            scheduleReconnect: false,
            notifyRemote: false,
            cancellationToken);
    }

    private async Task HandleChatMessageAsync(
        BluetoothSession session,
        ProtocolEnvelope<JsonElement> envelope,
        CancellationToken cancellationToken)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        var validation = ProtocolEnvelopeValidator.Validate(
            envelope,
            expectedPacketTypes: [ProtocolPacketTypes.ChatTextMessage]);
        var packet = DeserializePayload<TextChatPacketDto>(envelope);

        if (!validation.IsValid)
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ChatDeliveryReceipt,
                errorCode: validation.ErrorCode ?? ProtocolErrorCodes.InvalidRequest,
                errorMessage: validation.ErrorMessage ?? "Bluetooth chat packet is malformed.",
                payload: CreateFailedReceipt(
                    packet?.Id ?? string.Empty,
                    validation.ErrorCode ?? ProtocolErrorCodes.InvalidRequest,
                    localDevice),
                receiverDeviceId: packet?.SenderId,
                sessionId: packet?.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (packet is null ||
            string.IsNullOrWhiteSpace(packet.Id) ||
            string.IsNullOrWhiteSpace(packet.SessionId) ||
            string.IsNullOrWhiteSpace(packet.SenderId) ||
            string.IsNullOrWhiteSpace(packet.Text))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ChatDeliveryReceipt,
                errorCode: ProtocolErrorCodes.InvalidRequest,
                errorMessage: "Bluetooth chat packet is missing required fields.",
                payload: CreateFailedReceipt(
                    packet?.Id ?? string.Empty,
                    ProtocolErrorCodes.InvalidRequest,
                    localDevice),
                receiverDeviceId: packet?.SenderId,
                sessionId: packet?.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (!string.IsNullOrWhiteSpace(packet.ReceiverId) &&
            !string.Equals(packet.ReceiverId, localDevice.DeviceId, StringComparison.OrdinalIgnoreCase))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ChatDeliveryReceipt,
                errorCode: ProtocolErrorCodes.WrongReceiver,
                errorMessage: "The Bluetooth message receiver does not match this device.",
                payload: CreateFailedReceipt(
                    packet.Id,
                    ProtocolErrorCodes.WrongReceiver,
                    localDevice),
                receiverDeviceId: packet.SenderId,
                sessionId: packet.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        if (!string.Equals(session.SessionId, packet.SessionId, StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(session.Peer.Id, packet.SenderId, StringComparison.OrdinalIgnoreCase))
        {
            await SendErrorEnvelopeAsync(
                session,
                packetType: ProtocolPacketTypes.ChatDeliveryReceipt,
                errorCode: ProtocolErrorCodes.SessionNotFound,
                errorMessage: "The Bluetooth chat session is not active on this host.",
                payload: CreateFailedReceipt(
                    packet.Id,
                    ProtocolErrorCodes.SessionNotFound,
                    localDevice),
                receiverDeviceId: packet.SenderId,
                sessionId: packet.SessionId,
                correlationId: envelope.Meta.MessageId,
                cancellationToken: cancellationToken);
            return;
        }

        session.LastHeartbeatUtc = DateTimeOffset.UtcNow;
        session.Peer.LastSeenAtUtc = DateTimeOffset.UtcNow;
        session.Peer.IsOnline = true;

        try
        {
            ChatMessageReceived?.Invoke(packet);
        }
        catch (Exception ex)
        {
            await _loggerService.LogWarningAsync(
                $"Bluetooth chat event dispatch failed: {ex.Message}",
                cancellationToken);
        }

        await _loggerService.LogInfoAsync(
            $"Accepted Bluetooth chat message {packet.Id} from {packet.SenderName}.",
            cancellationToken);

        await SendResponseEnvelopeAsync(
            session,
            packetType: ProtocolPacketTypes.ChatDeliveryReceipt,
            payload: new TextChatDeliveryReceiptDto(
                Accepted: true,
                MessageId: packet.Id,
                Status: ProtocolConstants.DeliveryStatusDelivered,
                FailureReason: null,
                ReceiverDeviceId: localDevice.DeviceId,
                ReceiverDeviceName: localDevice.DeviceName,
                ReceivedAtUtc: DateTimeOffset.UtcNow),
            receiverDeviceId: packet.SenderId,
            sessionId: packet.SessionId,
            correlationId: envelope.Meta.MessageId,
            cancellationToken: cancellationToken);
    }

    private async Task HandleTransferUnsupportedAsync(
        BluetoothSession session,
        ProtocolEnvelope<JsonElement> envelope,
        CancellationToken cancellationToken)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        const string message = "Bluetooth fallback currently prioritizes text chat only. Use Auto or Local Wi-Fi / Hotspot for file transfers.";

        switch (envelope.Meta.PacketType)
        {
            case ProtocolPacketTypes.TransferPrepareRequest:
            {
                var request = DeserializePayload<FileTransferPrepareRequestDto>(envelope);
                await SendErrorEnvelopeAsync(
                    session,
                    packetType: ProtocolPacketTypes.TransferPrepareResponse,
                    errorCode: ProtocolErrorCodes.TransferServiceUnavailable,
                    errorMessage: message,
                    payload: new FileTransferPrepareResponseDto(
                        Accepted: false,
                        TransferId: request?.TransferId ?? string.Empty,
                        Status: ProtocolConstants.TransferStateFailed,
                        FailureReason: ProtocolErrorCodes.TransferServiceUnavailable,
                        NextExpectedChunkIndex: 0,
                        ReceivedBytes: 0,
                        ReceiverDeviceId: localDevice.DeviceId,
                        ReceiverDeviceName: localDevice.DeviceName,
                        SuggestedFilePath: null,
                        RespondedAtUtc: DateTimeOffset.UtcNow),
                    receiverDeviceId: request?.SenderId,
                    sessionId: request?.SessionId,
                    correlationId: envelope.Meta.MessageId,
                    cancellationToken: cancellationToken);
                break;
            }
            case ProtocolPacketTypes.TransferChunkRequest:
            {
                var request = DeserializePayload<FileTransferChunkDescriptorDto>(envelope);
                await SendErrorEnvelopeAsync(
                    session,
                    packetType: ProtocolPacketTypes.TransferChunkResponse,
                    errorCode: ProtocolErrorCodes.TransferServiceUnavailable,
                    errorMessage: message,
                    payload: new FileTransferChunkResponseDto(
                        Accepted: false,
                        TransferId: request?.TransferId ?? string.Empty,
                        ChunkIndex: request?.ChunkIndex ?? 0,
                        Status: ProtocolConstants.TransferStateFailed,
                        FailureReason: ProtocolErrorCodes.TransferServiceUnavailable,
                        NextExpectedChunkIndex: 0,
                        ReceivedBytes: 0,
                        RespondedAtUtc: DateTimeOffset.UtcNow),
                    receiverDeviceId: request?.SenderId,
                    sessionId: request?.SessionId,
                    correlationId: envelope.Meta.MessageId,
                    cancellationToken: cancellationToken);
                break;
            }
            case ProtocolPacketTypes.TransferCompleteRequest:
            {
                var request = DeserializePayload<FileTransferCompleteRequestDto>(envelope);
                await SendErrorEnvelopeAsync(
                    session,
                    packetType: ProtocolPacketTypes.TransferCompleteResponse,
                    errorCode: ProtocolErrorCodes.TransferServiceUnavailable,
                    errorMessage: message,
                    payload: new FileTransferCompleteResponseDto(
                        Accepted: false,
                        TransferId: request?.TransferId ?? string.Empty,
                        Status: ProtocolConstants.TransferStateFailed,
                        FailureReason: ProtocolErrorCodes.TransferServiceUnavailable,
                        SavedFilePath: null,
                        CompletedAtUtc: DateTimeOffset.UtcNow),
                    receiverDeviceId: request?.SenderId,
                    sessionId: request?.SessionId,
                    correlationId: envelope.Meta.MessageId,
                    cancellationToken: cancellationToken);
                break;
            }
            case ProtocolPacketTypes.TransferCancelRequest:
            {
                var request = DeserializePayload<FileTransferCancelRequestDto>(envelope);
                await SendErrorEnvelopeAsync(
                    session,
                    packetType: ProtocolPacketTypes.TransferCancelResponse,
                    errorCode: ProtocolErrorCodes.TransferServiceUnavailable,
                    errorMessage: message,
                    payload: new FileTransferCancelResponseDto(
                        Accepted: false,
                        TransferId: request?.TransferId ?? string.Empty,
                        Status: ProtocolConstants.TransferStateFailed,
                        FailureReason: ProtocolErrorCodes.TransferServiceUnavailable,
                        CanceledAtUtc: DateTimeOffset.UtcNow),
                    receiverDeviceId: request?.SenderId,
                    sessionId: request?.SessionId,
                    correlationId: envelope.Meta.MessageId,
                    cancellationToken: cancellationToken);
                break;
            }
        }

        await _loggerService.LogWarningAsync(message, cancellationToken);
    }

    private async Task SendResponseEnvelopeAsync<TPayload>(
        BluetoothSession session,
        string packetType,
        TPayload payload,
        string? receiverDeviceId,
        string? sessionId,
        string? correlationId,
        CancellationToken cancellationToken)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        var envelope = ProtocolEnvelopeFactory.Create(
            packetType: packetType,
            payload: payload,
            senderDeviceId: localDevice.DeviceId,
            receiverDeviceId: receiverDeviceId,
            sessionId: sessionId,
            correlationId: correlationId);

        await SendEnvelopeAsync(session, envelope, cancellationToken);
    }

    private async Task SendErrorEnvelopeAsync<TPayload>(
        BluetoothSession session,
        string packetType,
        string errorCode,
        string errorMessage,
        TPayload payload,
        string? receiverDeviceId,
        string? sessionId,
        string? correlationId,
        CancellationToken cancellationToken)
    {
        var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
        var envelope = ProtocolEnvelopeFactory.CreateError(
            packetType: packetType,
            code: errorCode,
            message: errorMessage,
            payload: payload,
            senderDeviceId: localDevice.DeviceId,
            receiverDeviceId: receiverDeviceId,
            sessionId: sessionId,
            correlationId: correlationId);

        await SendEnvelopeAsync(session, envelope, cancellationToken);
    }

    private void StartReconnectLoop()
    {
        if (_boundState is null || _reconnectPlan is null)
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
        var connectionState = _boundState;

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
                    state.IsConnected = false;
                    state.LifecycleState = ConnectionLifecycleState.Connecting;
                    state.StatusText = $"Bluetooth reconnect attempt {attempt} to {plan.PeerName} is in progress.";
                    state.HandshakeSummary = "Waiting for the remote Bluetooth peer to accept RFCOMM again.";
                });

                await _loggerService.LogInfoAsync(
                    $"Bluetooth reconnect attempt {attempt} to {plan.PeerName} started.",
                    token);

                try
                {
                    var peer = ResolveReconnectPeer(plan);
                    await ConnectToPeerAsync(connectionState, peer, plan.PairingToken, token);

                    if (connectionState.IsConnected)
                    {
                        await UpdateStateAsync(connectionState, state =>
                        {
                            state.IsReconnectScheduled = false;
                            state.ReconnectAttemptCount = 0;
                        });
                        return;
                    }
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception ex)
                {
                    await _loggerService.LogWarningAsync(
                        $"Bluetooth reconnect attempt {attempt} failed: {ex.Message}",
                        token);
                }

                await Task.Delay(TimeSpan.FromSeconds(AppConstants.ConnectionReconnectDelaySeconds), token);
            }
        }, token);
    }

    private async Task CloseActiveSessionAsync(
        string reason,
        bool scheduleReconnect,
        bool notifyRemote,
        CancellationToken cancellationToken)
    {
        var session = _activeSession;
        if (session is null)
        {
            return;
        }

        await CloseSessionAsync(session, reason, scheduleReconnect, notifyRemote, cancellationToken);
    }

    private async Task CloseSessionAsync(
        BluetoothSession session,
        string reason,
        bool scheduleReconnect,
        bool notifyRemote,
        CancellationToken cancellationToken)
    {
        if (Interlocked.Exchange(ref session.CloseRequested, 1) == 1)
        {
            return;
        }

        var isActiveSession = ReferenceEquals(_activeSession, session);
        if (isActiveSession)
        {
            _activeSession = null;
            await CancelHeartbeatLoopAsync();
        }

        if (scheduleReconnect && !session.IsIncoming)
        {
            _reconnectPlan = new ReconnectPlan(
                session.Peer.Id,
                session.Peer.DisplayName,
                session.Peer.BluetoothAddress,
                session.PairingToken);
        }

        if (notifyRemote && session.Client.Connected && !string.IsNullOrWhiteSpace(session.SessionId))
        {
            try
            {
                var localDevice = await EnsureLocalDeviceAsync(cancellationToken);
                var envelope = ProtocolEnvelopeFactory.Create(
                    packetType: ProtocolPacketTypes.ConnectionDisconnectRequest,
                    payload: new ConnectionDisconnectRequestDto(
                        SessionId: session.SessionId,
                        DeviceId: localDevice.DeviceId,
                        SentAtUtc: DateTimeOffset.UtcNow),
                    senderDeviceId: localDevice.DeviceId,
                    receiverDeviceId: session.Peer.Id,
                    sessionId: session.SessionId);

                await SendEnvelopeAsync(session, envelope, cancellationToken);
            }
            catch (Exception ex)
            {
                await _loggerService.LogWarningAsync(
                    $"Bluetooth disconnect notice could not be sent to {session.Peer.DisplayName}: {ex.Message}",
                    cancellationToken);
            }
        }

        try
        {
            session.CancellationSource.Cancel();
        }
        catch
        {
            // Ignore cleanup failures.
        }

        try
        {
            session.Stream.Dispose();
        }
        catch
        {
            // Ignore cleanup failures.
        }

        try
        {
            session.Client.Dispose();
        }
        catch
        {
            // Ignore cleanup failures.
        }

        if (session.ReadLoopTask is not null && Task.CurrentId != session.ReadLoopTask.Id)
        {
            await SafeAwaitAsync(session.ReadLoopTask);
        }

        session.CancellationSource.Dispose();
        session.SendGate.Dispose();

        if (isActiveSession)
        {
            await UpdateStateAsync(_boundState, state =>
            {
                state.IsConnected = false;
                state.SessionId = string.Empty;
                state.LifecycleState = ConnectionLifecycleState.Disconnected;
                state.StatusText = reason;
                state.LastFailure = reason;
                state.HandshakeSummary = scheduleReconnect && !session.IsIncoming
                    ? "Bluetooth reconnect will retry automatically."
                    : "Bluetooth session closed.";
                state.IsReconnectScheduled = scheduleReconnect && !session.IsIncoming;
                if (!state.IsReconnectScheduled)
                {
                    state.ReconnectAttemptCount = 0;
                }
            });

            await NotifySessionChangedAsync(cancellationToken);
        }

        await _loggerService.LogInfoAsync(
            $"Bluetooth session with {session.Peer.DisplayName} closed. {reason}",
            cancellationToken);

        if (isActiveSession && scheduleReconnect && !session.IsIncoming)
        {
            StartReconnectLoop();
        }
    }

    private async Task CancelHeartbeatLoopAsync()
    {
        var heartbeatCts = _heartbeatCts;
        var heartbeatTask = _heartbeatTask;
        _heartbeatCts = null;
        _heartbeatTask = null;

        if (heartbeatCts is null)
        {
            return;
        }

        heartbeatCts.Cancel();

        if (heartbeatTask is not null && Task.CurrentId != heartbeatTask.Id)
        {
            await SafeAwaitAsync(heartbeatTask);
        }

        heartbeatCts.Dispose();
    }

    private async Task CancelReconnectLoopAsync()
    {
        var reconnectCts = _reconnectCts;
        var reconnectTask = _reconnectTask;
        _reconnectCts = null;
        _reconnectTask = null;

        if (reconnectCts is null)
        {
            return;
        }

        reconnectCts.Cancel();

        if (reconnectTask is not null && Task.CurrentId != reconnectTask.Id)
        {
            await SafeAwaitAsync(reconnectTask);
        }

        reconnectCts.Dispose();
    }

    private async Task<LocalDeviceProfile> EnsureLocalDeviceAsync(CancellationToken cancellationToken)
    {
        _localDevice ??= await _localDeviceProfileRepository.LoadOrCreateAsync(cancellationToken);
        _localDevice.Platform = AppConstants.DesktopPlatformName;
        _localDevice.SupportedModes = [DiscoverySupportedModes.LocalLan, DiscoverySupportedModes.BluetoothFallback];
        return _localDevice;
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
        });

        await _loggerService.LogWarningAsync(message);
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

    private static string? ResolvePairingCode(string? pairingToken)
    {
        return string.IsNullOrWhiteSpace(pairingToken)
            ? null
            : pairingToken.Trim();
    }

    private BluetoothSession CreateSession(
        BluetoothClient client,
        DevicePeer peer,
        string pairingToken,
        bool isIncoming)
    {
        var stream = client.GetStream();
        if (stream.CanTimeout)
        {
            try
            {
                stream.ReadTimeout = AppConstants.BluetoothStreamReadTimeoutMilliseconds;
                stream.WriteTimeout = AppConstants.BluetoothStreamReadTimeoutMilliseconds;
            }
            catch
            {
                // Some Bluetooth stream implementations report CanTimeout but still reject timeout setters.
            }
        }

        return new BluetoothSession(
            client,
            stream,
            peer,
            pairingToken,
            isIncoming);
    }

    private async Task<(BluetoothClient? Client, string ConnectMethod, string FailureSummary)> OpenOutgoingBluetoothClientAsync(
        DevicePeer peer,
        CancellationToken cancellationToken)
    {
        var bluetoothAddress = InTheHand.Net.BluetoothAddress.Parse(peer.BluetoothAddress);
        var failures = new List<string>();

        var attempts = new (string Name, Action<BluetoothClient> Connect)[]
        {
            (
                "LocalBridge service UUID",
                client => client.Connect(bluetoothAddress, AppConstants.BluetoothServiceId)
            ),
            (
                "LocalBridge endpoint UUID",
                client => client.Connect(new BluetoothEndPoint(bluetoothAddress, AppConstants.BluetoothServiceId, -1))
            ),
            (
                "standard SerialPort RFCOMM fallback",
                client => client.Connect(new BluetoothEndPoint(bluetoothAddress, BluetoothService.SerialPort, -1))
            )
        };

        foreach (var attempt in attempts)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var client = new BluetoothClient();

            try
            {
                await Task.Run(() => attempt.Connect(client), cancellationToken);
                return (client, attempt.Name, string.Empty);
            }
            catch (Exception ex)
            {
                client.Dispose();
                var message = ex.GetBaseException().Message;
                failures.Add($"{attempt.Name}: {message}");
                await _loggerService.LogWarningAsync(
                    $"[BT-CONNECT] Outgoing Bluetooth connect attempt failed for {peer.DisplayName} via {attempt.Name}: {message}",
                    cancellationToken);
            }
        }

        return (null, string.Empty, string.Join(" | ", failures));
    }

    private static DevicePeer CreateTemporaryPeer(BluetoothClient client)
    {
        string bluetoothAddress = string.Empty;
        try
        {
            if (client.Client.RemoteEndPoint is InTheHand.Net.BluetoothEndPoint endPoint)
            {
                bluetoothAddress = endPoint.Address.ToString();
            }
        }
        catch
        {
            // Ignore endpoint inspection failures.
        }

        string displayName;
        try
        {
            displayName = client.RemoteMachineName;
        }
        catch
        {
            displayName = string.Empty;
        }

        if (string.IsNullOrWhiteSpace(displayName))
        {
            displayName = string.IsNullOrWhiteSpace(bluetoothAddress)
                ? "Bluetooth peer"
                : $"Bluetooth {bluetoothAddress}";
        }

        return new DevicePeer
        {
            Id = BuildTemporaryPeerId(bluetoothAddress),
            DisplayName = displayName,
            Platform = "Bluetooth device",
            IpAddress = string.Empty,
            Port = 0,
            BluetoothAddress = bluetoothAddress,
            AppVersion = "unknown",
            SupportedModes = [DiscoverySupportedModes.BluetoothFallback],
            TransportMode = AppConnectionMode.BluetoothFallback,
            IsTrusted = false,
            IsOnline = true,
            FirstSeenAtUtc = DateTimeOffset.UtcNow,
            LastSeenAtUtc = DateTimeOffset.UtcNow,
            HasResolvedIdentity = false
        };
    }

    private static string BuildTemporaryPeerId(string bluetoothAddress)
    {
        if (string.IsNullOrWhiteSpace(bluetoothAddress))
        {
            return $"bt-{Guid.NewGuid():N}";
        }

        return $"bt-{bluetoothAddress.Replace(':', '-').Replace(' ', '-')}";
    }

    private static DevicePeer ResolveReconnectPeer(ReconnectPlan plan)
    {
        return new DevicePeer
        {
            Id = plan.PeerId,
            DisplayName = plan.PeerName,
            Platform = "Bluetooth device",
            IpAddress = string.Empty,
            Port = 0,
            BluetoothAddress = plan.BluetoothAddress,
            AppVersion = "unknown",
            SupportedModes = [DiscoverySupportedModes.BluetoothFallback],
            TransportMode = AppConnectionMode.BluetoothFallback,
            IsTrusted = true,
            IsOnline = false,
            FirstSeenAtUtc = DateTimeOffset.UtcNow,
            LastSeenAtUtc = DateTimeOffset.UtcNow,
            HasResolvedIdentity = true
        };
    }

    private static TextChatDeliveryReceiptDto CreateFailedReceipt(
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

    private static ConnectionHeartbeatResponseDto CreateHeartbeatFailureResponse(
        LocalDeviceProfile localDevice,
        string sessionState,
        string failureReason)
    {
        return new ConnectionHeartbeatResponseDto(
            Alive: false,
            SessionState: sessionState,
            FailureReason: failureReason,
            ServerDeviceId: localDevice.DeviceId,
            ServerDeviceName: localDevice.DeviceName,
            ServerPlatform: localDevice.Platform,
            ServerAppVersion: localDevice.AppVersion,
            ReceivedAtUtc: DateTimeOffset.UtcNow);
    }

    private static TPayload? DeserializePayload<TPayload>(ProtocolEnvelope<JsonElement> envelope)
    {
        return envelope.Payload.ValueKind is JsonValueKind.Undefined or JsonValueKind.Null
            ? default
            : envelope.Payload.Deserialize<TPayload>(JsonDefaults.Options);
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

    private static async Task UpdateStateAsync(ConnectionStateModel? connectionState, Action<ConnectionStateModel> update)
    {
        if (connectionState is null)
        {
            return;
        }

        var dispatcher = Application.Current?.Dispatcher;

        if (dispatcher is null || dispatcher.CheckAccess())
        {
            update(connectionState);
            return;
        }

        await dispatcher.InvokeAsync(() => update(connectionState));
    }

    private sealed class BluetoothSession
    {
        public BluetoothSession(
            BluetoothClient client,
            Stream stream,
            DevicePeer peer,
            string pairingToken,
            bool isIncoming)
        {
            Client = client;
            Stream = stream;
            Peer = peer;
            PairingToken = pairingToken;
            IsIncoming = isIncoming;
        }

        public BluetoothClient Client { get; }

        public Stream Stream { get; }

        public DevicePeer Peer { get; set; }

        public string SessionId { get; set; } = string.Empty;

        public string PairingToken { get; set; }

        public bool IsIncoming { get; }

        public DateTimeOffset LastHeartbeatUtc { get; set; } = DateTimeOffset.UtcNow;

        public CancellationTokenSource CancellationSource { get; } = new();

        public SemaphoreSlim SendGate { get; } = new(1, 1);

        public ConcurrentDictionary<string, TaskCompletionSource<ProtocolEnvelope<JsonElement>?>> PendingResponses { get; } =
            new(StringComparer.OrdinalIgnoreCase);

        public Task? ReadLoopTask { get; set; }

        public int CloseRequested;
    }

    private sealed record ReconnectPlan(
        string PeerId,
        string PeerName,
        string BluetoothAddress,
        string PairingToken);
}
