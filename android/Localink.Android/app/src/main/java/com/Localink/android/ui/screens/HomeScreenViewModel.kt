package com.localink.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localink.android.core.AppConstants
import com.localink.android.models.ConnectionStateModel
import com.localink.android.models.DiscoveryStateModel
import com.localink.android.models.LocalinkSettings
import com.localink.android.repositories.SettingsRepository
import com.localink.android.services.ConnectionService
import com.localink.android.services.DiscoveryService
import com.localink.android.services.TrustedDevicesService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val connectionState: ConnectionStateModel,
    val discoveryState: DiscoveryStateModel = DiscoveryStateModel(),
    val settings: LocalinkSettings = LocalinkSettings(),
    val discoveredPeersCount: Int = 0,
    val trustedDevicesCount: Int = 0,
    val protocolVersion: String = AppConstants.protocolVersion
)

class HomeScreenViewModel(
    discoveryService: DiscoveryService,
    connectionService: ConnectionService,
    settingsRepository: SettingsRepository,
    trustedDevicesService: TrustedDevicesService
) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = combine(
        connectionService.connectionState,
        discoveryService.discoveryState,
        settingsRepository.settings,
        discoveryService.devices,
        trustedDevicesService.trustedDeviceIds
    ) { connectionState, discoveryState, settings, devices, trustedIds ->
        HomeUiState(
            connectionState = connectionState,
            discoveryState = discoveryState,
            settings = settings,
            discoveredPeersCount = devices.size,
            trustedDevicesCount = trustedIds.size
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(
            connectionState = connectionService.connectionState.value,
            discoveryState = discoveryService.discoveryState.value
        )
    )
}
