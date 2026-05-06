package com.localbridge.android.services

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.core.protocol.ConnectionDisconnectRequestDto
import com.localbridge.android.core.protocol.ConnectionDisconnectResponseDto
import com.localbridge.android.core.protocol.ConnectionHandshakeRequestDto
import com.localbridge.android.core.protocol.ConnectionHandshakeResponseDto
import com.localbridge.android.core.protocol.ConnectionHeartbeatRequestDto
import com.localbridge.android.core.protocol.ConnectionHeartbeatResponseDto
import com.localbridge.android.core.protocol.FileTransferCancelRequestDto
import com.localbridge.android.core.protocol.FileTransferCancelResponseDto
import com.localbridge.android.core.protocol.FileTransferChunkDescriptorDto
import com.localbridge.android.core.protocol.FileTransferChunkResponseDto
import com.localbridge.android.core.protocol.FileTransferCompleteRequestDto
import com.localbridge.android.core.protocol.FileTransferCompleteResponseDto
import com.localbridge.android.core.protocol.FileTransferPrepareRequestDto
import com.localbridge.android.core.protocol.FileTransferPrepareResponseDto
import com.localbridge.android.core.protocol.ProtocolConstants
import com.localbridge.android.core.protocol.ProtocolEnvelope
import com.localbridge.android.core.protocol.ProtocolEnvelopeFactory
import com.localbridge.android.core.protocol.ProtocolEnvelopeValidator
import com.localbridge.android.core.protocol.ProtocolError
import com.localbridge.android.core.protocol.ProtocolErrorCodes
import com.localbridge.android.core.protocol.ProtocolJson
import com.localbridge.android.core.protocol.ProtocolMetadata
import com.localbridge.android.core.protocol.ProtocolPacketTypes
import com.localbridge.android.core.protocol.TextChatDeliveryReceiptDto
import com.localbridge.android.core.protocol.TextChatPacketDto
import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.ConnectionLifecycleState
import com.localbridge.android.models.ConnectionStateModel
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.models.LocalDeviceProfile
import com.localbridge.android.repositories.DeviceRepository
import com.localbridge.android.repositories.LocalDeviceProfileRepository
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonElement

