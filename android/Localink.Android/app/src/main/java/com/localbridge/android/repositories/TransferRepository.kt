package com.localbridge.android.repositories

import com.localbridge.android.models.TransferItem
import kotlinx.coroutines.flow.StateFlow

interface TransferRepository {
    val transfers: StateFlow<List<TransferItem>>

    suspend fun load(): List<TransferItem>
    suspend fun append(item: TransferItem)
    suspend fun replace(item: TransferItem)
    suspend fun replaceAll(items: List<TransferItem>)
}
