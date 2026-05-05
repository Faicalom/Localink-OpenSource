package com.localbridge.android.features.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localbridge.android.models.ConnectionStateModel
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.models.DiscoveryStateModel
import com.localbridge.android.repositories.DeviceRepository
import com.localbridge.android.services.ConnectionService
import com.localbridge.android.services.DiscoveryService
import com.localbridge.android.services.TrustedDevicesService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DevicesUiState(
    val devices: List<DevicePeer> = emptyList(),
    val connectionState: ConnectionStateModel,
    val discoveryState: DiscoveryStateModel = DiscoveryStateModel(),
    val pairingToken: String = ""
)

class DevicesViewModel(
    private val discoveryService: DiscoveryService,
    private val connectionService: ConnectionService,
    private val trustedDevicesService: TrustedDevicesService,
    private val deviceRepository: DeviceRepository
) : ViewModel() {
    private val pairingToken = MutableStateFlow("")

    val uiState: StateFlow<DevicesUiState> = combine(
        discoveryService.devices,
        discoveryService.discoveryState,
        connectionService.connectionState,
        trustedDevicesService.trustedDeviceIds,
        pairingToken
    ) { devices, discoveryState, connectionState, trustedIds, token ->
        DevicesUiState(
            devices = devices.map { peer ->
                peer.copy(isTrusted = trustedIds.contains(peer.id))
            },
            connectionState = connectionState,
            discoveryState = discoveryState,
            pairingToken = token
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DevicesUiState(
            connectionState = connectionService.connectionState.value,
            discoveryState = discoveryService.discoveryState.value
        )
    )

    fun updatePairingToken(value: String) {
        pairingToken.value = value
    }

    fun refresh() {
        discoveryService.refresh()
    }

    fun connect(peer: DevicePeer) {
        connectionService.connect(peer, pairingToken.value)
    }

    fun disconnect() {
        connectionService.disconnect()
    }

    fun toggleTrust(peer: DevicePeer) {
        viewModelScope.launch {
            val updatedTrustedIds = if (peer.isTrusted) {
                trustedDevicesService.trustedDeviceIds.value - peer.id
            } else {
                trustedDevicesService.trustedDeviceIds.value + peer.id
            }

            if (peer.isTrusted) {
                trustedDevicesService.untrust(peer.id)
            } else {
                trustedDevicesService.trust(peer.id)
            }
            deviceRepository.updateTrust(updatedTrustedIds)
        }
    }
}
