package com.localbridge.android.repositories

import com.localbridge.android.models.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryChatRepository : ChatRepository {
    private val _messages = MutableStateFlow(emptyList<ChatMessage>())

    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    override suspend fun load(): List<ChatMessage> {
        return _messages.value
    }

    override suspend fun append(message: ChatMessage) {
        _messages.value = (_messages.value + message).sortedBy { it.timestampUtc }
    }

    override suspend fun replace(message: ChatMessage) {
        _messages.value = _messages.value.map { existing ->
            if (existing.id == message.id) message else existing
        }.sortedBy { it.timestampUtc }
    }

    override suspend fun replaceAll(messages: List<ChatMessage>) {
        _messages.value = messages.sortedBy { it.timestampUtc }
    }
}
