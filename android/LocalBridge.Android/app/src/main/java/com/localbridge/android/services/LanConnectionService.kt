package com.localbridge.android.services

import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.core.network.ProtocolHttpClient
import com.localbridge.android.core.network.LocalNetworkResolver
import com.localbridge.android.core.network.local.LocalHttpHostService
import com.localbridge.android.core.network.local.LocalHttpRequest
import com.localbridge.android.core.network.local.LocalHttpResponse
import com.localbridge.android.core.protocol.ConnectionDisconnectRequestDto
import com.localbridge.android.core.protocol.ConnectionDisconnectResponseDto
import com.localbridge.android.core.protocol.ConnectionHandshakeRequestDto
import com.localbridge.android.core.protocol.ConnectionHandshakeResponseDto
import com.localbridge.android.core.protocol.ConnectionHeartbeatRequestDto
import com.localbridge.android.core.protocol.ConnectionHeartbeatResponseDto
import com.localbridge.android.core.protocol.ProtocolConstants
import com.localbridge.android.core.protocol.ProtocolEnvelopeFactory
import com.localbridge.android.core.protocol.ProtocolEnvelopeValidator
import com.localbridge.android.core.protocol.ProtocolErrorCodes
import com.localbridge.android.core.protocol.ProtocolError
import com.localbridge.android.core.protocol.ProtocolEnvelope
import com.localbridge.android.core.protocol.ProtocolJson
import com.localbridge.android.core.protocol.ProtocolMetadata
import com.localbridge.android.core.protocol.ProtocolPacketTypes
import com.localbridge.android.core.protocol.StatusResponseDto
import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.ConnectionLifecycleState
import com.localbridge.android.models.ConnectionStateModel
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.models.LocalDeviceProfile
import com.localbridge.android.repositories.DeviceRepository
import com.localbridge.android.repositories.LocalDeviceProfileRepository
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer

