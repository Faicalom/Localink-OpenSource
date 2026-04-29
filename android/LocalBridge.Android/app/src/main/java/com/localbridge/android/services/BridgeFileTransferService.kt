package com.localbridge.android.services

import android.net.Uri
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.TransferItem
import kotlinx.coroutines.flow.StateFlow

class BridgeFileTransferService(
    private val lanFileTransferService: LanFileTransferService,
    private val bluetoothConnectionService: BluetoothConnectionService,
    private val connectionService: ConnectionService,
    private val loggerService: LoggerService
) : FileTransferService {
    override val transfers: StateFlow<List<TransferItem>> = lanFileTransferService.transfers

    override fun start() {
        lanFileTransferService.start()
        bluetoothConnectionService.registerFileTransferHandler(lanFileTransferService)
    }

    override fun queueFiles(uris: List<Uri>) {
        val activePeer = connectionService.activePeer.value
        if (activePeer?.transportMode == AppConnectionMode.BluetoothFallback) {
            loggerService.info("[BT-TRANSFER] Android is sending file(s) over Bluetooth fallback to ${activePeer.displayName}.")
        }
        lanFileTransferService.queueFiles(uris)
    }

    override fun pause(transferId: String) {
        lanFileTransferService.pause(transferId)
    }

    override fun resume(transferId: String) {
        lanFileTransferService.resume(transferId)
    }

    override fun cancel(transferId: String) {
        lanFileTransferService.cancel(transferId)
    }

    override fun clearHistory() {
        lanFileTransferService.clearHistory()
    }
}
