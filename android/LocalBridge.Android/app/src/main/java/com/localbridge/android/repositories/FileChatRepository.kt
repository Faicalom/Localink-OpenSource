package com.localbridge.android.repositories

import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.core.protocol.ProtocolJson
import com.localbridge.android.core.storage.StorageDirectories
import com.localbridge.android.models.ChatMessage
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class FileChatRepository(
    private val storageDirectories: StorageDirectories,
    private val loggerService: LoggerService
) : ChatRepository {
    private val fileLock = Mutex()
    private val _messages = MutableStateFlow(emptyList<ChatMessage>())

    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val filePath: File
        get() = File(storageDirectories.rootDirectory, AppConstants.defaultChatHistoryFileName)

    override suspend fun load(): List<ChatMessage> = fileLock.withLock {
        val file = filePath
        if (!file.exists()) {
            _messages.value = emptyList()
            return@withLock emptyList()
        }

        val loadedMessages = runCatching {
            ProtocolJson.format.decodeFromString(
                ListSerializer(ChatMessage.serializer()),
                file.readText()
            )
        }.getOrElse { exception ->
            loggerService.warning("Could not load Android chat history: ${exception.message}")
            emptyList()
        }.sortedBy { it.timestampUtc }

        _messages.value = loadedMessages
        loadedMessages
    }

    override suspend fun append(message: ChatMessage) = fileLock.withLock {
        _messages.value = (_messages.value + message).sortedBy { it.timestampUtc }
        persistLocked()
    }

    override suspend fun replace(message: ChatMessage) = fileLock.withLock {
        _messages.value = _messages.value.map { existing ->
            if (existing.id == message.id) message else existing
        }.sortedBy { it.timestampUtc }
        persistLocked()
    }

    override suspend fun replaceAll(messages: List<ChatMessage>) = fileLock.withLock {
        _messages.value = messages.sortedBy { it.timestampUtc }
        persistLocked()
    }

    private fun persistLocked() {
        val file = filePath
        file.parentFile?.mkdirs()
        file.writeText(
            ProtocolJson.format.encodeToString(
                ListSerializer(ChatMessage.serializer()),
                _messages.value
            )
        )
    }
}
