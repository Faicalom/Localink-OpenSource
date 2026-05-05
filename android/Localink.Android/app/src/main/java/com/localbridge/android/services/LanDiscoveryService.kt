package com.localbridge.android.services

import android.content.Context
import android.net.wifi.WifiManager
import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.core.network.LocalNetworkResolver
import com.localbridge.android.core.protocol.DiscoveryPacket
import com.localbridge.android.core.protocol.ProtocolEnvelopeFactory
import com.localbridge.android.core.protocol.ProtocolEnvelopeValidator
import com.localbridge.android.core.protocol.ProtocolPacketTypes
import com.localbridge.android.core.protocol.decodeEnvelope
import com.localbridge.android.core.protocol.encodeEnvelope
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.models.DiscoveryStateModel
import com.localbridge.android.repositories.DeviceRepository
import com.localbridge.android.repositories.LocalDeviceProfileRepository
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.Instant
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

class LanDiscoveryService(
    context: Context,
    private val deviceRepository: DeviceRepository,
    private val trustedDevicesService: TrustedDevicesService,
    private val localDeviceProfileRepository: LocalDeviceProfileRepository,
    private val loggerService: LoggerService
) : DiscoveryService {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val _discoveryState = MutableStateFlow(DiscoveryStateModel())

    private var listenerSocket: DatagramSocket? = null
    private var senderSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenJob: Job? = null
    private var announcementJob: Job? = null
    private var cleanupJob: Job? = null
    private var scanWindowJob: Job? = null
    private var isStarted = false
    private var localDeviceId: String? = null

    override val devices: StateFlow<List<DevicePeer>> = deviceRepository.devices
    override val discoveryState: StateFlow<DiscoveryStateModel> = _discoveryState.asStateFlow()

    override fun start() {
        scope.launch {
            lifecycleMutex.withLock {
                if (isStarted) {
                    return@withLock
                }

                ensureSockets()
                isStarted = true
                _discoveryState.value = DiscoveryStateModel(
                    isRunning = true,
                    isScanning = false,
                    statusText = "Listening for Localink peers on the local hotspot/LAN."
                )

                listenJob = scope.launch { listenLoop() }
                announcementJob = scope.launch { announcementLoop() }
                cleanupJob = scope.launch { cleanupLoop() }

                loggerService.info("[DISCOVERY] Android LAN discovery started on UDP ${AppConstants.defaultDiscoveryPort}.")
                triggerScanLocked("initial scan")
            }
        }
    }

    override fun refresh() {
        scope.launch {
            lifecycleMutex.withLock {
                if (!isStarted) {
                    start()
                    return@withLock
                }

                triggerScanLocked("manual refresh")
            }
        }
    }

    override fun stop() {
        scope.launch {
            lifecycleMutex.withLock {
                if (!isStarted) {
                    return@withLock
                }

                isStarted = false
                listenerSocket?.close()
                senderSocket?.close()
                multicastLock?.let { lock ->
                    runCatching {
                        if (lock.isHeld) {
                            lock.release()
                        }
                    }
                }
                multicastLock = null
            }

            listenJob.cancelAndJoinSafely()
            announcementJob.cancelAndJoinSafely()
            cleanupJob.cancelAndJoinSafely()
            scanWindowJob.cancelAndJoinSafely()

            listenerSocket = null
            senderSocket = null
            listenJob = null
            announcementJob = null
            cleanupJob = null
            scanWindowJob = null

            deviceRepository.replaceAll(emptyList())
            _discoveryState.value = DiscoveryStateModel(
                isRunning = false,
                isScanning = false,
                statusText = "Discovery stopped."
            )

            loggerService.info("[DISCOVERY] Android LAN discovery stopped and peer cache cleared.")
        }
    }

    private suspend fun ensureSockets() {
        val localProfile = localDeviceProfileRepository.getOrCreate()
        localDeviceId = localProfile.deviceId

        multicastLock = LocalNetworkResolver.acquireMulticastLock(appContext)
        listenerSocket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1_000
            bind(InetSocketAddress(AppConstants.defaultDiscoveryPort))
        }
        senderSocket = DatagramSocket().apply {
            reuseAddress = true
            broadcast = true
        }
    }

    private suspend fun triggerScanLocked(reason: String) {
        _discoveryState.update { current ->
            current.copy(
                isRunning = true,
                isScanning = true,
                statusText = "Scanning the local hotspot/LAN for Windows peers...",
                lastError = null
            )
        }

        sendProbe(reason)

        scanWindowJob?.cancel()
        scanWindowJob = scope.launch {
            delay(AppConstants.discoveryScanWindowMillis)
            val peerCount = devices.value.size
            _discoveryState.update { current ->
                current.copy(
                    isRunning = true,
                    isScanning = false,
                    lastScanAtUtc = Instant.now().toString(),
                    statusText = if (peerCount == 0) {
                        "Scan finished. No compatible Windows peers replied yet."
                    } else {
                        "Scan finished. Found $peerCount Windows peer(s)."
                    }
                )
            }
        }
    }

    private suspend fun announcementLoop() {
        while (scope.isActive && isStarted) {
            try {
                delay(AppConstants.discoveryAnnouncementIntervalSeconds * 1_000L)
                sendProbe(null)
            } catch (exception: Exception) {
                if (!isStarted) {
                    break
                }
                loggerService.error("[DISCOVERY] Android scan loop error: ${exception.message}", exception)
            }
        }
    }

    private suspend fun cleanupLoop() {
        while (scope.isActive && isStarted) {
            try {
                delay(3_000L)
                cleanupStalePeers()
            } catch (exception: Exception) {
                if (!isStarted) {
                    break
                }
                loggerService.error("[DISCOVERY] Android cleanup error: ${exception.message}", exception)
            }
        }
    }

    private suspend fun listenLoop() {
        val buffer = ByteArray(65_507)

        while (scope.isActive && isStarted) {
            try {
                val socket = listenerSocket ?: break
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                handlePacket(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                if (!isStarted) {
                    break
                }
            } catch (exception: Exception) {
                loggerService.error("[DISCOVERY] Android listener error: ${exception.message}", exception)
                _discoveryState.update { current ->
                    current.copy(
                        isRunning = isStarted,
                        lastError = exception.message,
                        statusText = "Discovery hit an error and is retrying."
                    )
                }
                delay(1_000L)
            }
        }
    }

    private suspend fun handlePacket(packet: DatagramPacket) {
        val json = packet.data.decodeToString(startIndex = 0, endIndex = packet.length)
        val envelope = runCatching { decodeEnvelope<DiscoveryPacket>(json) }
            .getOrElse { exception ->
                loggerService.warning("[DISCOVERY] Ignored malformed packet: ${exception.message}")
                return
            }

        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            expectedPacketTypes = *arrayOf(
                ProtocolPacketTypes.discoveryProbe,
                ProtocolPacketTypes.discoveryReply,
                ProtocolPacketTypes.discoveryAnnouncement
            )
        )

        if (!validation.isValid) {
            loggerService.warning(
                "[DISCOVERY] Ignored packet from ${packet.address.hostAddress}: ${validation.errorMessage ?: "invalid packet"}"
            )
            return
        }

        val payload = envelope.payload ?: return
        val runtimeLocalDeviceId = localDeviceId ?: localDeviceProfileRepository.getOrCreate().deviceId.also { localDeviceId = it }
        if (payload.deviceId.isBlank() || payload.apiPort <= 0) {
            loggerService.warning("[DISCOVERY] Ignored packet because required device metadata was missing.")
            return
        }

        if (payload.deviceId.equals(runtimeLocalDeviceId, ignoreCase = true)) {
            return
        }

        if (!payload.platform.equals(AppConstants.desktopPlatformName, ignoreCase = true)) {
            return
        }

        loggerService.info("[DISCOVERY] Received ${envelope.meta.packetType} from ${packet.address.hostAddress}.")
        upsertPeer(payload, packet.address)
    }

    private suspend fun upsertPeer(packet: DiscoveryPacket, remoteAddress: InetAddress) {
        val now = Instant.now().toString()
        val existingPeer = devices.value.firstOrNull { peer -> peer.id == packet.deviceId }
        val updatedPeer = DevicePeer(
            id = packet.deviceId,
            displayName = packet.deviceName,
            platform = packet.platform,
            ipAddress = resolvePeerIp(packet.localIp, remoteAddress),
            port = packet.apiPort,
            appVersion = packet.appVersion,
            supportedModes = packet.supportedModes,
            isTrusted = trustedDevicesService.trustedDeviceIds.value.contains(packet.deviceId),
            isOnline = true,
            pairingRequired = packet.pairingRequired,
            lastSeenAtUtc = now
        )

        deviceRepository.upsert(updatedPeer)

        when {
            existingPeer == null -> {
                loggerService.info("[DISCOVERY] Discovered Windows peer ${updatedPeer.displayName} at ${updatedPeer.ipAddress}:${updatedPeer.port}.")
            }
            existingPeer.ipAddress != updatedPeer.ipAddress || existingPeer.port != updatedPeer.port -> {
                loggerService.info("[DISCOVERY] Updated peer endpoint for ${updatedPeer.displayName} to ${updatedPeer.ipAddress}:${updatedPeer.port}.")
            }
        }

        if (_discoveryState.value.isScanning) {
            val peerCount = devices.value.size
            _discoveryState.update { current ->
                current.copy(
                    statusText = "Scanning the LAN... $peerCount Windows peer(s) discovered so far."
                )
            }
        }
    }

    private suspend fun cleanupStalePeers() {
        val threshold = Instant.now().minusSeconds(AppConstants.discoveryPeerStaleTimeoutSeconds.toLong())
        val stalePeerIds = devices.value.map { it.id }

        stalePeerIds.forEach { deviceId ->
            val currentPeer = devices.value.firstOrNull { it.id == deviceId } ?: return@forEach
            val seenAt = runCatching { Instant.parse(currentPeer.lastSeenAtUtc) }.getOrDefault(Instant.EPOCH)
            if (!seenAt.isBefore(threshold)) {
                return@forEach
            }

            deviceRepository.remove(currentPeer.id)
            loggerService.warning("[DISCOVERY] Removed stale Windows peer ${currentPeer.displayName} after discovery timeout.")
        }

        if (!_discoveryState.value.isScanning) {
            _discoveryState.update { current ->
                current.copy(
                    statusText = if (devices.value.isEmpty()) {
                        "Waiting for Windows peers on the local network."
                    } else {
                        "Tracking ${devices.value.size} active Windows peer(s)."
                    }
                )
            }
        }
    }

    private suspend fun sendProbe(reason: String?) {
        runCatching {
            val packet = createPacket()
            broadcast(ProtocolPacketTypes.discoveryProbe, packet)
            if (!reason.isNullOrBlank()) {
                loggerService.info("[DISCOVERY] Sent Android probe for $reason.")
            }
        }.onFailure { exception ->
            loggerService.error("[DISCOVERY] Android probe failed: ${exception.message}", exception)
            _discoveryState.update { current ->
                current.copy(
                    isRunning = isStarted,
                    isScanning = false,
                    lastError = exception.message,
                    statusText = "Discovery could not broadcast on the local network."
                )
            }
        }
    }

    private suspend fun broadcast(packetType: String, packet: DiscoveryPacket) {
        val socket = senderSocket ?: return
        val envelope = ProtocolEnvelopeFactory.create(
            packetType = packetType,
            payload = packet,
            senderDeviceId = packet.deviceId,
            sentAtUtc = packet.sentAtUtc
        )
        val bytes = encodeEnvelope(envelope).toByteArray(Charsets.UTF_8)
        var successfulEndpointCount = 0

        val broadcastRoutes = LocalNetworkResolver.getBroadcastRoutes()
        if (broadcastRoutes.isNotEmpty()) {
            broadcastRoutes.forEach { route ->
                runCatching {
                    DatagramSocket(0, route.localAddress).use { routeSocket ->
                        routeSocket.broadcast = true
                        routeSocket.send(
                            DatagramPacket(
                                bytes,
                                bytes.size,
                                route.broadcastAddress,
                                AppConstants.defaultDiscoveryPort
                            )
                        )
                    }
                }.onSuccess {
                    successfulEndpointCount += 1
                }.onFailure { exception ->
                    loggerService.warning(
                        "[DISCOVERY] Skipped Android broadcast route ${route.localAddress.hostAddress} -> ${route.broadcastAddress.hostAddress}: ${exception.message}"
                    )
                }
            }
        } else {
            runCatching {
                socket.send(
                    DatagramPacket(
                        bytes,
                        bytes.size,
                        InetAddress.getByName("255.255.255.255"),
                        AppConstants.defaultDiscoveryPort
                    )
                )
            }.onSuccess {
                successfulEndpointCount += 1
            }.onFailure { exception ->
                loggerService.warning(
                    "[DISCOVERY] Global Android broadcast failed: ${exception.message}"
                )
            }
        }

        if (successfulEndpointCount == 0) {
            error("no_discovery_broadcast_route")
        }
    }

    private suspend fun createPacket(): DiscoveryPacket {
        val localProfile = localDeviceProfileRepository.getOrCreate()
        localDeviceId = localProfile.deviceId

        return DiscoveryPacket(
            deviceId = localProfile.deviceId,
            deviceName = localProfile.deviceName,
            platform = localProfile.platform,
            localIp = LocalNetworkResolver.firstLocalIpv4Address(),
            apiPort = AppConstants.defaultApiPort,
            appVersion = localProfile.appVersion,
            supportedModes = localProfile.supportedModes,
            pairingRequired = true,
            sentAtUtc = Instant.now().toString()
        )
    }

    private fun resolvePeerIp(advertisedIp: String, remoteAddress: InetAddress): String {
        val remoteIp = remoteAddress.hostAddress.orEmpty()
        return if (remoteIp.isNotBlank() && remoteIp != "0.0.0.0") {
            remoteIp
        } else {
            advertisedIp.ifBlank { "0.0.0.0" }
        }
    }

    private suspend fun Job?.cancelAndJoinSafely() {
        this ?: return
        runCatching {
            cancelAndJoin()
        }
    }
}
