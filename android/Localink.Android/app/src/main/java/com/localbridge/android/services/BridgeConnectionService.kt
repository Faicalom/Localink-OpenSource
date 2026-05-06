package com.localbridge.android.services

import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.ConnectionLifecycleState
import com.localbridge.android.models.ConnectionStateModel
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.repositories.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BridgeConnectionService(
    private val settingsRepository: SettingsRepository,
    private val lanConnectionService: ConnectionService,
    private val bluetoothConnectionService: BluetoothConnectionService
) : ConnectionService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _connectionState = MutableStateFlow(ConnectionStateModel(protocolVersion = com.localbridge.android.core.AppConstants.protocolVersion))
    private val _activePeer = MutableStateFlow<DevicePeer?>(null)
    private var started = false
    private var requestedTransport = AppConnectionMode.LocalWifiLan

    override val connectionState: StateFlow<ConnectionStateModel> = _connectionState.asStateFlow()
    override val activePeer: StateFlow<DevicePeer?> = _activePeer.asStateFlow()

    override fun start() {
        if (!started) {
            started = true
            scope.launch {
                settingsRepository.settings.collectLatest { settings ->
                    requestedTransport = settings.preferredMode
                    mergeState(
                        lanState = lanConnectionService.connectionState.value,
                        lanPeer = lanConnectionService.activePeer.value,
                        bluetoothState = bluetoothConnectionService.connectionState.value,
                        bluetoothPeer = bluetoothConnectionService.activePeer.value
                    )
                }
            }
            scope.launch {
                lanConnectionService.connectionState.collectLatest { lanState ->
                    mergeState(lanState, lanConnectionService.activePeer.value, bluetoothConnectionService.connectionState.value, bluetoothConnectionService.activePeer.value)
                }
            }
            scope.launch {
                lanConnectionService.activePeer.collectLatest { lanPeer ->
                    mergeState(lanConnectionService.connectionState.value, lanPeer, bluetoothConnectionService.connectionState.value, bluetoothConnectionService.activePeer.value)
                }
            }
            scope.launch {
                bluetoothConnectionService.connectionState.collectLatest { bluetoothState ->
                    mergeState(lanConnectionService.connectionState.value, lanConnectionService.activePeer.value, bluetoothState, bluetoothConnectionService.activePeer.value)
                }
            }
            scope.launch {
                bluetoothConnectionService.activePeer.collectLatest { bluetoothPeer ->
                    mergeState(lanConnectionService.connectionState.value, lanConnectionService.activePeer.value, bluetoothConnectionService.connectionState.value, bluetoothPeer)
                }
            }
        }

        lanConnectionService.start()
        bluetoothConnectionService.start()
    }

    override fun connect(peer: DevicePeer, pairingToken: String) {
        requestedTransport = peer.transportMode
        if (peer.transportMode == AppConnectionMode.BluetoothFallback) {
            lanConnectionService.disconnect()
            bluetoothConnectionService.connect(peer, pairingToken)
        } else {
            bluetoothConnectionService.disconnect()
            lanConnectionService.connect(peer, pairingToken)
        }
    }

    override fun disconnect() {
        if (activePeer.value?.transportMode == AppConnectionMode.BluetoothFallback) {
            bluetoothConnectionService.disconnect()
        } else {
            lanConnectionService.disconnect()
            bluetoothConnectionService.disconnect()
        }
    }

    suspend fun currentPreferredMode(): AppConnectionMode = settingsRepository.settings.first().preferredMode

    private fun mergeState(
        lanState: ConnectionStateModel,
        lanPeer: DevicePeer?,
        bluetoothState: ConnectionStateModel,
        bluetoothPeer: DevicePeer?
    ) {
        val useBluetooth = when {
            bluetoothPeer != null -> true
            bluetoothState.lifecycleState.isEngaged() -> true
            lanPeer != null -> false
            lanState.lifecycleState.isEngaged() -> false
            requestedTransport == AppConnectionMode.BluetoothFallback -> true
            else -> false
        }

        val selectedState = if (useBluetooth) bluetoothState else lanState
        val selectedPeer = if (useBluetooth) bluetoothPeer else lanPeer
        _activePeer.value = selectedPeer
        _connectionState.value = selectedState.copy(
            selectedMode = requestedTransport,
            localPairingCode = bluetoothState.localPairingCode.ifBlank { selectedState.localPairingCode },
            connectedPeer = selectedPeer ?: selectedState.connectedPeer
        )
    }

    private fun ConnectionLifecycleState.isEngaged(): Boolean {
        return this !in setOf(ConnectionLifecycleState.Idle, ConnectionLifecycleState.Disconnected)
    }
}
