package com.localbridge.android.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localbridge.android.core.logging.LogEntry
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.core.storage.StorageDirectories
import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.AppLanguage
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.models.LocalBridgeSettings
import com.localbridge.android.repositories.DeviceRepository
import com.localbridge.android.repositories.SettingsRepository
import com.localbridge.android.services.ChatService
import com.localbridge.android.services.FileTransferService
import com.localbridge.android.services.TrustedDevicesService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: LocalBridgeSettings = LocalBridgeSettings(),
    val recentLogs: List<LogEntry> = emptyList(),
    val trustedDevices: List<DevicePeer> = emptyList(),
    val resolvedPrivateReceiveFolderPath: String = "",
    val publicReceiveFolderPath: String = "",
    val activeReceiveLocationDescription: String = ""
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val trustedDevicesService: TrustedDevicesService,
    private val chatService: ChatService,
    private val fileTransferService: FileTransferService,
    deviceRepository: DeviceRepository,
    loggerService: LoggerService,
    private val storageDirectories: StorageDirectories
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        loggerService.recentEntries,
        trustedDevicesService.trustedDeviceIds,
        deviceRepository.devices
    ) { settings, logs, trustedIds, devices ->
        val trustedDevices = trustedIds.map { trustedId ->
            devices.firstOrNull { peer -> peer.id == trustedId }
                ?: DevicePeer(
                    id = trustedId,
                    displayName = trustedId,
                    platform = "Stored device",
                    ipAddress = "--",
                    port = 0,
                    appVersion = "unknown",
                    supportedModes = emptyList(),
                    isTrusted = true,
                    isOnline = false,
                    pairingRequired = false,
                    lastSeenAtUtc = ""
                )
        }.sortedBy { device -> device.displayName.lowercase() }

        SettingsUiState(
            settings = settings,
            recentLogs = logs.takeLast(10).reversed(),
            trustedDevices = trustedDevices,
            resolvedPrivateReceiveFolderPath = storageDirectories.resolveReceivedDirectory(settings.receiveFolderName).absolutePath,
            publicReceiveFolderPath = storageDirectories.publicDownloadsDisplayPath(),
            activeReceiveLocationDescription = if (settings.hasExternalReceiveFolder) {
                "External SAF folder · ${settings.receiveTreeDisplayName ?: settings.receiveTreeUri.orEmpty()} · Download/${com.localbridge.android.core.AppConstants.defaultPublicDownloadsFolderName}"
            } else {
                "Download/${com.localbridge.android.core.AppConstants.defaultPublicDownloadsFolderName} · ${storageDirectories.resolveReceivedDirectory(settings.receiveFolderName).absolutePath}"
            }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    fun updatePreferredMode(mode: AppConnectionMode) {
        viewModelScope.launch {
            settingsRepository.updatePreferredMode(mode)
        }
    }

    fun updateReceiveFolderLabel(label: String) {
        viewModelScope.launch {
            settingsRepository.updateReceiveFolderLabel(label)
        }
    }

    fun updateReceiveTree(uri: String, displayName: String) {
        viewModelScope.launch {
            settingsRepository.updateReceiveTree(uri, displayName)
        }
    }

    fun clearReceiveTree() {
        viewModelScope.launch {
            settingsRepository.clearReceiveTree()
        }
    }

    fun updateDarkTheme(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateDarkThemeEnabled(enabled)
        }
    }

    fun updateDeviceAlias(alias: String) {
        viewModelScope.launch {
            settingsRepository.updateDeviceAlias(alias)
        }
    }

    fun updateLanguage(language: AppLanguage) {
        viewModelScope.launch {
            settingsRepository.updateLanguage(language)
        }
    }

    fun removeTrustedDevice(deviceId: String) {
        viewModelScope.launch {
            trustedDevicesService.untrust(deviceId)
        }
    }

    fun clearChatHistory() {
        chatService.clearHistory()
    }

    fun clearTransferHistory() {
        fileTransferService.clearHistory()
    }
}
