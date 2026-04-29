package com.localbridge.android.services

import android.net.Uri
import com.localbridge.android.models.TransferItem
import kotlinx.coroutines.flow.StateFlow

interface FileTransferService {
    val transfers: StateFlow<List<TransferItem>>

    fun start()
    fun queueFiles(uris: List<Uri>)
    fun pause(transferId: String)
    fun resume(transferId: String)
    fun cancel(transferId: String)
    fun clearHistory()
}
