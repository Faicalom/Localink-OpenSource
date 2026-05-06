package com.localbridge.android.repositories

import com.localbridge.android.models.ChatMessage
import kotlinx.coroutines.flow.StateFlow

interface ChatRepository {
    val messages: StateFlow<List<ChatMessage>>

    suspend fun load(): List<ChatMessage>
    suspend fun append(message: ChatMessage)
    suspend fun replace(message: ChatMessage)
    suspend fun replaceAll(messages: List<ChatMessage>)
}
