package com.localbridge.android.repositories

import com.localbridge.android.models.TransferItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryTransferRepository : TransferRepository {
    private val _transfers = MutableStateFlow(emptyList<TransferItem>())

    override val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    override suspend fun load(): List<TransferItem> = _transfers.value

    override suspend fun append(item: TransferItem) {
        _transfers.value = (listOf(item) + _transfers.value).sortedByDescending { it.createdAtUtc }
    }

    override suspend fun replace(item: TransferItem) {
        _transfers.value = _transfers.value.map { existing ->
            if (existing.id == item.id) item else existing
        }.sortedByDescending { it.createdAtUtc }
    }

    override suspend fun replaceAll(items: List<TransferItem>) {
        _transfers.value = items.sortedByDescending { it.createdAtUtc }
    }
}