class LanConnectionService(
    private val deviceRepository: DeviceRepository,
    private val trustedDevicesService: TrustedDevicesService,
    private val localDeviceProfileRepository: LocalDeviceProfileRepository,
    private val localHttpHostService: LocalHttpHostService,
    private val loggerService: LoggerService
) : ConnectionService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private val _connectionState = MutableStateFlow(
        ConnectionStateModel(
            protocolVersion = AppConstants.protocolVersion,
            localPairingCode = generatePairingCode(),
            handshakeSummary = "Share this six-digit code with Windows when the desktop opens a hotspot/LAN session."
        )
    )
    private val _activePeer = MutableStateFlow<DevicePeer?>(null)

    private var activeConnection: ActiveConnection? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectPlan: ReconnectPlan? = null
    private var isStarted = false

    override val connectionState: StateFlow<ConnectionStateModel> = _connectionState.asStateFlow()
    override val activePeer: StateFlow<DevicePeer?> = _activePeer.asStateFlow()

    override fun start() {
        if (isStarted) {
            return
        }

        isStarted = true
        localHttpHostService.register("GET", AppConstants.connectionStatusPath, ::handleStatusRequest)
        localHttpHostService.register("POST", AppConstants.connectionHandshakePath, ::handleHandshakeRequest)
        localHttpHostService.register("POST", AppConstants.connectionHeartbeatPath, ::handleHeartbeatRequest)
        localHttpHostService.register("POST", AppConstants.connectionDisconnectPath, ::handleDisconnectRequest)
        loggerService.info(
            "[SESSION] Android LAN connection host registered on TCP ${AppConstants.defaultApiPort}. " +
                "Current LAN pairing code: ${_connectionState.value.localPairingCode}."
        )
    }

    override fun connect(peer: DevicePeer, pairingToken: String) {
        scope.launch {
            connectMutex.withLock {
                if (activeConnection?.peer?.id == peer.id && connectionState.value.isConnected) {
                    _connectionState.update { current ->
                        current.copy(
                            lifecycleState = ConnectionLifecycleState.Connected,
                            statusText = "Already connected to ${peer.displayName}.",
                            connectedPeer = peer,
                            lastError = null
                        )
                    }
                    return@withLock
                }

                cancelReconnectLoopLocked()
                reconnectPlan = null

                if (activeConnection != null) {
                    disconnectInternal(
                        notifyRemote = true,
                        keepPeerVisible = false,
                        statusText = "Switching to ${peer.displayName}."
                    )
                }

                attemptConnection(peer, pairingToken.trim(), isReconnectAttempt = false)
            }
        }
    }

    override fun disconnect() {
        scope.launch {
            connectMutex.withLock {
                reconnectPlan = null
                cancelReconnectLoopLocked()
                disconnectInternal(
                    notifyRemote = true,
                    keepPeerVisible = false,
                    statusText = "Disconnected from the local peer."
                )
            }
        }
    }

    private suspend fun attemptConnection(
        peer: DevicePeer,
        pairingToken: String,
        isReconnectAttempt: Boolean
    ): ConnectAttemptOutcome {
        val localDevice = localDeviceProfileRepository.getOrCreate()

        if (pairingToken.isBlank()) {
            _connectionState.update { current ->
                current.copy(
                    lifecycleState = ConnectionLifecycleState.WaitingForPairing,
                    statusText = "Enter the confirmation code shown on ${peer.displayName}.",
                    connectedPeer = peer,
                    sessionId = null,
                    selectedMode = AppConnectionMode.LocalWifiLan,
                    lastError = ProtocolErrorCodes.pairingTokenRequired,
                    handshakeSummary = "A pairing token is required before Android can connect to this Windows peer.",
                    isReconnectScheduled = false
                )
            }
            loggerService.warning("[PAIRING] Connection to ${peer.displayName} is waiting for a pairing token.")
            return ConnectAttemptOutcome.WaitingForPairing
        }

        cancelHeartbeatLoopLocked()

        _connectionState.update { current ->
            current.copy(
                lifecycleState = ConnectionLifecycleState.Connecting,
                statusText = if (isReconnectAttempt) {
                    "Retrying local connection to ${peer.displayName}..."
                } else {
                    "Connecting to ${peer.displayName} over the local network..."
                },
                connectedPeer = peer,
                sessionId = null,
                selectedMode = AppConnectionMode.LocalWifiLan,
                lastError = null,
                handshakeSummary = if (isReconnectAttempt) {
                    "Reconnect attempt ${current.reconnectAttemptCount + 1} is preparing a handshake."
                } else {
                    "Preparing handshake with ${peer.displayName}."
                }
            )
        }

        loggerService.info(
            if (isReconnectAttempt) {
                "[RECONNECT] Attempt started for ${peer.displayName} at ${peer.ipAddress}:${peer.port}."
            } else {
                "[PAIRING] Starting handshake with Windows peer ${peer.displayName} at ${peer.ipAddress}:${peer.port}."
            }
        )

        val handshakeResponse = try {
            val envelope = ProtocolEnvelopeFactory.create(
                packetType = ProtocolPacketTypes.connectionHandshakeRequest,
                payload = ConnectionHandshakeRequestDto(
                    deviceId = localDevice.deviceId,
                    deviceName = localDevice.deviceName,
                    platform = localDevice.platform,
                    appVersion = localDevice.appVersion,
                    pairingToken = pairingToken,
                    supportedModes = localDevice.supportedModes
                ),
                senderDeviceId = localDevice.deviceId,
                receiverDeviceId = peer.id,
                sentAtUtc = Instant.now().toString()
            )

            ProtocolHttpClient.postEnvelope<ConnectionHandshakeRequestDto, ConnectionHandshakeResponseDto>(
                url = buildPeerUrl(peer, AppConstants.connectionHandshakePath),
                envelope = envelope,
                timeoutMillis = AppConstants.connectionRequestTimeoutMillis
            )
        } catch (_: SocketTimeoutException) {
            return handleConnectFailure(
                peer = peer,
                failureReason = "handshake_timeout",
                waitingForPairing = false
            )
        } catch (exception: IOException) {
            return handleConnectFailure(
                peer = peer,
                failureReason = exception.message ?: "network_io_error",
                waitingForPairing = false
            )
        } catch (exception: Exception) {
            return handleConnectFailure(
                peer = peer,
                failureReason = exception.message ?: "connection_failed",
                waitingForPairing = false
            )
        }

        val handshakeValidation = ProtocolEnvelopeValidator.validate(
            handshakeResponse.envelope,
            expectedPacketTypes = *arrayOf(ProtocolPacketTypes.connectionHandshakeResponse)
        )
        val payload = if (handshakeValidation.isValid) handshakeResponse.envelope?.payload else null

        if (payload != null && !payload.accepted) {
            val failureReason = payload.failureReason
                ?: handshakeResponse.envelope?.error?.code
                ?: "handshake_rejected"
            val waitingForPairing = payload.sessionState == ProtocolConstants.sessionStateWaitingForPairing ||
                failureReason == ProtocolErrorCodes.pairingTokenRequired ||
                failureReason == ProtocolErrorCodes.invalidPairingToken
            return handleConnectFailure(peer, failureReason, waitingForPairing)
        }

        if (handshakeResponse.statusCode !in 200..299 || payload == null) {
            val failureReason = handshakeResponse.envelope?.error?.code
                ?: handshakeValidation.errorCode
                ?: "handshake_http_${handshakeResponse.statusCode}"
            return handleConnectFailure(peer, failureReason, waitingForPairing = false)
        }

        val pairedPeer = peer.copy(
            displayName = payload.serverDeviceName,
            platform = payload.serverPlatform,
            appVersion = payload.serverAppVersion,
            supportedModes = payload.supportedModes,
            isTrusted = true,
            isOnline = true,
            lastSeenAtUtc = payload.issuedAtUtc
        )
        val sessionId = payload.sessionId ?: UUID.randomUUID().toString().replace("-", "")
        activeConnection = ActiveConnection(
            sessionId = sessionId,
            peer = pairedPeer,
            pairingToken = pairingToken
        )
        _activePeer.value = pairedPeer

        _connectionState.update { current ->
            current.copy(
                lifecycleState = ConnectionLifecycleState.Paired,
                statusText = "Paired with ${pairedPeer.displayName}. Validating the local session...",
                sessionId = sessionId,
                connectedPeer = pairedPeer,
                lastError = null,
                handshakeSummary = "Handshake accepted. Session $sessionId is waiting for heartbeat validation.",
                isReconnectScheduled = false
            )
        }

        loggerService.info("[PAIRING] Handshake accepted by ${pairedPeer.displayName}. Session $sessionId created.")

        val heartbeatResult = sendHeartbeat(activeConnection!!, transitionToConnected = true)
        if (heartbeatResult !is HeartbeatOutcome.Success) {
            val reason = (heartbeatResult as? HeartbeatOutcome.Failure)?.reason ?: "heartbeat_failed"
            activeConnection = null
            _activePeer.value = null
            return handleConnectFailure(
                peer = pairedPeer,
                failureReason = reason,
                waitingForPairing = false
            )
        }

        val updatedTrustedIds = trustedDevicesService.trustedDeviceIds.value + pairedPeer.id
        trustedDevicesService.trust(pairedPeer.id)
        deviceRepository.upsert(pairedPeer.copy(isTrusted = true))
        deviceRepository.updateTrust(updatedTrustedIds)

        reconnectPlan = ReconnectPlan(
            peer = pairedPeer.copy(isTrusted = true),
            pairingToken = pairingToken
        )
        startHeartbeatLoopLocked()

        return ConnectAttemptOutcome.Connected
    }

    private suspend fun handleConnectFailure(
        peer: DevicePeer,
        failureReason: String,
        waitingForPairing: Boolean
    ): ConnectAttemptOutcome {
        val lifecycleState = if (waitingForPairing) {
            ConnectionLifecycleState.WaitingForPairing
        } else {
            ConnectionLifecycleState.Failed
        }

        _connectionState.update { current ->
            current.copy(
                lifecycleState = lifecycleState,
                statusText = if (waitingForPairing) {
                    "Pairing confirmation is required by ${peer.displayName}."
                } else {
                    "Could not connect to ${peer.displayName}."
                },
                sessionId = null,
                connectedPeer = peer,
                lastError = failureReason,
                handshakeSummary = failureReason.replace('_', ' '),
                isReconnectScheduled = false
            )
        }

        loggerService.warning("Connection attempt to ${peer.displayName} failed: $failureReason.")
        return if (waitingForPairing) {
            ConnectAttemptOutcome.WaitingForPairing
        } else {
            ConnectAttemptOutcome.Failed
        }
    }

    private suspend fun sendHeartbeat(
        connection: ActiveConnection,
        transitionToConnected: Boolean
    ): HeartbeatOutcome {
        val localDevice = localDeviceProfileRepository.getOrCreate()

        val response = try {
            val envelope = ProtocolEnvelopeFactory.create(
                packetType = ProtocolPacketTypes.connectionHeartbeatRequest,
                payload = ConnectionHeartbeatRequestDto(
                    sessionId = connection.sessionId,
                    deviceId = localDevice.deviceId,
                    deviceName = localDevice.deviceName,
                    platform = localDevice.platform,
                    appVersion = localDevice.appVersion
                ),
                senderDeviceId = localDevice.deviceId,
                receiverDeviceId = connection.peer.id,
                sessionId = connection.sessionId,
                sentAtUtc = Instant.now().toString()
            )

            ProtocolHttpClient.postEnvelope<ConnectionHeartbeatRequestDto, ConnectionHeartbeatResponseDto>(
                url = buildPeerUrl(connection.peer, AppConstants.connectionHeartbeatPath),
                envelope = envelope,
                timeoutMillis = AppConstants.connectionRequestTimeoutMillis
            )
        } catch (_: SocketTimeoutException) {
            return HeartbeatOutcome.Failure("heartbeat_timeout")
        } catch (exception: IOException) {
            return HeartbeatOutcome.Failure(exception.message ?: "heartbeat_io_error")
        } catch (exception: Exception) {
            return HeartbeatOutcome.Failure(exception.message ?: "heartbeat_failed")
        }

        val validation = ProtocolEnvelopeValidator.validate(
            response.envelope,
            expectedPacketTypes = *arrayOf(ProtocolPacketTypes.connectionHeartbeatResponse)
        )
        val payload = if (validation.isValid) response.envelope?.payload else null

        if (payload == null || response.statusCode !in 200..299 || !payload.alive) {
            val failureReason = payload?.failureReason
                ?: response.envelope?.error?.code
                ?: validation.errorCode
                ?: "heartbeat_http_${response.statusCode}"
            loggerService.warning("[SESSION] Heartbeat rejected by ${connection.peer.displayName}: $failureReason.")
            return HeartbeatOutcome.Failure(failureReason)
        }

        val connectedPeer = connection.peer.copy(
            displayName = payload.serverDeviceName,
            platform = payload.serverPlatform,
            appVersion = payload.serverAppVersion,
            isTrusted = true,
            isOnline = true,
            lastSeenAtUtc = payload.receivedAtUtc
        )
        activeConnection = connection.copy(peer = connectedPeer)
        _activePeer.value = connectedPeer
        deviceRepository.upsert(connectedPeer)

        if (transitionToConnected) {
            _connectionState.update { current ->
                current.copy(
                    lifecycleState = ConnectionLifecycleState.Connected,
                    statusText = "Connected to ${connectedPeer.displayName} over hotspot/LAN.",
                    sessionId = connection.sessionId,
                    connectedPeer = connectedPeer,
                    lastError = null,
                    handshakeSummary = "Heartbeat validated session ${connection.sessionId}.",
                    isReconnectScheduled = false,
                    reconnectAttemptCount = 0
                )
            }
            loggerService.info("[SESSION] Heartbeat validated session ${connection.sessionId} with ${connectedPeer.displayName}.")
        }

        return HeartbeatOutcome.Success
    }

    private fun startHeartbeatLoopLocked() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(AppConstants.connectionHeartbeatIntervalSeconds * 1_000L)

                val snapshot = connectMutex.withLock { activeConnection } ?: break
                val result = connectMutex.withLock {
                    sendHeartbeat(snapshot, transitionToConnected = false)
                }

                if (result is HeartbeatOutcome.Failure) {
                    connectMutex.withLock {
                        handleConnectionDrop(snapshot, result.reason)
                    }
                    break
                }
            }
        }
    }

    private suspend fun handleConnectionDrop(connection: ActiveConnection, reason: String) {
        activeConnection = null
        _activePeer.value = null
        reconnectPlan = if (connection.isIncoming) {
            null
        } else {
            ReconnectPlan(connection.peer, connection.pairingToken)
        }

        _connectionState.update { current ->
            current.copy(
                lifecycleState = ConnectionLifecycleState.Disconnected,
                statusText = "Lost connection with ${connection.peer.displayName}.",
                sessionId = null,
                connectedPeer = connection.peer,
                lastError = reason,
                handshakeSummary = "Heartbeat stopped responding. Android will try to reconnect automatically.",
                isReconnectScheduled = true
            )
        }

        if (connection.isIncoming) {
            loggerService.warning("[SESSION] Incoming hotspot/LAN session from ${connection.peer.displayName} closed: $reason.")
            return
        }

        loggerService.warning("[RECONNECT] Local connection dropped for ${connection.peer.displayName}: $reason.")
        loggerService.info(
            "[RECONNECT] Android will retry ${connection.peer.displayName} in ${AppConstants.connectionReconnectDelayMillis / 1000L} second(s)."
        )
        startReconnectLoopLocked()
    }

    private fun startReconnectLoopLocked() {
        if (reconnectJob?.isActive == true) {
            return
        }

        val plan = reconnectPlan ?: return
        reconnectJob = scope.launch {
            var attempt = 0

            while (isActive) {
                delay(AppConstants.connectionReconnectDelayMillis)
                attempt += 1

                val latestPeer = deviceRepository.devices.value.firstOrNull { peer ->
                    peer.id == plan.peer.id
                } ?: plan.peer

                _connectionState.update { current ->
                    current.copy(
                        lifecycleState = ConnectionLifecycleState.Connecting,
                        statusText = "Reconnect attempt $attempt to ${latestPeer.displayName}...",
                        connectedPeer = latestPeer,
                        lastError = current.lastError,
                        handshakeSummary = "Retrying the Localink handshake over the local network.",
                        isReconnectScheduled = true,
                        reconnectAttemptCount = attempt
                    )
                }
                loggerService.info(
                    "[RECONNECT] Android attempt $attempt started for ${latestPeer.displayName} at ${latestPeer.ipAddress}:${latestPeer.port}."
                )

                val result = connectMutex.withLock {
                    attemptConnection(latestPeer, plan.pairingToken, isReconnectAttempt = true)
                }

                when (result) {
                    ConnectAttemptOutcome.Connected -> {
                        reconnectJob = null
                        return@launch
                    }

                    ConnectAttemptOutcome.WaitingForPairing -> {
                        _connectionState.update { current ->
                            current.copy(
                                isReconnectScheduled = false,
                                reconnectAttemptCount = attempt
                            )
                        }
                        reconnectJob = null
                        return@launch
                    }

                    ConnectAttemptOutcome.Failed -> {
                        _connectionState.update { current ->
                            current.copy(
                                lifecycleState = ConnectionLifecycleState.Disconnected,
                                statusText = "Reconnect attempt $attempt failed. Retrying soon...",
                                connectedPeer = latestPeer,
                                isReconnectScheduled = true,
                                reconnectAttemptCount = attempt
                            )
                        }
                        loggerService.warning(
                            "[RECONNECT] Android attempt $attempt failed for ${latestPeer.displayName}. " +
                                "Retrying in ${AppConstants.connectionReconnectDelayMillis / 1000L} second(s)."
                        )
                    }
                }
            }
        }
    }

    private suspend fun disconnectInternal(
        notifyRemote: Boolean,
        keepPeerVisible: Boolean,
        statusText: String
    ) {
        val connection = activeConnection
        activeConnection = null
        _activePeer.value = null
        cancelHeartbeatLoopLocked()

        if (notifyRemote && connection != null) {
            sendDisconnect(connection)
        }

        _connectionState.update { current ->
            current.copy(
                lifecycleState = ConnectionLifecycleState.Disconnected,
                statusText = if (connection == null) {
                    "No active local connection."
                } else {
                    statusText.replace("the local peer", connection.peer.displayName)
                },
                sessionId = null,
                connectedPeer = if (keepPeerVisible) connection?.peer else null,
                lastError = null,
                handshakeSummary = "The local session is closed.",
                isReconnectScheduled = false,
                reconnectAttemptCount = 0
            )
        }

        if (connection != null) {
            loggerService.info("Disconnected from ${connection.peer.displayName}.")
        }
    }

    private suspend fun sendDisconnect(connection: ActiveConnection) {
        val localDevice = localDeviceProfileRepository.getOrCreate()

        runCatching {
            val envelope = ProtocolEnvelopeFactory.create(
                packetType = ProtocolPacketTypes.connectionDisconnectRequest,
                payload = ConnectionDisconnectRequestDto(
                    sessionId = connection.sessionId,
                    deviceId = localDevice.deviceId,
                    sentAtUtc = Instant.now().toString()
                ),
                senderDeviceId = localDevice.deviceId,
                receiverDeviceId = connection.peer.id,
                sessionId = connection.sessionId,
                sentAtUtc = Instant.now().toString()
            )

            ProtocolHttpClient.postEnvelope<ConnectionDisconnectRequestDto, ConnectionDisconnectResponseDto>(
                url = buildPeerUrl(connection.peer, AppConstants.connectionDisconnectPath),
                envelope = envelope,
                timeoutMillis = AppConstants.connectionRequestTimeoutMillis
            )
        }.onFailure { exception ->
            loggerService.warning(
                "[SESSION] Best-effort disconnect request to ${connection.peer.displayName} did not complete: ${exception.message}"
            )
        }
    }

    private suspend fun handleStatusRequest(request: LocalHttpRequest): LocalHttpResponse {
        val localDevice = localDeviceProfileRepository.getOrCreate()
        return protocolSuccessResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            packetType = ProtocolPacketTypes.connectionStatus,
            payload = StatusResponseDto(
                serverDeviceId = localDevice.deviceId,
                serverName = localDevice.deviceName,
                pairingCode = _connectionState.value.localPairingCode,
                apiPort = AppConstants.defaultApiPort,
                discoveryPort = AppConstants.defaultDiscoveryPort,
                localAddresses = LocalNetworkResolver.getLocalIpv4Addresses().mapNotNull { it.hostAddress }
            ),
            payloadSerializer = StatusResponseDto.serializer(),
            localDevice = localDevice,
            receiverDeviceId = null,
            sessionId = null,
            correlationId = null
        )
    }

    private suspend fun handleHandshakeRequest(request: LocalHttpRequest): LocalHttpResponse {
        val localDevice = localDeviceProfileRepository.getOrCreate()
        val envelope = runCatching {
            ProtocolJson.format.decodeFromString(
                ProtocolEnvelope.serializer(ConnectionHandshakeRequestDto.serializer()),
                request.bodyText
            )
        }.getOrNull()
        val payload = envelope?.payload
        val correlationId = envelope?.meta?.messageId
        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            expectedPacketTypes = *arrayOf(ProtocolPacketTypes.connectionHandshakeRequest)
        )

        if (!validation.isValid) {
            return protocolErrorResponse(
                statusCode = if (validation.errorCode == ProtocolErrorCodes.protocolMismatch) 426 else 400,
                reasonPhrase = if (validation.errorCode == ProtocolErrorCodes.protocolMismatch) "Upgrade Required" else "Bad Request",
                packetType = ProtocolPacketTypes.connectionHandshakeResponse,
                payload = createHandshakeFailureResponse(
                    localDevice = localDevice,
                    sessionState = ProtocolConstants.sessionStateFailed,
                    failureReason = validation.errorCode ?: ProtocolErrorCodes.invalidRequest
                ),
                payloadSerializer = ConnectionHandshakeResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = payload?.deviceId,
                sessionId = null,
                correlationId = correlationId,
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidRequest,
                errorMessage = validation.errorMessage ?: "Handshake request is malformed."
            )
        }

        val packet = envelope!!.payload!!
        if (packet.deviceId.isBlank() ||
            packet.deviceName.isBlank() ||
            packet.platform.isBlank() ||
            packet.appVersion.isBlank()
        ) {
            return protocolErrorResponse(
                statusCode = 400,
                reasonPhrase = "Bad Request",
                packetType = ProtocolPacketTypes.connectionHandshakeResponse,
                payload = createHandshakeFailureResponse(
                    localDevice = localDevice,
                    sessionState = ProtocolConstants.sessionStateFailed,
                    failureReason = ProtocolErrorCodes.invalidRequest
                ),
                payloadSerializer = ConnectionHandshakeResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.deviceId.ifBlank { null },
                sessionId = null,
                correlationId = correlationId,
                errorCode = ProtocolErrorCodes.invalidRequest,
                errorMessage = "Handshake request is missing required metadata."
            )
        }

        if (packet.deviceId.equals(localDevice.deviceId, ignoreCase = true)) {
            return protocolErrorResponse(
                statusCode = 409,
                reasonPhrase = "Conflict",
                packetType = ProtocolPacketTypes.connectionHandshakeResponse,
                payload = createHandshakeFailureResponse(
                    localDevice = localDevice,
                    sessionState = ProtocolConstants.sessionStateFailed,
                    failureReason = ProtocolErrorCodes.selfConnectionNotAllowed
                ),
                payloadSerializer = ConnectionHandshakeResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.deviceId,
                sessionId = null,
                correlationId = correlationId,
                errorCode = ProtocolErrorCodes.selfConnectionNotAllowed,
                errorMessage = "Android cannot open a Localink session to itself."
            )
        }

        val pairingToken = packet.pairingToken.trim()
        if (pairingToken.isBlank() || pairingToken != _connectionState.value.localPairingCode) {
            val errorCode = if (pairingToken.isBlank()) {
                ProtocolErrorCodes.pairingTokenRequired
            } else {
                ProtocolErrorCodes.invalidPairingToken
            }
            loggerService.warning(
                "[PAIRING] Android rejected ${packet.deviceName} because the hotspot/LAN pairing code was invalid."
            )
            return protocolErrorResponse(
                statusCode = 409,
                reasonPhrase = "Conflict",
                packetType = ProtocolPacketTypes.connectionHandshakeResponse,
                payload = createHandshakeFailureResponse(
                    localDevice = localDevice,
                    sessionState = ProtocolConstants.sessionStateWaitingForPairing,
                    failureReason = errorCode
                ),
                payloadSerializer = ConnectionHandshakeResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.deviceId,
                sessionId = null,
                correlationId = correlationId,
                errorCode = errorCode,
                errorMessage = if (errorCode == ProtocolErrorCodes.pairingTokenRequired) {
                    "The Android hotspot/LAN pairing code is required."
                } else {
                    "The supplied Android hotspot/LAN pairing code is not valid on this device."
                }
            )
        }

        val currentConnection = activeConnection
        if (currentConnection != null &&
            !currentConnection.peer.id.equals(packet.deviceId, ignoreCase = true)
        ) {
            return protocolErrorResponse(
                statusCode = 409,
                reasonPhrase = "Conflict",
                packetType = ProtocolPacketTypes.connectionHandshakeResponse,
                payload = createHandshakeFailureResponse(
                    localDevice = localDevice,
                    sessionState = ProtocolConstants.sessionStateFailed,
                    failureReason = ProtocolErrorCodes.invalidRequest
                ),
                payloadSerializer = ConnectionHandshakeResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.deviceId,
                sessionId = null,
                correlationId = correlationId,
                errorCode = ProtocolErrorCodes.invalidRequest,
                errorMessage = "Another hotspot/LAN session is already active on Android."
            )
        }

        cancelReconnectLoopLocked()
        cancelHeartbeatLoopLocked()
        reconnectPlan = null

        val now = Instant.now().toString()
        val knownPeer = deviceRepository.devices.value.firstOrNull { peer ->
            peer.id.equals(packet.deviceId, ignoreCase = true)
        }
        val resolvedPeer = (knownPeer ?: DevicePeer(
            id = packet.deviceId,
            displayName = packet.deviceName,
            platform = packet.platform,
            ipAddress = request.remoteAddress.orEmpty(),
            port = AppConstants.defaultApiPort,
            appVersion = packet.appVersion,
            supportedModes = packet.supportedModes,
            transportMode = AppConnectionMode.LocalWifiLan,
            isTrusted = true,
            isOnline = true,
            pairingRequired = false,
            lastSeenAtUtc = now
        )).copy(
            displayName = packet.deviceName,
            platform = packet.platform,
            ipAddress = request.remoteAddress ?: knownPeer?.ipAddress.orEmpty(),
            port = knownPeer?.port ?: AppConstants.defaultApiPort,
            appVersion = packet.appVersion,
            supportedModes = packet.supportedModes,
            transportMode = AppConnectionMode.LocalWifiLan,
            isTrusted = true,
            isOnline = true,
            pairingRequired = false,
            lastSeenAtUtc = now
        )
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        activeConnection = ActiveConnection(
            sessionId = sessionId,
            peer = resolvedPeer,
            pairingToken = pairingToken,
            isIncoming = true
        )
        _activePeer.value = resolvedPeer

        trustedDevicesService.trust(resolvedPeer.id)
        deviceRepository.upsert(resolvedPeer)
        deviceRepository.updateTrust(trustedDevicesService.trustedDeviceIds.value + resolvedPeer.id)

        _connectionState.update { current ->
            current.copy(
                lifecycleState = ConnectionLifecycleState.Paired,
                statusText = "Accepted hotspot/LAN pairing from ${resolvedPeer.displayName}.",
                sessionId = sessionId,
                connectedPeer = resolvedPeer,
                selectedMode = AppConnectionMode.LocalWifiLan,
                localPairingCode = current.localPairingCode,
                lastError = null,
                handshakeSummary = "Incoming hotspot/LAN session $sessionId paired successfully.",
                isReconnectScheduled = false,
                reconnectAttemptCount = 0
            )
        }

        loggerService.info(
            "[PAIRING] Android accepted hotspot/LAN pairing from ${resolvedPeer.displayName} at ${resolvedPeer.ipAddress}. Session $sessionId."
        )
        return protocolSuccessResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            packetType = ProtocolPacketTypes.connectionHandshakeResponse,
            payload = ConnectionHandshakeResponseDto(
                accepted = true,
                sessionState = ProtocolConstants.sessionStatePaired,
                sessionId = sessionId,
                failureReason = null,
                serverDeviceId = localDevice.deviceId,
                serverDeviceName = localDevice.deviceName,
                serverPlatform = localDevice.platform,
                serverAppVersion = localDevice.appVersion,
                supportedModes = localDevice.supportedModes,
                issuedAtUtc = now
            ),
            payloadSerializer = ConnectionHandshakeResponseDto.serializer(),
            localDevice = localDevice,
            receiverDeviceId = packet.deviceId,
            sessionId = sessionId,
            correlationId = correlationId
        )
    }

    private suspend fun handleHeartbeatRequest(request: LocalHttpRequest): LocalHttpResponse {
        val localDevice = localDeviceProfileRepository.getOrCreate()
        val envelope = runCatching {
            ProtocolJson.format.decodeFromString(
                ProtocolEnvelope.serializer(ConnectionHeartbeatRequestDto.serializer()),
                request.bodyText
            )
        }.getOrNull()
        val payload = envelope?.payload
        val correlationId = envelope?.meta?.messageId
        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            expectedPacketTypes = *arrayOf(ProtocolPacketTypes.connectionHeartbeatRequest)
        )

        if (!validation.isValid) {
            return protocolErrorResponse(
                statusCode = if (validation.errorCode == ProtocolErrorCodes.protocolMismatch) 426 else 400,
                reasonPhrase = if (validation.errorCode == ProtocolErrorCodes.protocolMismatch) "Upgrade Required" else "Bad Request",
                packetType = ProtocolPacketTypes.connectionHeartbeatResponse,
                payload = createHeartbeatFailureResponse(
                    localDevice = localDevice,
                    sessionState = ProtocolConstants.sessionStateFailed,
                    failureReason = validation.errorCode ?: ProtocolErrorCodes.invalidRequest
                ),
                payloadSerializer = ConnectionHeartbeatResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = payload?.deviceId,
                sessionId = payload?.sessionId,
                correlationId = correlationId,
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidRequest,
                errorMessage = validation.errorMessage ?: "Heartbeat request is malformed."
            )
        }

        val packet = envelope!!.payload!!
        if (packet.sessionId.isBlank() || packet.deviceId.isBlank()) {
            return protocolErrorResponse(
                statusCode = 400,
                reasonPhrase = "Bad Request",
                packetType = ProtocolPacketTypes.connectionHeartbeatResponse,
                payload = createHeartbeatFailureResponse(
                    localDevice = localDevice,
                    sessionState = ProtocolConstants.sessionStateFailed,
                    failureReason = ProtocolErrorCodes.invalidRequest
                ),
                payloadSerializer = ConnectionHeartbeatResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.deviceId.ifBlank { null },
                sessionId = packet.sessionId.ifBlank { null },
                correlationId = correlationId,
                errorCode = ProtocolErrorCodes.invalidRequest,
                errorMessage = "Heartbeat request is missing the session or device id."
            )
        }

        val connection = activeConnection
        if (connection == null ||
            connection.sessionId != packet.sessionId ||
            !connection.peer.id.equals(packet.deviceId, ignoreCase = true)
        ) {
            return protocolErrorResponse(
                statusCode = 404,
                reasonPhrase = "Not Found",
                packetType = ProtocolPacketTypes.connectionHeartbeatResponse,
                payload = createHeartbeatFailureResponse(
                    localDevice = localDevice,
                    sessionState = ProtocolConstants.sessionStateDisconnected,
                    failureReason = ProtocolErrorCodes.sessionNotFound
                ),
                payloadSerializer = ConnectionHeartbeatResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.deviceId,
                sessionId = packet.sessionId,
                correlationId = correlationId,
                errorCode = ProtocolErrorCodes.sessionNotFound,
                errorMessage = "The hotspot/LAN session is not active on this Android host."
            )
        }

        val now = Instant.now().toString()
        val resolvedPeer = connection.peer.copy(
            displayName = packet.deviceName,
            platform = packet.platform,
            ipAddress = request.remoteAddress ?: connection.peer.ipAddress,
            appVersion = packet.appVersion,
            transportMode = AppConnectionMode.LocalWifiLan,
            isTrusted = true,
            isOnline = true,
            pairingRequired = false,
            lastSeenAtUtc = now
        )
        activeConnection = connection.copy(peer = resolvedPeer)
        _activePeer.value = resolvedPeer
        deviceRepository.upsert(resolvedPeer)

        _connectionState.update { current ->
            current.copy(
                lifecycleState = ConnectionLifecycleState.Connected,
                statusText = "Connected to ${resolvedPeer.displayName} over hotspot/LAN.",
                sessionId = connection.sessionId,
                connectedPeer = resolvedPeer,
                selectedMode = AppConnectionMode.LocalWifiLan,
                localPairingCode = current.localPairingCode,
                lastError = null,
                handshakeSummary = "Heartbeat validated hotspot/LAN session ${connection.sessionId}.",
                isReconnectScheduled = false,
                reconnectAttemptCount = 0
            )
        }

        return protocolSuccessResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            packetType = ProtocolPacketTypes.connectionHeartbeatResponse,
            payload = ConnectionHeartbeatResponseDto(
                alive = true,
                sessionState = ProtocolConstants.sessionStateConnected,
                failureReason = null,
                serverDeviceId = localDevice.deviceId,
                serverDeviceName = localDevice.deviceName,
                serverPlatform = localDevice.platform,
                serverAppVersion = localDevice.appVersion,
                receivedAtUtc = now
            ),
            payloadSerializer = ConnectionHeartbeatResponseDto.serializer(),
            localDevice = localDevice,
            receiverDeviceId = packet.deviceId,
            sessionId = connection.sessionId,
            correlationId = correlationId
        )
    }

    private suspend fun handleDisconnectRequest(request: LocalHttpRequest): LocalHttpResponse {
        val localDevice = localDeviceProfileRepository.getOrCreate()
        val envelope = runCatching {
            ProtocolJson.format.decodeFromString(
                ProtocolEnvelope.serializer(ConnectionDisconnectRequestDto.serializer()),
                request.bodyText
            )
        }.getOrNull()
        val payload = envelope?.payload
        val correlationId = envelope?.meta?.messageId
        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            expectedPacketTypes = *arrayOf(ProtocolPacketTypes.connectionDisconnectRequest)
        )

        if (!validation.isValid) {
            return protocolErrorResponse(
                statusCode = if (validation.errorCode == ProtocolErrorCodes.protocolMismatch) 426 else 400,
                reasonPhrase = if (validation.errorCode == ProtocolErrorCodes.protocolMismatch) "Upgrade Required" else "Bad Request",
                packetType = ProtocolPacketTypes.connectionDisconnectResponse,
                payload = ConnectionDisconnectResponseDto(
                    acknowledged = false,
                    sessionId = payload?.sessionId.orEmpty(),
                    receivedAtUtc = Instant.now().toString()
                ),
                payloadSerializer = ConnectionDisconnectResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = payload?.deviceId,
                sessionId = payload?.sessionId,
                correlationId = correlationId,
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidRequest,
                errorMessage = validation.errorMessage ?: "Disconnect request is malformed."
            )
        }

        val packet = envelope!!.payload!!
        if (packet.sessionId.isBlank() || packet.deviceId.isBlank()) {
            return protocolErrorResponse(
                statusCode = 400,
                reasonPhrase = "Bad Request",
                packetType = ProtocolPacketTypes.connectionDisconnectResponse,
                payload = ConnectionDisconnectResponseDto(
                    acknowledged = false,
                    sessionId = packet.sessionId,
                    receivedAtUtc = Instant.now().toString()
                ),
                payloadSerializer = ConnectionDisconnectResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.deviceId.ifBlank { null },
                sessionId = packet.sessionId.ifBlank { null },
                correlationId = correlationId,
                errorCode = ProtocolErrorCodes.invalidRequest,
                errorMessage = "Disconnect request is missing required fields."
            )
        }

        val connection = activeConnection
        if (connection != null &&
            connection.sessionId == packet.sessionId &&
            connection.peer.id.equals(packet.deviceId, ignoreCase = true)
        ) {
            activeConnection = null
            _activePeer.value = null
            cancelHeartbeatLoopLocked()
            reconnectPlan = null
            cancelReconnectLoopLocked()

            _connectionState.update { current ->
                current.copy(
                    lifecycleState = ConnectionLifecycleState.Disconnected,
                    statusText = "${connection.peer.displayName} disconnected from the hotspot/LAN session.",
                    sessionId = null,
                    connectedPeer = connection.peer,
                    selectedMode = AppConnectionMode.LocalWifiLan,
                    localPairingCode = current.localPairingCode,
                    lastError = null,
                    handshakeSummary = "The hotspot/LAN session is closed.",
                    isReconnectScheduled = false,
                    reconnectAttemptCount = 0
                )
            }

            loggerService.info("[SESSION] ${connection.peer.displayName} closed hotspot/LAN session ${connection.sessionId}.")
        }

        return protocolSuccessResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            packetType = ProtocolPacketTypes.connectionDisconnectResponse,
            payload = ConnectionDisconnectResponseDto(
                acknowledged = true,
                sessionId = packet.sessionId,
                receivedAtUtc = Instant.now().toString()
            ),
            payloadSerializer = ConnectionDisconnectResponseDto.serializer(),
            localDevice = localDevice,
            receiverDeviceId = packet.deviceId,
            sessionId = packet.sessionId,
            correlationId = correlationId
        )
    }

    private suspend fun cancelHeartbeatLoopLocked() {
        heartbeatJob.cancelAndJoinSafely()
        heartbeatJob = null
    }

    private suspend fun cancelReconnectLoopLocked() {
        reconnectJob.cancelAndJoinSafely()
        reconnectJob = null
        _connectionState.update { current ->
            current.copy(
                isReconnectScheduled = false,
                reconnectAttemptCount = 0
            )
        }
    }

    private fun buildPeerUrl(peer: DevicePeer, path: String): String {
        return "http://${peer.ipAddress}:${peer.port}$path"
    }

    private suspend fun Job?.cancelAndJoinSafely() {
        this ?: return
        runCatching {
            cancelAndJoin()
        }
    }

    private fun createHandshakeFailureResponse(
        localDevice: LocalDeviceProfile,
        sessionState: String,
        failureReason: String
    ): ConnectionHandshakeResponseDto {
        return ConnectionHandshakeResponseDto(
            accepted = false,
            sessionState = sessionState,
            sessionId = null,
            failureReason = failureReason,
            serverDeviceId = localDevice.deviceId,
            serverDeviceName = localDevice.deviceName,
            serverPlatform = localDevice.platform,
            serverAppVersion = localDevice.appVersion,
            supportedModes = localDevice.supportedModes,
            issuedAtUtc = Instant.now().toString()
        )
    }

    private fun createHeartbeatFailureResponse(
        localDevice: LocalDeviceProfile,
        sessionState: String,
        failureReason: String
    ): ConnectionHeartbeatResponseDto {
        return ConnectionHeartbeatResponseDto(
            alive = false,
            sessionState = sessionState,
            failureReason = failureReason,
            serverDeviceId = localDevice.deviceId,
            serverDeviceName = localDevice.deviceName,
            serverPlatform = localDevice.platform,
            serverAppVersion = localDevice.appVersion,
            receivedAtUtc = Instant.now().toString()
        )
    }

    private fun <T> protocolSuccessResponse(
        statusCode: Int,
        reasonPhrase: String,
        packetType: String,
        payload: T,
        payloadSerializer: KSerializer<T>,
        localDevice: LocalDeviceProfile,
        receiverDeviceId: String?,
        sessionId: String?,
        correlationId: String?
    ): LocalHttpResponse {
        val envelope = ProtocolEnvelope(
            meta = ProtocolMetadata(
                version = ProtocolConstants.version,
                packetType = packetType,
                messageId = UUID.randomUUID().toString().replace("-", ""),
                sentAtUtc = Instant.now().toString(),
                sessionId = sessionId,
                senderDeviceId = localDevice.deviceId,
                receiverDeviceId = receiverDeviceId,
                correlationId = correlationId
            ),
            payload = payload,
            error = null
        )
        val body = ProtocolJson.format.encodeToString(
            ProtocolEnvelope.serializer(payloadSerializer),
            envelope
        )
        return LocalHttpResponse.json(statusCode, reasonPhrase, body)
    }

    private fun <T> protocolErrorResponse(
        statusCode: Int,
        reasonPhrase: String,
        packetType: String,
        payload: T,
        payloadSerializer: KSerializer<T>,
        localDevice: LocalDeviceProfile,
        receiverDeviceId: String?,
        sessionId: String?,
        correlationId: String?,
        errorCode: String,
        errorMessage: String
    ): LocalHttpResponse {
        val envelope = ProtocolEnvelope(
            meta = ProtocolMetadata(
                version = ProtocolConstants.version,
                packetType = packetType,
                messageId = UUID.randomUUID().toString().replace("-", ""),
                sentAtUtc = Instant.now().toString(),
                sessionId = sessionId,
                senderDeviceId = localDevice.deviceId,
                receiverDeviceId = receiverDeviceId,
                correlationId = correlationId
            ),
            payload = payload,
            error = ProtocolError(
                code = errorCode,
                message = errorMessage,
                isRetryable = false
            )
        )
        val body = ProtocolJson.format.encodeToString(
            ProtocolEnvelope.serializer(payloadSerializer),
            envelope
        )
        return LocalHttpResponse.json(statusCode, reasonPhrase, body)
    }

    private fun generatePairingCode(): String {
        return (100000..999999).random().toString()
    }

    private data class ActiveConnection(
        val sessionId: String,
        val peer: DevicePeer,
        val pairingToken: String,
        val isIncoming: Boolean = false
    )

    private data class ReconnectPlan(
        val peer: DevicePeer,
        val pairingToken: String
    )

    private enum class ConnectAttemptOutcome {
        Connected,
        WaitingForPairing,
        Failed
    }

    private sealed interface HeartbeatOutcome {
        data object Success : HeartbeatOutcome
        data class Failure(val reason: String) : HeartbeatOutcome
    }
}
