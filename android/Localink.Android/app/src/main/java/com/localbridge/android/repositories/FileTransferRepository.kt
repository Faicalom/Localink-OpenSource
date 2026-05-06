package com.localbridge.android.repositories

import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.core.protocol.ProtocolJson
import com.localbridge.android.core.storage.StorageDirectories
import com.localbridge.android.models.TransferItem
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class FileTransferRepository(
    private val storageDirectories: StorageDirectories,
    private val loggerService: LoggerService
) : TransferRepository {
    private val fileLock = Mutex()
    private val _transfers = MutableStateFlow(emptyList<TransferItem>())

    override val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    private val filePath: File
        get() = File(storageDirectories.rootDirectory, AppConstants.defaultTransferHistoryFileName)

    override suspend fun load(): List<TransferItem> = fileLock.withLock {
        val file = filePath
        if (!file.exists()) {
            _transfers.value = emptyList()
            return@withLock emptyList()
        }

        val loadedItems = runCatching {
            ProtocolJson.format.decodeFromString(
                ListSerializer(TransferItem.serializer()),
                file.readText()
            )
        }.getOrElse { exception ->
            loggerService.warning("Could not load Android transfer history: ${exception.message}")
            emptyList()
        }.sortedByDescending { it.createdAtUtc }

        _transfers.value = loadedItems
        loadedItems
    }

    override suspend fun append(item: TransferItem) = fileLock.withLock {
        _transfers.value = (_transfers.value + item).sortedByDescending { it.createdAtUtc }
        persistLocked()
    }

    override suspend fun replace(item: TransferItem) = fileLock.withLock {
        _transfers.value = _transfers.value.map { existing ->
            if (existing.id == item.id) item else existing
        }.sortedByDescending { it.createdAtUtc }
        persistLocked()
    }

    override suspend fun replaceAll(items: List<TransferItem>) = fileLock.withLock {
        _transfers.value = items.sortedByDescending { it.createdAtUtc }
        persistLocked()
    }

    private fun persistLocked() {
        val file = filePath
        file.parentFile?.mkdirs()
        val serialized = ProtocolJson.format.encodeToString(
            ListSerializer(TransferItem.serializer()),
            _transfers.value
        )
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.writeText(serialized)

        if (file.exists() && !file.delete()) {
            loggerService.warning("Could not replace Android transfer history because the old file stayed locked.")
        }

        if (!tempFile.renameTo(file)) {
            file.writeText(serialized)
            tempFile.delete()
        }
    }
}
