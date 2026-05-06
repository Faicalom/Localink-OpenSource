package com.localink.android.services

import com.localink.android.models.ChatMessage
import kotlinx.coroutines.flow.StateFlow

interface ChatService {
    val messages: StateFlow<List<ChatMessage>>

    fun start()
    fun send(text: String)
    fun retry(messageId: String)
    fun clearHistory()
}
