package com.localbridge.android.services

import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.models.ConnectionLifecycleState
import com.localbridge.android.models.ConnectionStateModel
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.repositories.DeviceRepository
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaceholderConnectionService(
    private val deviceRepository: DeviceRepository,
    private val trustedDevicesService: TrustedDevicesService,
    private val loggerService: LoggerService
) : ConnectionService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _connectionState = MutableStateFlow(
        ConnectionStateModel(protocolVersion = AppConstants.protocolVersion)
    )
    private val _activePeer = MutableStateFlow<DevicePeer?>(null)

    override val connectionState: StateFlow<ConnectionStateModel> = _connectionState.asStateFlow()
    override val activePeer: StateFlow<DevicePeer?> = _activePeer.asStateFlow()

    override fun connect(peer: DevicePeer, pairingToken: String) {
        scope.launch {
            loggerService.info("Starting placeholder connection to ${peer.displayName}.")
            _connectionState.value = _connectionState.value.copy(
                lifecycleState = ConnectionLifecycleState.Connecting,
                statusText = "Connecting to ${peer.displayName}...",
                connectedPeer = peer,
                lastError = null
            )

            delay(350)

            if (pairingToken.isBlank() && peer.pairingRequired) {
                _connectionState.value = _connectionState.value.copy(
                    lifecycleState = ConnectionLifecycleState.WaitingForPairing,
                    statusText = "Pairing token required for ${peer.displayName}.",
                    lastError = "Pairing token missing."
                )
                loggerService.warning("Connection paused because pairing token is missing.")
                return@launch
            }

            _connectionState.value = _connectionState.value.copy(
                lifecycleState = ConnectionLifecycleState.Paired,
                statusText = "Paired with ${peer.displayName}. Validating session..."
            )

            delay(300)

            _activePeer.value = peer
            trustedDevicesService.trust(peer.id)
            deviceRepository.updateTrust(trustedDevicesService.trustedDeviceIds.value)
            _connectionState.value = _connectionState.value.copy(
                lifecycleState = ConnectionLifecycleState.Connected,
                statusText = "Connected to ${peer.displayName} over hotspot/LAN.",
                sessionId = "android-${Instant.now().epochSecond}",
                connectedPeer = peer,
                lastError = null
            )
            loggerService.info("Placeholder connection established with ${peer.displayName}.")
        }
    }

    override fun disconnect() {
        val peerName = _activePeer.value?.displayName ?: "peer"
        _activePeer.value = null
        _connectionState.value = _connectionState.value.copy(
            lifecycleState = ConnectionLifecycleState.Disconnected,
            statusText = "Disconnected from $peerName.",
            sessionId = null,
            connectedPeer = null
        )
        loggerService.info("Disconnected from $peerName.")
    }
}