class BluetoothConnectionService(
    context: Context,
    private val deviceRepository: DeviceRepository,
    private val trustedDevicesService: TrustedDevicesService,
    private val localDeviceProfileRepository: LocalDeviceProfileRepository,
    private val loggerService: LoggerService
) : ConnectionService {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val connectMutex = Mutex()
    private val bluetoothAdapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _connectionState = MutableStateFlow(
        ConnectionStateModel(
            lifecycleState = ConnectionLifecycleState.Idle,
            statusText = "Bluetooth fallback is ready when permissions and the adapter are available.",
            selectedMode = AppConnectionMode.BluetoothFallback,
            localPairingCode = generatePairingCode(),
            protocolVersion = AppConstants.protocolVersion,
            handshakeSummary = "Share this six-digit code with Windows when the desktop starts the Bluetooth fallback."
        )
    )
    private val _activePeer = MutableStateFlow<DevicePeer?>(null)
    private val _incomingMessages = MutableSharedFlow<TextChatPacketDto>(extraBufferCapacity = 32)

    override val connectionState: StateFlow<ConnectionStateModel> = _connectionState.asStateFlow()
    override val activePeer: StateFlow<DevicePeer?> = _activePeer.asStateFlow()
    val incomingMessages: SharedFlow<TextChatPacketDto> = _incomingMessages.asSharedFlow()

    var isAvailable: Boolean = false
        private set
    var availabilityReason: String = "Bluetooth RFCOMM listener has not been initialized yet."
        private set

    private var serverSocket: BluetoothServerSocket? = null
    private var serialPortServerSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private var serialPortAcceptJob: Job? = null
    private var heartbeatJob: Job? = null
    private var activeSession: BluetoothSession? = null
    private var isStarted = false
    private var fileTransferHandler: BluetoothTransferEndpointHandler? = null

    fun registerFileTransferHandler(handler: BluetoothTransferEndpointHandler) {
        fileTransferHandler = handler
        loggerService.info("[BT-TRANSFER] Android Bluetooth transfer handler registered: ${handler::class.simpleName}.")
    }

    override fun start() {
        scope.launch {
            lifecycleMutex.withLock {
                startOrRefreshListener()
            }
        }
    }

    override fun connect(peer: DevicePeer, pairingToken: String) {
        scope.launch {
            connectMutex.withLock {
                startOrRefreshListener()

                if (!isAvailable) {
                    fail("Bluetooth is not available on this Android device right now. $availabilityReason")
                    return@withLock
                }

                if (peer.bluetoothAddress.isBlank()) {
                    fail("The selected Bluetooth peer does not expose a Bluetooth address.")
                    return@withLock
                }

                val normalizedToken = pairingToken.trim()
                if (normalizedToken.isBlank()) {
                    _connectionState.update { current ->
                        current.copy(
                            lifecycleState = ConnectionLifecycleState.WaitingForPairing,
                            statusText = "Enter the six-digit code shown on Windows before opening the Bluetooth session.",
                            connectedPeer = peer,
                            sessionId = null,
                            lastError = ProtocolErrorCodes.pairingTokenRequired,
                            handshakeSummary = "Bluetooth needs the Windows Localink pairing code."
                        )
                    }
                    return@withLock
                }

                closeActiveSession(
                    reason = "Switching Bluetooth peers.",
                    notifyRemote = true,
                    updateState = false
                )

                _connectionState.update { current ->
                    current.copy(
                        lifecycleState = ConnectionLifecycleState.Connecting,
                        statusText = "Opening a Bluetooth RFCOMM link to ${peer.displayName}.",
                        connectedPeer = peer,
                        sessionId = null,
                        selectedMode = AppConnectionMode.BluetoothFallback,
                        lastError = null,
                        handshakeSummary = "Waiting for the Bluetooth socket before Localink pairing starts.",
                        isReconnectScheduled = false,
                        reconnectAttemptCount = 0
                    )
                }

                loggerService.info("[BT-CONNECT] Android is opening RFCOMM to ${peer.displayName} (${peer.bluetoothAddress}).")

                val remoteDevice = runCatching {
                    bluetoothAdapter?.getRemoteDevice(peer.bluetoothAddress)
                }.getOrNull()

                if (remoteDevice == null) {
                    fail("Android could not resolve the selected Bluetooth device address.")
                    return@withLock
                }

                val socket = try {
                    bluetoothAdapter?.cancelDiscovery()
                    createClientSocket(remoteDevice).also { it.connect() }
                } catch (exception: Exception) {
                    fail("Android could not open the Bluetooth socket to ${peer.displayName}: ${exception.message ?: "unknown error"}")
                    return@withLock
                }

                val session = createSession(
                    socket = socket,
                    peer = peer.copy(
                        transportMode = AppConnectionMode.BluetoothFallback,
                        isOnline = true
                    ),
                    pairingToken = normalizedToken,
                    isIncoming = false
                )
                activeSession = session
                session.readJob = scope.launch { runReadLoop(session) }

                val localDevice = ensureLocalDevice()
                val handshake = sendRequest<ConnectionHandshakeRequestDto, ConnectionHandshakeResponseDto>(
                    session = session,
                    packetType = ProtocolPacketTypes.connectionHandshakeRequest,
                    expectedResponsePacketType = ProtocolPacketTypes.connectionHandshakeResponse,
                    payload = ConnectionHandshakeRequestDto(
                        deviceId = localDevice.deviceId,
                        deviceName = localDevice.deviceName,
                        platform = localDevice.platform,
                        appVersion = localDevice.appVersion,
                        pairingToken = normalizedToken,
                        supportedModes = listOf(AppConstants.localWifiMode, AppConstants.bluetoothMode)
                    ),
                    receiverDeviceId = peer.id
                )

                if (handshake == null || !handshake.accepted || handshake.sessionId.isNullOrBlank()) {
                    closeActiveSession(
                        reason = handshake?.failureReason ?: "Bluetooth pairing failed on the Windows peer.",
                        notifyRemote = false,
                        updateState = true
                    )
                    return@withLock
                }

                val resolvedPeer = session.peer.copy(
                    id = handshake.serverDeviceId,
                    displayName = handshake.serverDeviceName,
                    platform = handshake.serverPlatform,
                    appVersion = handshake.serverAppVersion,
                    supportedModes = handshake.supportedModes,
                    transportMode = AppConnectionMode.BluetoothFallback,
                    isTrusted = true,
                    isOnline = true,
                    pairingRequired = false,
                    lastSeenAtUtc = handshake.issuedAtUtc
                )
                session.peer = resolvedPeer
                session.sessionId = handshake.sessionId
                session.lastHeartbeatUtc = Instant.now().toString()
                _activePeer.value = resolvedPeer
                trustedDevicesService.trust(resolvedPeer.id)
                deviceRepository.upsert(resolvedPeer)

                _connectionState.update { current ->
                    current.copy(
                        lifecycleState = ConnectionLifecycleState.Paired,
                        statusText = "Bluetooth pairing accepted by ${resolvedPeer.displayName}.",
                        sessionId = session.sessionId,
                        connectedPeer = resolvedPeer,
                        selectedMode = AppConnectionMode.BluetoothFallback,
                        lastError = null,
                        handshakeSummary = "Bluetooth session ${session.sessionId} is waiting for heartbeat validation."
                    )
                }

                if (!sendHeartbeat(session, transitionToConnected = true)) {
                    closeActiveSession(
                        reason = "Bluetooth heartbeat validation failed.",
                        notifyRemote = false,
                        updateState = true
                    )
                    return@withLock
                }

                startHeartbeatLoop()
            }
        }
    }

    override fun disconnect() {
        scope.launch {
            connectMutex.withLock {
                closeActiveSession(
                    reason = "Bluetooth session disconnected.",
                    notifyRemote = true,
                    updateState = true
                )
            }
        }
    }

    suspend fun sendChatMessage(packet: TextChatPacketDto): TextChatDeliveryReceiptDto {
        val session = activeSession
        val localDevice = ensureLocalDevice()
        if (session == null || !connectionState.value.isConnected || session.sessionId.isBlank()) {
            return createFailedReceipt(packet.id, ProtocolErrorCodes.notConnected, localDevice)
        }

        val payload = packet.copy(
            sessionId = session.sessionId,
            senderId = localDevice.deviceId,
            senderName = localDevice.deviceName,
            receiverId = session.peer.id,
            timestampUtc = packet.timestampUtc.ifBlank { Instant.now().toString() }
        )

        return sendRequest<TextChatPacketDto, TextChatDeliveryReceiptDto>(
            session = session,
            packetType = ProtocolPacketTypes.chatTextMessage,
            expectedResponsePacketType = ProtocolPacketTypes.chatDeliveryReceipt,
            payload = payload,
            receiverDeviceId = session.peer.id,
            sessionId = session.sessionId,
            messageId = payload.id,
            sentAtUtc = payload.timestampUtc
        ) ?: createFailedReceipt(packet.id, "bluetooth_chat_timeout", localDevice)
    }

    suspend fun sendTransferPrepare(request: FileTransferPrepareRequestDto): FileTransferPrepareResponseDto {
        val localDevice = ensureLocalDevice()
        val session = activeSession
        if (session == null || !connectionState.value.isConnected || session.sessionId.isBlank()) {
            return createTransferPrepareFailure(request.transferId, ProtocolErrorCodes.notConnected, localDevice)
        }

        if (request.fileSize > AppConstants.bluetoothSmallFileTransferLimitBytes) {
            return createTransferPrepareFailure(request.transferId, "file_size_not_supported", localDevice)
        }

        return sendRequest<FileTransferPrepareRequestDto, FileTransferPrepareResponseDto>(
            session = session,
            packetType = ProtocolPacketTypes.transferPrepareRequest,
            expectedResponsePacketType = ProtocolPacketTypes.transferPrepareResponse,
            payload = request,
            receiverDeviceId = session.peer.id,
            sessionId = session.sessionId,
            messageId = request.transferId,
            sentAtUtc = request.requestedAtUtc
        ) ?: createTransferPrepareFailure(request.transferId, "bluetooth_prepare_timeout", localDevice)
    }

    suspend fun sendTransferChunk(
        descriptor: FileTransferChunkDescriptorDto,
        chunkPayload: ByteArray
    ): FileTransferChunkResponseDto {
        val session = activeSession
        if (session == null || !connectionState.value.isConnected || session.sessionId.isBlank()) {
            return createTransferChunkFailure(descriptor.transferId, descriptor.chunkIndex, ProtocolErrorCodes.notConnected)
        }

        return sendBinaryRequest<FileTransferChunkDescriptorDto, FileTransferChunkResponseDto>(
            session = session,
            packetType = ProtocolPacketTypes.transferChunkRequest,
            expectedResponsePacketType = ProtocolPacketTypes.transferChunkResponse,
            payload = descriptor,
            binaryPayload = chunkPayload,
            receiverDeviceId = session.peer.id,
            sessionId = session.sessionId,
            messageId = "${descriptor.transferId}-${descriptor.chunkIndex}"
        ) ?: createTransferChunkFailure(descriptor.transferId, descriptor.chunkIndex, "bluetooth_chunk_timeout")
    }

    suspend fun sendTransferComplete(request: FileTransferCompleteRequestDto): FileTransferCompleteResponseDto {
        val session = activeSession
        if (session == null || !connectionState.value.isConnected || session.sessionId.isBlank()) {
            return createTransferCompleteFailure(request.transferId, ProtocolErrorCodes.notConnected)
        }

        return sendRequest<FileTransferCompleteRequestDto, FileTransferCompleteResponseDto>(
            session = session,
            packetType = ProtocolPacketTypes.transferCompleteRequest,
            expectedResponsePacketType = ProtocolPacketTypes.transferCompleteResponse,
            payload = request,
            receiverDeviceId = session.peer.id,
            sessionId = session.sessionId,
            messageId = request.transferId,
            sentAtUtc = request.sentAtUtc
        ) ?: createTransferCompleteFailure(request.transferId, "bluetooth_complete_timeout")
    }

    suspend fun sendTransferCancel(request: FileTransferCancelRequestDto): FileTransferCancelResponseDto {
        val session = activeSession
        if (session == null || !connectionState.value.isConnected || session.sessionId.isBlank()) {
            return createTransferCancelFailure(request.transferId, ProtocolErrorCodes.notConnected)
        }

        return sendRequest<FileTransferCancelRequestDto, FileTransferCancelResponseDto>(
            session = session,
            packetType = ProtocolPacketTypes.transferCancelRequest,
            expectedResponsePacketType = ProtocolPacketTypes.transferCancelResponse,
            payload = request,
            receiverDeviceId = session.peer.id,
            sessionId = session.sessionId,
            messageId = request.transferId,
            sentAtUtc = request.sentAtUtc
        ) ?: createTransferCancelFailure(request.transferId, "bluetooth_cancel_timeout")
    }

    @SuppressLint("MissingPermission")
    private suspend fun startOrRefreshListener() {
        _connectionState.update { current ->
            current.copy(
                selectedMode = AppConnectionMode.BluetoothFallback,
                localPairingCode = current.localPairingCode.ifBlank { generatePairingCode() }
            )
        }

        if (!isStarted) {
            isStarted = true
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            isAvailable = false
            availabilityReason = "Bluetooth hardware is not available on this Android device."
            _connectionState.update { current ->
                current.copy(
                    lifecycleState = ConnectionLifecycleState.Idle,
                    statusText = availabilityReason,
                    selectedMode = AppConnectionMode.BluetoothFallback,
                    lastError = "bluetooth_not_available"
                )
            }
            loggerService.warning("[BT-LISTENER] Android Bluetooth hardware is unavailable.")
            return
        }

        if (!hasBluetoothSocketPermissions()) {
            isAvailable = false
            availabilityReason = "Bluetooth permissions are required before Localink can open RFCOMM on Android."
            _connectionState.update { current ->
                current.copy(
                    lifecycleState = ConnectionLifecycleState.Idle,
                    statusText = availabilityReason,
                    selectedMode = AppConnectionMode.BluetoothFallback,
                    lastError = "bluetooth_permission_missing"
                )
            }
            loggerService.warning("[BT-LISTENER] Android Bluetooth RFCOMM listener is blocked until CONNECT/ADVERTISE permissions are granted.")
            return
        }

        if (!adapter.isEnabled) {
            isAvailable = false
            availabilityReason = "Bluetooth is turned off on Android."
            _connectionState.update { current ->
                current.copy(
                    lifecycleState = ConnectionLifecycleState.Idle,
                    statusText = "Turn on Bluetooth on Android to use the fallback mode.",
                    selectedMode = AppConnectionMode.BluetoothFallback,
                    lastError = "bluetooth_disabled"
                )
            }
            loggerService.warning("[BT-LISTENER] Android Bluetooth adapter is turned off.")
            return
        }

        val customListenerActive = serverSocket != null && acceptJob?.isActive == true
        val serialPortListenerActive = serialPortServerSocket != null && serialPortAcceptJob?.isActive == true
        if (customListenerActive || serialPortListenerActive) {
            isAvailable = true
            availabilityReason = "Bluetooth RFCOMM listener is ready."
            _connectionState.update { current ->
                current.copy(
                    statusText = if (current.isConnected) current.statusText else "Bluetooth fallback is ready on Android. Share the pairing code with Windows when needed.",
                    selectedMode = AppConnectionMode.BluetoothFallback,
                    lastError = if (current.isConnected) current.lastError else null
                )
            }
            return
        }

        runCatching {
            serverSocket?.close()
        }
        runCatching {
            serialPortServerSocket?.close()
        }
        serverSocket = null
        serialPortServerSocket = null
        acceptJob?.cancel()
        serialPortAcceptJob?.cancel()
        acceptJob = null
        serialPortAcceptJob = null

        val primarySocket = openServerSocket(
            adapter = adapter,
            serviceName = AppConstants.bluetoothServiceName,
            serviceId = AppConstants.bluetoothServiceId,
            label = "Localink UUID"
        )
        val serialSocket = openServerSocket(
            adapter = adapter,
            serviceName = "${AppConstants.bluetoothServiceName} SerialPort",
            serviceId = AppConstants.bluetoothSerialPortServiceId,
            label = "SerialPort fallback"
        )

        if (primarySocket != null) {
            val socket = primarySocket
            serverSocket = socket
            acceptJob = scope.launch { acceptLoop(socket, "Localink UUID") }
        }

        if (serialSocket != null) {
            val socket = serialSocket
            serialPortServerSocket = socket
            serialPortAcceptJob = scope.launch { acceptLoop(socket, "SerialPort fallback") }
        }

        if (primarySocket != null || serialSocket != null) {
            isAvailable = true
            availabilityReason = "Bluetooth RFCOMM listener is ready."
            _connectionState.update { current ->
                current.copy(
                    lifecycleState = if (current.isConnected) current.lifecycleState else ConnectionLifecycleState.Idle,
                    statusText = if (current.isConnected) current.statusText else "Bluetooth fallback is ready on Android. Share the pairing code with Windows when needed.",
                    selectedMode = AppConnectionMode.BluetoothFallback,
                    lastError = null,
                    handshakeSummary = "Use pairing code ${current.localPairingCode} when Windows opens the Bluetooth Localink link."
                )
            }
            loggerService.info("[BT-LISTENER] Android Bluetooth RFCOMM listener started. Localink=${primarySocket != null}, SerialPort=${serialSocket != null}.")
        } else {
            isAvailable = false
            availabilityReason = "Bluetooth RFCOMM listener could not start."
            _connectionState.update { current ->
                current.copy(
                    lifecycleState = if (current.isConnected) current.lifecycleState else ConnectionLifecycleState.Failed,
                    statusText = "Android could not start the Bluetooth RFCOMM listener.",
                    selectedMode = AppConnectionMode.BluetoothFallback,
                    lastError = availabilityReason
                )
            }
            loggerService.warning("[BT-LISTENER] Android listener start failed for both Localink UUID and SerialPort fallback.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openServerSocket(
        adapter: BluetoothAdapter,
        serviceName: String,
        serviceId: String,
        label: String
    ): BluetoothServerSocket? {
        val uuid = UUID.fromString(serviceId)
        return runCatching {
            adapter.listenUsingInsecureRfcommWithServiceRecord(serviceName, uuid)
        }.onSuccess {
            loggerService.info("[BT-LISTENER] Android $label RFCOMM listener started (insecure mode).")
        }.getOrElse { insecureException ->
            loggerService.warning("[BT-LISTENER] Android $label insecure listener failed, trying secure RFCOMM: ${insecureException.message}")
            runCatching {
                adapter.listenUsingRfcommWithServiceRecord(serviceName, uuid)
            }.onSuccess {
                loggerService.info("[BT-LISTENER] Android $label RFCOMM listener started (secure mode).")
            }.onFailure { secureException ->
                loggerService.warning("[BT-LISTENER] Android $label listener failed: ${secureException.message}")
            }.getOrNull()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun acceptLoop(listeningSocket: BluetoothServerSocket, listenerLabel: String) {
        while (scope.isActive) {
            try {
                val socket = listeningSocket.accept() ?: continue
                val remoteDevice = socket.remoteDevice
                val session = createSession(
                    socket = socket,
                    peer = DevicePeer(
                        id = buildTemporaryPeerId(remoteDevice.address.orEmpty()),
                        displayName = remoteDevice.name?.takeIf { it.isNotBlank() } ?: remoteDevice.address.orEmpty(),
                        platform = "Bluetooth device",
                        ipAddress = "",
                        port = 0,
                        bluetoothAddress = remoteDevice.address.orEmpty(),
                        appVersion = "paired",
                        supportedModes = listOf(AppConstants.bluetoothMode),
                        transportMode = AppConnectionMode.BluetoothFallback,
                        isTrusted = false,
                        isOnline = true,
                        pairingRequired = true,
                        lastSeenAtUtc = Instant.now().toString()
                    ),
                    pairingToken = "",
                    isIncoming = true
                )
                session.readJob = scope.launch { runReadLoop(session) }
                loggerService.info("[BT-LISTENER] Accepted Android Bluetooth socket from ${session.peer.displayName} via $listenerLabel.")
            } catch (exception: Exception) {
                if (!scope.isActive) {
                    return
                }
                loggerService.warning("[BT-LISTENER] Android accept loop failed: ${exception.message}")
                delay(750)
            }
        }
    }

    private suspend fun runReadLoop(session: BluetoothSession) {
        try {
            while (scope.isActive && !session.isClosed) {
                val frame = BluetoothTransportFrameCodec.read(session.input)
                    ?: break

                val envelope = ProtocolJson.format.decodeFromString<ProtocolEnvelope<JsonElement>>(frame.metadataJson)
                val correlationId = envelope.meta.correlationId
                if (!correlationId.isNullOrBlank()) {
                    val waiter = session.pendingResponses.remove(correlationId)
                    if (waiter != null) {
                        waiter.complete(envelope)
                        continue
                    }
                }

                handleIncomingEnvelope(session, envelope, frame.binaryPayload)
            }
        } catch (exception: Exception) {
            if (!session.isClosed) {
                loggerService.warning("[BT-READ] Android Bluetooth read loop failed for ${session.peer.displayName}: ${exception.message}")
            }
        } finally {
            closeSession(
                session = session,
                reason = "The Bluetooth link closed.",
                notifyRemote = false,
                updateState = true
            )
        }
    }

    private suspend fun handleIncomingEnvelope(
        session: BluetoothSession,
        envelope: ProtocolEnvelope<JsonElement>,
        binaryPayload: ByteArray?
    ) {
        when (envelope.meta.packetType) {
            ProtocolPacketTypes.connectionHandshakeRequest -> handleHandshakeRequest(session, envelope)
            ProtocolPacketTypes.connectionHeartbeatRequest -> handleHeartbeatRequest(session, envelope)
            ProtocolPacketTypes.connectionDisconnectRequest -> handleDisconnectRequest(session, envelope)
            ProtocolPacketTypes.chatTextMessage -> handleChatMessage(session, envelope)
            ProtocolPacketTypes.transferPrepareRequest -> handleTransferPrepareRequest(session, envelope)
            ProtocolPacketTypes.transferChunkRequest -> handleTransferChunkRequest(session, envelope, binaryPayload)
            ProtocolPacketTypes.transferCompleteRequest -> handleTransferCompleteRequest(session, envelope)
            ProtocolPacketTypes.transferCancelRequest -> handleTransferCancelRequest(session, envelope)
            else -> loggerService.warning("[BT-RX] Android ignored unsupported Bluetooth packet ${envelope.meta.packetType}.")
        }
    }

    private suspend fun handleHandshakeRequest(
        session: BluetoothSession,
        envelope: ProtocolEnvelope<JsonElement>
    ) {
        val localDevice = ensureLocalDevice()
        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            true,
            ProtocolPacketTypes.connectionHandshakeRequest
        )
        val request = decodePayload<ConnectionHandshakeRequestDto>(envelope)

        if (!validation.isValid) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.connectionHandshakeResponse,
                payload = createHandshakeFailureResponse(localDevice, ProtocolConstants.sessionStateFailed, validation.errorCode ?: ProtocolErrorCodes.invalidRequest),
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidRequest,
                errorMessage = validation.errorMessage ?: "Bluetooth handshake request is malformed.",
                receiverDeviceId = request?.deviceId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (request == null ||
            request.deviceId.isBlank() ||
            request.deviceName.isBlank() ||
            request.platform.isBlank() ||
            request.appVersion.isBlank()
        ) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.connectionHandshakeResponse,
                payload = createHandshakeFailureResponse(localDevice, ProtocolConstants.sessionStateFailed, ProtocolErrorCodes.invalidRequest),
                errorCode = ProtocolErrorCodes.invalidRequest,
                errorMessage = "Bluetooth handshake request is missing required fields.",
                receiverDeviceId = request?.deviceId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (request.deviceId.equals(localDevice.deviceId, ignoreCase = true)) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.connectionHandshakeResponse,
                payload = createHandshakeFailureResponse(localDevice, ProtocolConstants.sessionStateFailed, ProtocolErrorCodes.selfConnectionNotAllowed),
                errorCode = ProtocolErrorCodes.selfConnectionNotAllowed,
                errorMessage = "Android cannot pair with itself over Bluetooth.",
                receiverDeviceId = request.deviceId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        val pairingCode = request.pairingToken.trim()
        if (pairingCode.isBlank() || pairingCode != connectionState.value.localPairingCode) {
            val errorCode = if (pairingCode.isBlank()) {
                ProtocolErrorCodes.pairingTokenRequired
            } else {
                ProtocolErrorCodes.invalidPairingToken
            }
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.connectionHandshakeResponse,
                payload = createHandshakeFailureResponse(localDevice, ProtocolConstants.sessionStateWaitingForPairing, errorCode),
                errorCode = errorCode,
                errorMessage = if (errorCode == ProtocolErrorCodes.pairingTokenRequired) {
                    "The Android Localink pairing code is required."
                } else {
                    "The supplied Android Bluetooth pairing code is not valid on this device."
                },
                receiverDeviceId = request.deviceId,
                correlationId = envelope.meta.messageId
            )
            loggerService.warning("[BT-PAIR] Android rejected ${request.deviceName} because the pairing code was invalid.")
            return
        }

        val existingSession = activeSession
        if (existingSession != null &&
            existingSession !== session &&
            !existingSession.peer.id.equals(request.deviceId, ignoreCase = true)
        ) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.connectionHandshakeResponse,
                payload = createHandshakeFailureResponse(localDevice, ProtocolConstants.sessionStateFailed, ProtocolErrorCodes.invalidRequest),
                errorCode = ProtocolErrorCodes.invalidRequest,
                errorMessage = "Another Bluetooth session is already active on Android.",
                receiverDeviceId = request.deviceId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        val resolvedPeer = session.peer.copy(
            id = request.deviceId,
            displayName = request.deviceName,
            platform = request.platform,
            appVersion = request.appVersion,
            supportedModes = request.supportedModes,
            transportMode = AppConnectionMode.BluetoothFallback,
            isTrusted = true,
            isOnline = true,
            pairingRequired = false,
            lastSeenAtUtc = Instant.now().toString()
        )
        session.peer = resolvedPeer
        session.sessionId = UUID.randomUUID().toString().replace("-", "")
        session.pairingToken = pairingCode
        session.lastHeartbeatUtc = Instant.now().toString()
        activeSession = session
        _activePeer.value = resolvedPeer
        trustedDevicesService.trust(resolvedPeer.id)
        deviceRepository.upsert(resolvedPeer)

        _connectionState.update { current ->
            current.copy(
                lifecycleState = ConnectionLifecycleState.Paired,
                statusText = "Accepted Bluetooth pairing from ${resolvedPeer.displayName}.",
                sessionId = session.sessionId,
                connectedPeer = resolvedPeer,
                selectedMode = AppConnectionMode.BluetoothFallback,
                localPairingCode = current.localPairingCode,
                lastError = null,
                handshakeSummary = "Incoming Bluetooth session ${session.sessionId} paired successfully."
            )
        }

        loggerService.info("[BT-PAIR] Android accepted Bluetooth pairing from ${resolvedPeer.displayName}. Session ${session.sessionId}.")
        sendResponseEnvelope(
            session = session,
            packetType = ProtocolPacketTypes.connectionHandshakeResponse,
            payload = ConnectionHandshakeResponseDto(
                accepted = true,
                sessionState = ProtocolConstants.sessionStatePaired,
                sessionId = session.sessionId,
                failureReason = null,
                serverDeviceId = localDevice.deviceId,
                serverDeviceName = localDevice.deviceName,
                serverPlatform = localDevice.platform,
                serverAppVersion = localDevice.appVersion,
                supportedModes = listOf(AppConstants.localWifiMode, AppConstants.bluetoothMode),
                issuedAtUtc = Instant.now().toString()
            ),
            receiverDeviceId = request.deviceId,
            sessionId = session.sessionId,
            correlationId = envelope.meta.messageId
        )
    }

    private suspend fun handleHeartbeatRequest(
        session: BluetoothSession,
        envelope: ProtocolEnvelope<JsonElement>
    ) {
        val localDevice = ensureLocalDevice()
        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            true,
            ProtocolPacketTypes.connectionHeartbeatRequest
        )
        val request = decodePayload<ConnectionHeartbeatRequestDto>(envelope)

        if (!validation.isValid) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.connectionHeartbeatResponse,
                payload = createHeartbeatFailureResponse(localDevice, ProtocolConstants.sessionStateFailed, validation.errorCode ?: ProtocolErrorCodes.invalidRequest),
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidRequest,
                errorMessage = validation.errorMessage ?: "Bluetooth heartbeat request is malformed.",
                receiverDeviceId = request?.deviceId,
                sessionId = request?.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (request == null ||
            request.sessionId.isBlank() ||
            request.deviceId.isBlank()
        ) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.connectionHeartbeatResponse,
                payload = createHeartbeatFailureResponse(localDevice, ProtocolConstants.sessionStateFailed, ProtocolErrorCodes.invalidRequest),
                errorCode = ProtocolErrorCodes.invalidRequest,
                errorMessage = "Bluetooth heartbeat request is missing the session or device id.",
                receiverDeviceId = request?.deviceId,
                sessionId = request?.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (session.sessionId != request.sessionId || !session.peer.id.equals(request.deviceId, ignoreCase = true)) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.connectionHeartbeatResponse,
                payload = createHeartbeatFailureResponse(localDevice, ProtocolConstants.sessionStateDisconnected, ProtocolErrorCodes.sessionNotFound),
                errorCode = ProtocolErrorCodes.sessionNotFound,
                errorMessage = "The Bluetooth session is not active on this Android host.",
                receiverDeviceId = request.deviceId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        session.lastHeartbeatUtc = Instant.now().toString()
        session.peer = session.peer.copy(
            displayName = request.deviceName,
            platform = request.platform,
            appVersion = request.appVersion,
            isOnline = true,
            lastSeenAtUtc = Instant.now().toString()
        )
        activeSession = session
        _activePeer.value = session.peer
        deviceRepository.upsert(session.peer)

        _connectionState.update { current ->
            current.copy(
                lifecycleState = ConnectionLifecycleState.Connected,
                statusText = "Connected to ${session.peer.displayName} over Bluetooth.",
                sessionId = session.sessionId,
                connectedPeer = session.peer,
                selectedMode = AppConnectionMode.BluetoothFallback,
                localPairingCode = current.localPairingCode,
                lastError = null,
                handshakeSummary = "Heartbeat validated Bluetooth session ${session.sessionId}.",
                isReconnectScheduled = false,
                reconnectAttemptCount = 0
            )
        }

        loggerService.info("[BT-SESSION] Android heartbeat accepted for ${session.peer.displayName} (${session.sessionId}).")
        sendResponseEnvelope(
            session = session,
            packetType = ProtocolPacketTypes.connectionHeartbeatResponse,
            payload = ConnectionHeartbeatResponseDto(
                alive = true,
                sessionState = ProtocolConstants.sessionStateConnected,
                failureReason = null,
                serverDeviceId = localDevice.deviceId,
                serverDeviceName = localDevice.deviceName,
                serverPlatform = localDevice.platform,
                serverAppVersion = localDevice.appVersion,
                receivedAtUtc = Instant.now().toString()
            ),
            receiverDeviceId = request.deviceId,
            sessionId = request.sessionId,
            correlationId = envelope.meta.messageId
        )
    }

    private suspend fun handleDisconnectRequest(
        session: BluetoothSession,
        envelope: ProtocolEnvelope<JsonElement>
    ) {
        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            true,
            ProtocolPacketTypes.connectionDisconnectRequest
        )
        val request = decodePayload<ConnectionDisconnectRequestDto>(envelope)

        if (!validation.isValid || request == null) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.connectionDisconnectResponse,
                payload = ConnectionDisconnectResponseDto(
                    acknowledged = false,
                    sessionId = request?.sessionId.orEmpty(),
                    receivedAtUtc = Instant.now().toString()
                ),
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidRequest,
                errorMessage = validation.errorMessage ?: "Bluetooth disconnect request is malformed.",
                receiverDeviceId = request?.deviceId,
                sessionId = request?.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (session.sessionId != request.sessionId || !session.peer.id.equals(request.deviceId, ignoreCase = true)) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.connectionDisconnectResponse,
                payload = ConnectionDisconnectResponseDto(
                    acknowledged = false,
                    sessionId = request.sessionId,
                    receivedAtUtc = Instant.now().toString()
                ),
                errorCode = ProtocolErrorCodes.sessionNotFound,
                errorMessage = "The Bluetooth session is not active on this Android host.",
                receiverDeviceId = request.deviceId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        sendResponseEnvelope(
            session = session,
            packetType = ProtocolPacketTypes.connectionDisconnectResponse,
            payload = ConnectionDisconnectResponseDto(
                acknowledged = true,
                sessionId = request.sessionId,
                receivedAtUtc = Instant.now().toString()
            ),
            receiverDeviceId = request.deviceId,
            sessionId = request.sessionId,
            correlationId = envelope.meta.messageId
        )
        closeSession(
            session = session,
            reason = "${session.peer.displayName} disconnected the Bluetooth session.",
            notifyRemote = false,
            updateState = true
        )
    }

    private suspend fun handleChatMessage(
        session: BluetoothSession,
        envelope: ProtocolEnvelope<JsonElement>
    ) {
        val localDevice = ensureLocalDevice()
        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            true,
            ProtocolPacketTypes.chatTextMessage
        )
        val packet = decodePayload<TextChatPacketDto>(envelope)

        if (!validation.isValid || packet == null) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.chatDeliveryReceipt,
                payload = createFailedReceipt(packet?.id.orEmpty(), validation.errorCode ?: ProtocolErrorCodes.invalidRequest, localDevice),
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidRequest,
                errorMessage = validation.errorMessage ?: "Bluetooth chat packet is malformed.",
                receiverDeviceId = packet?.senderId,
                sessionId = packet?.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (packet.id.isBlank() ||
            packet.sessionId.isBlank() ||
            packet.senderId.isBlank() ||
            packet.text.isBlank()
        ) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.chatDeliveryReceipt,
                payload = createFailedReceipt(packet.id, ProtocolErrorCodes.invalidRequest, localDevice),
                errorCode = ProtocolErrorCodes.invalidRequest,
                errorMessage = "Bluetooth chat packet is missing required fields.",
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (packet.receiverId.isNotBlank() && !packet.receiverId.equals(localDevice.deviceId, ignoreCase = true)) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.chatDeliveryReceipt,
                payload = createFailedReceipt(packet.id, ProtocolErrorCodes.wrongReceiver, localDevice),
                errorCode = ProtocolErrorCodes.wrongReceiver,
                errorMessage = "The Bluetooth message receiver does not match this Android device.",
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (session.sessionId != packet.sessionId || !session.peer.id.equals(packet.senderId, ignoreCase = true)) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.chatDeliveryReceipt,
                payload = createFailedReceipt(packet.id, ProtocolErrorCodes.sessionNotFound, localDevice),
                errorCode = ProtocolErrorCodes.sessionNotFound,
                errorMessage = "The Bluetooth chat session is not active on this Android host.",
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        session.lastHeartbeatUtc = Instant.now().toString()
        session.peer = session.peer.copy(isOnline = true, lastSeenAtUtc = Instant.now().toString())
        _incomingMessages.tryEmit(packet)
        loggerService.info("[BT-CHAT] Android accepted Bluetooth chat message ${packet.id} from ${packet.senderName}.")

        sendResponseEnvelope(
            session = session,
            packetType = ProtocolPacketTypes.chatDeliveryReceipt,
            payload = TextChatDeliveryReceiptDto(
                accepted = true,
                messageId = packet.id,
                status = ProtocolConstants.deliveryStatusDelivered,
                failureReason = null,
                receiverDeviceId = localDevice.deviceId,
                receiverDeviceName = localDevice.deviceName,
                receivedAtUtc = Instant.now().toString()
            ),
            receiverDeviceId = packet.senderId,
            sessionId = packet.sessionId,
            correlationId = envelope.meta.messageId
        )
    }

    private suspend fun handleTransferPrepareRequest(
        session: BluetoothSession,
        envelope: ProtocolEnvelope<JsonElement>
    ) {
        val localDevice = ensureLocalDevice()
        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            true,
            ProtocolPacketTypes.transferPrepareRequest
        )
        val request = decodePayload<FileTransferPrepareRequestDto>(envelope)

        if (!validation.isValid) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferPrepareResponse,
                payload = createTransferPrepareFailure(
                    request?.transferId.orEmpty(),
                    validation.errorCode ?: ProtocolErrorCodes.invalidTransferPrepare,
                    localDevice
                ),
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidTransferPrepare,
                errorMessage = validation.errorMessage ?: "Bluetooth transfer prepare request is malformed.",
                receiverDeviceId = request?.senderId,
                sessionId = request?.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (request == null ||
            request.transferId.isBlank() ||
            request.sessionId.isBlank() ||
            request.senderId.isBlank() ||
            request.fileName.isBlank() ||
            request.fileSize <= 0L ||
            request.chunkSize <= 0 ||
            request.totalChunks <= 0
        ) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferPrepareResponse,
                payload = createTransferPrepareFailure(
                    request?.transferId.orEmpty(),
                    ProtocolErrorCodes.invalidTransferPrepare,
                    localDevice
                ),
                errorCode = ProtocolErrorCodes.invalidTransferPrepare,
                errorMessage = "Bluetooth transfer prepare request is missing required metadata.",
                receiverDeviceId = request?.senderId,
                sessionId = request?.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (session.sessionId != request.sessionId || !session.peer.id.equals(request.senderId, ignoreCase = true)) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferPrepareResponse,
                payload = createTransferPrepareFailure(request.transferId, ProtocolErrorCodes.sessionNotFound, localDevice),
                errorCode = ProtocolErrorCodes.sessionNotFound,
                errorMessage = "The Bluetooth transfer session is not active on this Android host.",
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (request.fileSize > AppConstants.bluetoothSmallFileTransferLimitBytes) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferPrepareResponse,
                payload = createTransferPrepareFailure(request.transferId, "file_size_not_supported", localDevice),
                errorCode = "file_size_not_supported",
                errorMessage = "Bluetooth fallback supports files up to 300 MB only.",
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        val handler = fileTransferHandler
        if (handler == null) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferPrepareResponse,
                payload = createTransferPrepareFailure(request.transferId, ProtocolErrorCodes.transferServiceUnavailable, localDevice),
                errorCode = ProtocolErrorCodes.transferServiceUnavailable,
                errorMessage = "Bluetooth transfer service is not ready on Android.",
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        val response = runCatching {
            handler.prepareIncomingBluetoothTransfer(
                request = request,
                peer = session.peer,
                sessionId = session.sessionId,
                localDevice = localDevice
            )
        }.getOrElse { exception ->
            loggerService.warning("[BT-TRANSFER] Android Bluetooth prepare failed: ${exception.message}")
            createTransferPrepareFailure(request.transferId, exception.message ?: "prepare_failed", localDevice)
        }

        if (response.accepted) {
            sendResponseEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferPrepareResponse,
                payload = response,
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
        } else {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferPrepareResponse,
                payload = response,
                errorCode = response.failureReason ?: ProtocolErrorCodes.invalidTransferPrepare,
                errorMessage = response.failureReason ?: "Bluetooth transfer prepare failed.",
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
        }
    }

    private suspend fun handleTransferChunkRequest(
        session: BluetoothSession,
        envelope: ProtocolEnvelope<JsonElement>,
        binaryPayload: ByteArray?
    ) {
        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            true,
            ProtocolPacketTypes.transferChunkRequest
        )
        val request = decodePayload<FileTransferChunkDescriptorDto>(envelope)

        if (!validation.isValid) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferChunkResponse,
                payload = createTransferChunkFailure(
                    request?.transferId.orEmpty(),
                    request?.chunkIndex ?: 0,
                    validation.errorCode ?: ProtocolErrorCodes.invalidTransferChunk
                ),
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidTransferChunk,
                errorMessage = validation.errorMessage ?: "Bluetooth transfer chunk metadata is malformed.",
                receiverDeviceId = request?.senderId,
                sessionId = request?.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (request == null ||
            request.transferId.isBlank() ||
            request.sessionId.isBlank() ||
            request.senderId.isBlank() ||
            request.chunkIndex < 0 ||
            request.chunkOffset < 0L ||
            request.chunkLength <= 0 ||
            binaryPayload == null ||
            binaryPayload.isEmpty()
        ) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferChunkResponse,
                payload = createTransferChunkFailure(
                    request?.transferId.orEmpty(),
                    request?.chunkIndex ?: 0,
                    ProtocolErrorCodes.invalidTransferChunk
                ),
                errorCode = ProtocolErrorCodes.invalidTransferChunk,
                errorMessage = "Bluetooth transfer chunk metadata or binary payload is missing.",
                receiverDeviceId = request?.senderId,
                sessionId = request?.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (session.sessionId != request.sessionId || !session.peer.id.equals(request.senderId, ignoreCase = true)) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferChunkResponse,
                payload = createTransferChunkFailure(request.transferId, request.chunkIndex, ProtocolErrorCodes.sessionNotFound),
                errorCode = ProtocolErrorCodes.sessionNotFound,
                errorMessage = "The Bluetooth transfer session is not active on this Android host.",
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        val handler = fileTransferHandler
        if (handler == null) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferChunkResponse,
                payload = createTransferChunkFailure(request.transferId, request.chunkIndex, ProtocolErrorCodes.transferServiceUnavailable),
                errorCode = ProtocolErrorCodes.transferServiceUnavailable,
                errorMessage = "Bluetooth transfer service is not ready on Android.",
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        val response = runCatching {
            handler.receiveIncomingBluetoothChunk(
                descriptor = request,
                chunkBytes = binaryPayload,
                peer = session.peer,
                sessionId = session.sessionId
            )
        }.getOrElse { exception ->
            loggerService.warning("[BT-TRANSFER] Android Bluetooth chunk failed: ${exception.message}")
            createTransferChunkFailure(request.transferId, request.chunkIndex, exception.message ?: "chunk_failed")
        }

        if (response.accepted) {
            sendResponseEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferChunkResponse,
                payload = response,
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
        } else {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferChunkResponse,
                payload = response,
                errorCode = response.failureReason ?: ProtocolErrorCodes.invalidTransferChunk,
                errorMessage = response.failureReason ?: "Bluetooth transfer chunk failed.",
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
        }
    }

    private suspend fun handleTransferCompleteRequest(
        session: BluetoothSession,
        envelope: ProtocolEnvelope<JsonElement>
    ) {
        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            true,
            ProtocolPacketTypes.transferCompleteRequest
        )
        val request = decodePayload<FileTransferCompleteRequestDto>(envelope)

        if (!validation.isValid) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferCompleteResponse,
                payload = createTransferCompleteFailure(
                    request?.transferId.orEmpty(),
                    validation.errorCode ?: ProtocolErrorCodes.invalidTransferComplete
                ),
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidTransferComplete,
                errorMessage = validation.errorMessage ?: "Bluetooth transfer complete request is malformed.",
                receiverDeviceId = request?.senderId,
                sessionId = request?.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (request == null || request.transferId.isBlank() || request.sessionId.isBlank() || request.senderId.isBlank()) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferCompleteResponse,
                payload = createTransferCompleteFailure(
                    request?.transferId.orEmpty(),
                    ProtocolErrorCodes.invalidTransferComplete
                ),
                errorCode = ProtocolErrorCodes.invalidTransferComplete,
                errorMessage = "Bluetooth transfer completion metadata is missing.",
                receiverDeviceId = request?.senderId,
                sessionId = request?.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (session.sessionId != request.sessionId || !session.peer.id.equals(request.senderId, ignoreCase = true)) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferCompleteResponse,
                payload = createTransferCompleteFailure(request.transferId, ProtocolErrorCodes.sessionNotFound),
                errorCode = ProtocolErrorCodes.sessionNotFound,
                errorMessage = "The Bluetooth transfer session is not active on this Android host.",
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        val handler = fileTransferHandler
        if (handler == null) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferCompleteResponse,
                payload = createTransferCompleteFailure(request.transferId, ProtocolErrorCodes.transferServiceUnavailable),
                errorCode = ProtocolErrorCodes.transferServiceUnavailable,
                errorMessage = "Bluetooth transfer service is not ready on Android.",
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        val response = runCatching {
            handler.completeIncomingBluetoothTransfer(
                request = request,
                peer = session.peer,
                sessionId = session.sessionId
            )
        }.getOrElse { exception ->
            loggerService.warning("[BT-TRANSFER] Android Bluetooth completion failed: ${exception.message}")
            createTransferCompleteFailure(request.transferId, exception.message ?: "complete_failed")
        }

        if (response.accepted) {
            sendResponseEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferCompleteResponse,
                payload = response,
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
        } else {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferCompleteResponse,
                payload = response,
                errorCode = response.failureReason ?: ProtocolErrorCodes.invalidTransferComplete,
                errorMessage = response.failureReason ?: "Bluetooth transfer completion failed.",
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
        }
    }

    private suspend fun handleTransferCancelRequest(
        session: BluetoothSession,
        envelope: ProtocolEnvelope<JsonElement>
    ) {
        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            true,
            ProtocolPacketTypes.transferCancelRequest
        )
        val request = decodePayload<FileTransferCancelRequestDto>(envelope)

        if (!validation.isValid) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferCancelResponse,
                payload = createTransferCancelFailure(
                    request?.transferId.orEmpty(),
                    validation.errorCode ?: ProtocolErrorCodes.invalidTransferCancel
                ),
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidTransferCancel,
                errorMessage = validation.errorMessage ?: "Bluetooth transfer cancel request is malformed.",
                receiverDeviceId = request?.senderId,
                sessionId = request?.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (request == null || request.transferId.isBlank() || request.sessionId.isBlank() || request.senderId.isBlank()) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferCancelResponse,
                payload = createTransferCancelFailure(
                    request?.transferId.orEmpty(),
                    ProtocolErrorCodes.invalidTransferCancel
                ),
                errorCode = ProtocolErrorCodes.invalidTransferCancel,
                errorMessage = "Bluetooth transfer cancel metadata is missing.",
                receiverDeviceId = request?.senderId,
                sessionId = request?.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        if (session.sessionId != request.sessionId || !session.peer.id.equals(request.senderId, ignoreCase = true)) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferCancelResponse,
                payload = createTransferCancelFailure(request.transferId, ProtocolErrorCodes.sessionNotFound),
                errorCode = ProtocolErrorCodes.sessionNotFound,
                errorMessage = "The Bluetooth transfer session is not active on this Android host.",
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        val handler = fileTransferHandler
        if (handler == null) {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferCancelResponse,
                payload = createTransferCancelFailure(request.transferId, ProtocolErrorCodes.transferServiceUnavailable),
                errorCode = ProtocolErrorCodes.transferServiceUnavailable,
                errorMessage = "Bluetooth transfer service is not ready on Android.",
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
            return
        }

        val response = runCatching {
            handler.cancelIncomingBluetoothTransfer(
                request = request,
                peer = session.peer,
                sessionId = session.sessionId
            )
        }.getOrElse { exception ->
            loggerService.warning("[BT-TRANSFER] Android Bluetooth cancel failed: ${exception.message}")
            createTransferCancelFailure(request.transferId, exception.message ?: "cancel_failed")
        }

        if (response.accepted) {
            sendResponseEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferCancelResponse,
                payload = response,
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
        } else {
            sendErrorEnvelope(
                session = session,
                packetType = ProtocolPacketTypes.transferCancelResponse,
                payload = response,
                errorCode = response.failureReason ?: ProtocolErrorCodes.invalidTransferCancel,
                errorMessage = response.failureReason ?: "Bluetooth transfer cancel failed.",
                receiverDeviceId = request.senderId,
                sessionId = request.sessionId,
                correlationId = envelope.meta.messageId
            )
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(AppConstants.connectionHeartbeatIntervalSeconds * 1_000L)
                val session = activeSession ?: break
                val accepted = sendHeartbeat(session, transitionToConnected = false)
                if (!accepted) {
                    closeActiveSession(
                        reason = "Bluetooth heartbeat stopped responding.",
                        notifyRemote = false,
                        updateState = true
                    )
                    break
                }
            }
        }
    }

    private suspend fun sendHeartbeat(session: BluetoothSession, transitionToConnected: Boolean): Boolean {
        val localDevice = ensureLocalDevice()
        val response = sendRequest<ConnectionHeartbeatRequestDto, ConnectionHeartbeatResponseDto>(
            session = session,
            packetType = ProtocolPacketTypes.connectionHeartbeatRequest,
            expectedResponsePacketType = ProtocolPacketTypes.connectionHeartbeatResponse,
            payload = ConnectionHeartbeatRequestDto(
                sessionId = session.sessionId,
                deviceId = localDevice.deviceId,
                deviceName = localDevice.deviceName,
                platform = localDevice.platform,
                appVersion = localDevice.appVersion
            ),
            receiverDeviceId = session.peer.id,
            sessionId = session.sessionId
        ) ?: return false

        if (!response.alive) {
            return false
        }

        val updatedPeer = session.peer.copy(
            displayName = response.serverDeviceName,
            platform = response.serverPlatform,
            appVersion = response.serverAppVersion,
            isOnline = true,
            pairingRequired = false,
            lastSeenAtUtc = response.receivedAtUtc
        )
        session.peer = updatedPeer
        session.lastHeartbeatUtc = Instant.now().toString()
        activeSession = session
        _activePeer.value = updatedPeer
        deviceRepository.upsert(updatedPeer)

        if (transitionToConnected) {
            _connectionState.update { current ->
                current.copy(
                    lifecycleState = ConnectionLifecycleState.Connected,
                    statusText = "Connected to ${updatedPeer.displayName} over Bluetooth.",
                    sessionId = session.sessionId,
                    connectedPeer = updatedPeer,
                    selectedMode = AppConnectionMode.BluetoothFallback,
                    localPairingCode = current.localPairingCode,
                    lastError = null,
                    handshakeSummary = "Heartbeat validated Bluetooth session ${session.sessionId}.",
                    isReconnectScheduled = false,
                    reconnectAttemptCount = 0
                )
            }
        }

        return true
    }

    private suspend inline fun <reified TRequest, reified TResponse> sendRequest(
        session: BluetoothSession,
        packetType: String,
        expectedResponsePacketType: String,
        payload: TRequest,
        receiverDeviceId: String?,
        sessionId: String? = null,
        messageId: String? = null,
        sentAtUtc: String = Instant.now().toString()
    ): TResponse? {
        val localDevice = ensureLocalDevice()
        val resolvedMessageId = messageId ?: UUID.randomUUID().toString().replace("-", "")
        val envelope = ProtocolEnvelopeFactory.create(
            packetType = packetType,
            payload = payload,
            senderDeviceId = localDevice.deviceId,
            receiverDeviceId = receiverDeviceId,
            sessionId = sessionId,
            messageId = resolvedMessageId,
            sentAtUtc = sentAtUtc
        )

        val waiter = CompletableDeferred<ProtocolEnvelope<JsonElement>?>()
        session.pendingResponses[resolvedMessageId] = waiter

        return try {
            sendEnvelope(session, envelope)
            val responseEnvelope = withTimeoutOrNull(AppConstants.connectionRequestTimeoutMillis.toLong()) {
                waiter.await()
            } ?: return null

            val validation = ProtocolEnvelopeValidator.validate(
                responseEnvelope,
                true,
                expectedResponsePacketType
            )
            if (!validation.isValid) {
                null
            } else {
                responseEnvelope.payload?.let { payloadElement ->
                    ProtocolJson.format.decodeFromJsonElement<TResponse>(payloadElement)
                }
            }
        } finally {
            session.pendingResponses.remove(resolvedMessageId)
        }
    }

    private suspend inline fun <reified TRequest, reified TResponse> sendBinaryRequest(
        session: BluetoothSession,
        packetType: String,
        expectedResponsePacketType: String,
        payload: TRequest,
        binaryPayload: ByteArray,
        receiverDeviceId: String?,
        sessionId: String? = null,
        messageId: String? = null,
        sentAtUtc: String = Instant.now().toString()
    ): TResponse? {
        val localDevice = ensureLocalDevice()
        val resolvedMessageId = messageId ?: UUID.randomUUID().toString().replace("-", "")
        val envelope = ProtocolEnvelopeFactory.create(
            packetType = packetType,
            payload = payload,
            senderDeviceId = localDevice.deviceId,
            receiverDeviceId = receiverDeviceId,
            sessionId = sessionId,
            messageId = resolvedMessageId,
            sentAtUtc = sentAtUtc
        )

        val waiter = CompletableDeferred<ProtocolEnvelope<JsonElement>?>()
        session.pendingResponses[resolvedMessageId] = waiter

        return try {
            sendEnvelopeWithBinary(session, envelope, binaryPayload)
            val responseEnvelope = withTimeoutOrNull(AppConstants.connectionRequestTimeoutMillis.toLong()) {
                waiter.await()
            } ?: return null

            val validation = ProtocolEnvelopeValidator.validate(
                responseEnvelope,
                true,
                expectedResponsePacketType
            )
            if (!validation.isValid) {
                null
            } else {
                responseEnvelope.payload?.let { payloadElement ->
                    ProtocolJson.format.decodeFromJsonElement<TResponse>(payloadElement)
                }
            }
        } finally {
            session.pendingResponses.remove(resolvedMessageId)
        }
    }

    private suspend inline fun <reified TPayload> sendEnvelope(
        session: BluetoothSession,
        envelope: ProtocolEnvelope<TPayload>
    ) {
        val json = ProtocolJson.format.encodeToString(envelope)
        session.sendMutex.withLock {
            BluetoothTransportFrameCodec.writeJsonEnvelope(session.output, json)
        }
    }

    private suspend inline fun <reified TPayload> sendEnvelopeWithBinary(
        session: BluetoothSession,
        envelope: ProtocolEnvelope<TPayload>,
        binaryPayload: ByteArray
    ) {
        val json = ProtocolJson.format.encodeToString(envelope)
        session.sendMutex.withLock {
            BluetoothTransportFrameCodec.writeJsonEnvelopeWithBinary(session.output, json, binaryPayload)
        }
    }

    private suspend inline fun <reified TPayload> sendResponseEnvelope(
        session: BluetoothSession,
        packetType: String,
        payload: TPayload,
        receiverDeviceId: String?,
        sessionId: String? = null,
        correlationId: String? = null
    ) {
        val localDevice = ensureLocalDevice()
        val envelope = ProtocolEnvelopeFactory.create(
            packetType = packetType,
            payload = payload,
            senderDeviceId = localDevice.deviceId,
            receiverDeviceId = receiverDeviceId,
            sessionId = sessionId,
            correlationId = correlationId
        )
        sendEnvelope(session, envelope)
    }

    private suspend inline fun <reified TPayload> sendErrorEnvelope(
        session: BluetoothSession,
        packetType: String,
        payload: TPayload,
        errorCode: String,
        errorMessage: String,
        receiverDeviceId: String?,
        sessionId: String? = null,
        correlationId: String? = null
    ) {
        val localDevice = ensureLocalDevice()
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
        sendEnvelope(session, envelope)
    }

    private suspend inline fun <reified T> decodePayload(envelope: ProtocolEnvelope<JsonElement>): T? {
        val payload = envelope.payload ?: return null
        return runCatching {
            ProtocolJson.format.decodeFromJsonElement<T>(payload)
        }.getOrNull()
    }

    private suspend fun closeActiveSession(
        reason: String,
        notifyRemote: Boolean,
        updateState: Boolean
    ) {
        val session = activeSession ?: return
        closeSession(session, reason, notifyRemote, updateState)
    }

    private suspend fun closeSession(
        session: BluetoothSession,
        reason: String,
        notifyRemote: Boolean,
        updateState: Boolean
    ) {
        if (session.isClosed) {
            return
        }
        session.isClosed = true

        if (notifyRemote && session.sessionId.isNotBlank()) {
            runCatching {
                val localDevice = ensureLocalDevice()
                sendEnvelope(
                    session = session,
                    envelope = ProtocolEnvelopeFactory.create(
                        packetType = ProtocolPacketTypes.connectionDisconnectRequest,
                        payload = ConnectionDisconnectRequestDto(
                            sessionId = session.sessionId,
                            deviceId = localDevice.deviceId,
                            sentAtUtc = Instant.now().toString()
                        ),
                        senderDeviceId = localDevice.deviceId,
                        receiverDeviceId = session.peer.id,
                        sessionId = session.sessionId
                    )
                )
            }
        }

        if (activeSession === session) {
            activeSession = null
            _activePeer.value = null
            heartbeatJob?.cancelAndJoin()
            heartbeatJob = null
        }

        runCatching { session.socket.close() }
        session.readJob?.cancel()
        session.pendingResponses.values.forEach { it.complete(null) }
        session.pendingResponses.clear()

        if (updateState) {
            _connectionState.update { current ->
                current.copy(
                    lifecycleState = ConnectionLifecycleState.Disconnected,
                    statusText = reason,
                    sessionId = null,
                    connectedPeer = null,
                    selectedMode = AppConnectionMode.BluetoothFallback,
                    localPairingCode = current.localPairingCode,
                    lastError = reason,
                    handshakeSummary = "Bluetooth session closed.",
                    isReconnectScheduled = false,
                    reconnectAttemptCount = 0
                )
            }
        }

        loggerService.info("[BT-SESSION] Android Bluetooth session closed. $reason")
    }

    private suspend fun ensureLocalDevice(): LocalDeviceProfile {
        return localDeviceProfileRepository.getOrCreate()
    }

    private fun fail(message: String) {
        _activePeer.value = null
        _connectionState.update { current ->
            current.copy(
                lifecycleState = ConnectionLifecycleState.Failed,
                statusText = message,
                sessionId = null,
                connectedPeer = null,
                selectedMode = AppConnectionMode.BluetoothFallback,
                localPairingCode = current.localPairingCode,
                lastError = message,
                handshakeSummary = "Bluetooth pairing or connection failed."
            )
        }
        loggerService.warning("[BT-CONNECT] $message")
    }

    @SuppressLint("MissingPermission")
    private fun createClientSocket(remoteDevice: BluetoothDevice): BluetoothSocket {
        return runCatching {
            remoteDevice.createInsecureRfcommSocketToServiceRecord(UUID.fromString(AppConstants.bluetoothServiceId))
        }.getOrElse {
            remoteDevice.createRfcommSocketToServiceRecord(UUID.fromString(AppConstants.bluetoothServiceId))
        }
    }

    private fun hasBluetoothSocketPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
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
            supportedModes = listOf(AppConstants.localWifiMode, AppConstants.bluetoothMode),
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

    private fun createFailedReceipt(
        messageId: String,
        failureReason: String,
        localDevice: LocalDeviceProfile
    ): TextChatDeliveryReceiptDto {
        return TextChatDeliveryReceiptDto(
            accepted = false,
            messageId = messageId,
            status = ProtocolConstants.deliveryStatusFailed,
            failureReason = failureReason,
            receiverDeviceId = localDevice.deviceId,
            receiverDeviceName = localDevice.deviceName,
            receivedAtUtc = Instant.now().toString()
        )
    }

    private fun createTransferPrepareFailure(
        transferId: String,
        failureReason: String,
        localDevice: LocalDeviceProfile
    ): FileTransferPrepareResponseDto {
        return FileTransferPrepareResponseDto(
            accepted = false,
            transferId = transferId,
            status = ProtocolConstants.transferStateFailed,
            failureReason = failureReason,
            nextExpectedChunkIndex = 0,
            receivedBytes = 0L,
            receiverDeviceId = localDevice.deviceId,
            receiverDeviceName = localDevice.deviceName,
            suggestedFilePath = null,
            respondedAtUtc = Instant.now().toString()
        )
    }

    private fun createTransferChunkFailure(
        transferId: String,
        chunkIndex: Int,
        failureReason: String
    ): FileTransferChunkResponseDto {
        return FileTransferChunkResponseDto(
            accepted = false,
            transferId = transferId,
            chunkIndex = chunkIndex,
            status = ProtocolConstants.transferStateFailed,
            failureReason = failureReason,
            nextExpectedChunkIndex = 0,
            receivedBytes = 0L,
            respondedAtUtc = Instant.now().toString()
        )
    }

    private fun createTransferCompleteFailure(
        transferId: String,
        failureReason: String
    ): FileTransferCompleteResponseDto {
        return FileTransferCompleteResponseDto(
            accepted = false,
            transferId = transferId,
            status = ProtocolConstants.transferStateFailed,
            failureReason = failureReason,
            savedFilePath = null,
            completedAtUtc = Instant.now().toString()
        )
    }

    private fun createTransferCancelFailure(
        transferId: String,
        failureReason: String
    ): FileTransferCancelResponseDto {
        return FileTransferCancelResponseDto(
            accepted = false,
            transferId = transferId,
            status = ProtocolConstants.transferStateFailed,
            failureReason = failureReason,
            canceledAtUtc = Instant.now().toString()
        )
    }

    private fun buildTemporaryPeerId(bluetoothAddress: String): String {
        return if (bluetoothAddress.isBlank()) {
            "bt-${UUID.randomUUID().toString().replace("-", "")}"
        } else {
            "bt-" + bluetoothAddress.replace(":", "").replace("-", "").lowercase()
        }
    }

    private fun generatePairingCode(): String {
        return (100000..999999).random().toString()
    }

    private data class BluetoothSession(
        val socket: BluetoothSocket,
        var peer: DevicePeer,
        var pairingToken: String,
        val isIncoming: Boolean,
        val input: InputStream,
        val output: OutputStream,
        val sendMutex: Mutex = Mutex(),
        val pendingResponses: ConcurrentHashMap<String, CompletableDeferred<ProtocolEnvelope<JsonElement>?>> = ConcurrentHashMap()
    ) {
        var sessionId: String = ""
        var lastHeartbeatUtc: String = Instant.now().toString()
        var readJob: Job? = null
        var isClosed: Boolean = false
    }

    private fun createSession(
        socket: BluetoothSocket,
        peer: DevicePeer,
        pairingToken: String,
        isIncoming: Boolean
    ): BluetoothSession {
        return BluetoothSession(
            socket = socket,
            peer = peer,
            pairingToken = pairingToken,
            isIncoming = isIncoming,
            input = socket.inputStream,
            output = socket.outputStream
        )
    }
}
