package com.localink.android.features.transfers

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localink.android.core.storage.StorageDirectories
import com.localink.android.models.ConnectionStateModel
import com.localink.android.models.LocalinkSettings
import com.localink.android.models.TransferItem
import com.localink.android.repositories.SettingsRepository
import com.localink.android.services.ConnectionService
import com.localink.android.services.FileTransferService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class TransfersUiState(
    val transfers: List<TransferItem> = emptyList(),
    val connectionState: ConnectionStateModel,
    val settings: LocalinkSettings = LocalinkSettings(),
    val activeReceiveLocationDescription: String = "",
    val fallbackReceiveFolderPath: String = "",
    val publicReceiveFolderPath: String = ""
)

class TransfersViewModel(
    private val fileTransferService: FileTransferService,
    private val connectionService: ConnectionService,
    settingsRepository: SettingsRepository,
    private val storageDirectories: StorageDirectories
) : ViewModel() {
    val uiState: StateFlow<TransfersUiState> = combine(
        fileTransferService.transfers,
        connectionService.connectionState,
        settingsRepository.settings
    ) { transfers, connectionState, settings ->
        TransfersUiState(
            transfers = transfers,
            connectionState = connectionState,
            settings = settings,
            activeReceiveLocationDescription = if (settings.hasExternalReceiveFolder) {
                "External SAF folder · ${settings.receiveTreeDisplayName ?: settings.receiveTreeUri.orEmpty()} · Download/${com.localink.android.core.AppConstants.defaultPublicDownloadsFolderName}"
            } else {
                "Download/${com.localink.android.core.AppConstants.defaultPublicDownloadsFolderName} · ${storageDirectories.resolveReceivedDirectory(settings.receiveFolderName).absolutePath}"
            },
            fallbackReceiveFolderPath = storageDirectories.resolveReceivedDirectory(settings.receiveFolderName).absolutePath,
            publicReceiveFolderPath = storageDirectories.publicDownloadsDisplayPath()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TransfersUiState(connectionState = connectionService.connectionState.value)
    )

    fun queueFiles(uris: List<Uri>) {
        if (uris.isEmpty()) {
            return
        }
        fileTransferService.queueFiles(uris)
    }

    fun pause(transfer: TransferItem) {
        fileTransferService.pause(transfer.id)
    }

    fun resume(transfer: TransferItem) {
        fileTransferService.resume(transfer.id)
    }

    fun cancel(transfer: TransferItem) {
        fileTransferService.cancel(transfer.id)
    }
}
