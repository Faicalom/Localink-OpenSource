package com.localink.android.services

import android.net.Uri
import com.localink.android.models.TransferItem
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
