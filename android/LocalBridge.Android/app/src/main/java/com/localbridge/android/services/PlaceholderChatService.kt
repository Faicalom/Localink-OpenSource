package com.localbridge.android.services

import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.models.ChatMessage
import com.localbridge.android.models.ChatMessageStatus
import com.localbridge.android.repositories.ChatRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlaceholderChatService(
    private val chatRepository: ChatRepository,
    private val connectionService: ConnectionService,
    private val loggerService: LoggerService
) : ChatService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val messages: StateFlow<List<ChatMessage>> = chatRepository.messages

    override fun start() {
        scope.launch {
            chatRepository.load()
            if (chatRepository.messages.value.isEmpty()) {
                chatRepository.append(
                    ChatMessage(
                        id = "welcome-message",
                        senderId = "windows-reference-desktop",
                        receiverId = "android-client",
                        senderName = "Localink Desktop",
                        text = "Android scaffold is ready. Live LAN messaging will plug into the shared protocol next.",
                        timestampUtc = Instant.now().toString(),
                        status = ChatMessageStatus.Delivered,
                        isMine = false,
                        deliveryAttempts = 1
                    )
                )
            }
        }
    }

    override fun send(text: String) {
        if (text.isBlank()) {
            return
        }

        scope.launch {
            val peer = connectionService.activePeer.value
            val message = ChatMessage(
                id = UUID.randomUUID().toString(),
                senderId = "android-client",
                receiverId = peer?.id ?: "no-peer",
                    senderName = "Android Client",
                    text = text,
                    timestampUtc = Instant.now().toString(),
                    status = ChatMessageStatus.Sending,
                    isMine = true
                )
            chatRepository.append(message)

            delay(250)

            if (peer == null) {
                chatRepository.replace(message.copy(status = ChatMessageStatus.Failed))
                loggerService.warning("Chat message failed because no peer is connected.")
                return@launch
            }

            chatRepository.replace(message.copy(status = ChatMessageStatus.Delivered))
            loggerService.info("Placeholder chat message delivered to ${peer.displayName}.")

            delay(300)
            chatRepository.append(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    senderId = peer.id,
                    receiverId = "android-client",
                    senderName = peer.displayName,
                    text = "Protocol-aligned Android foundation received your draft. Networking comes next.",
                    timestampUtc = Instant.now().toString(),
                    status = ChatMessageStatus.Delivered,
                    isMine = false,
                    deliveryAttempts = 1
                )
            )
        }
    }

    override fun retry(messageId: String) {
        scope.launch {
            val message = chatRepository.messages.value.firstOrNull { it.id == messageId } ?: return@launch
            chatRepository.replace(message.copy(status = ChatMessageStatus.Sending))
            delay(150)
            chatRepository.replace(message.copy(status = ChatMessageStatus.Delivered, deliveryAttempts = message.deliveryAttempts + 1))
            loggerService.info("Placeholder retry completed for $messageId.")
        }
    }

    override fun clearHistory() {
        scope.launch {
            chatRepository.replaceAll(emptyList())
            loggerService.info("Placeholder Android chat history cleared.")
        }
    }
}
