package com.localbridge.android.features.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localbridge.android.models.ChatMessage
import com.localbridge.android.models.ConnectionStateModel
import com.localbridge.android.services.ChatService
import com.localbridge.android.services.ConnectionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val connectionState: ConnectionStateModel,
    val draftMessage: String = ""
) {
    val canSend: Boolean
        get() = draftMessage.isNotBlank()
}

class ChatViewModel(
    private val chatService: ChatService,
    private val connectionService: ConnectionService
) : ViewModel() {
    private val draftMessage = MutableStateFlow("")

    val uiState: StateFlow<ChatUiState> = combine(
        chatService.messages,
        connectionService.connectionState,
        draftMessage
    ) { messages, connectionState, draft ->
        ChatUiState(
            messages = messages,
            connectionState = connectionState,
            draftMessage = draft
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState(connectionState = connectionService.connectionState.value)
    )

    fun updateDraftMessage(value: String) {
        draftMessage.value = value
    }

    fun send() {
        if (draftMessage.value.isBlank()) {
            return
        }
        chatService.send(draftMessage.value)
        draftMessage.value = ""
    }

    fun retry(message: ChatMessage) {
        chatService.retry(message.id)
    }
}
