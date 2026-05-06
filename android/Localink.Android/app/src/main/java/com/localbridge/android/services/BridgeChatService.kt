package com.localbridge.android.services

import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.core.protocol.ProtocolConstants
import com.localbridge.android.core.protocol.TextChatPacketDto
import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.ChatMessage
import com.localbridge.android.models.ChatMessageStatus
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.repositories.ChatRepository
import com.localbridge.android.repositories.LocalDeviceProfileRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BridgeChatService(
    private val chatRepository: ChatRepository,
    private val lanChatService: LanChatService,
    private val bluetoothConnectionService: BluetoothConnectionService,
    private val bridgeConnectionService: BridgeConnectionService,
    private val localDeviceProfileRepository: LocalDeviceProfileRepository,
    private val loggerService: LoggerService
) : ChatService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private var started = false
    private var retryJob: Job? = null

    override val messages: StateFlow<List<ChatMessage>> = chatRepository.messages

    override fun start() {
        if (started) {
            return
        }

        started = true
        lanChatService.start()
        bluetoothConnectionService.start()

        scope.launch {
            bluetoothConnectionService.incomingMessages.collect { packet ->
                if (messages.value.none { it.id == packet.id }) {
                    chatRepository.append(
                        ChatMessage(
                            id = packet.id,
                            senderId = packet.senderId,
                            receiverId = packet.receiverId,
                            senderName = packet.senderName,
                            text = packet.text,
                            timestampUtc = packet.timestampUtc,
                            status = ChatMessageStatus.Delivered,
                            isMine = false,
                            deliveryAttempts = 1
                        )
                    )
                }
            }
        }

        retryJob = scope.launch {
            while (isActive) {
                delay(AppConstants.chatRetryIntervalSeconds * 1_000L)
                retryPendingBluetoothMessages()
            }
        }
    }

    override fun send(text: String) {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return
        }

        val peer = bridgeConnectionService.activePeer.value
        if (peer?.transportMode == AppConnectionMode.BluetoothFallback) {
            scope.launch {
                val localDevice = localDeviceProfileRepository.getOrCreate()
                val message = ChatMessage(
                    id = UUID.randomUUID().toString().replace("-", ""),
                    senderId = localDevice.deviceId,
                    receiverId = peer.id,
                    senderName = localDevice.deviceName,
                    text = normalizedText,
                    timestampUtc = Instant.now().toString(),
                    status = ChatMessageStatus.Sent,
                    isMine = true
                )
                chatRepository.append(message)
                deliverBluetoothMessage(message.id, forceManualRetry = false)
            }
        } else {
            lanChatService.send(normalizedText)
        }
    }

    override fun retry(messageId: String) {
        val peer = bridgeConnectionService.activePeer.value
        if (peer?.transportMode == AppConnectionMode.BluetoothFallback) {
            scope.launch { deliverBluetoothMessage(messageId, forceManualRetry = true) }
        } else {
            lanChatService.retry(messageId)
        }
    }

    override fun clearHistory() {
        scope.launch {
            val removedCount = messages.value.size
            chatRepository.replaceAll(emptyList())
            loggerService.info("[HISTORY] Android bridge chat history cleared ($removedCount message(s)).")
        }
    }

    private suspend fun retryPendingBluetoothMessages() {
        val peer = bridgeConnectionService.activePeer.value ?: return
        if (peer.transportMode != AppConnectionMode.BluetoothFallback) {
            return
        }

        messages.value
            .filter { it.isMine }
            .filter { it.status == ChatMessageStatus.Sent || it.status == ChatMessageStatus.Failed }
            .filter { it.deliveryAttempts < AppConstants.chatAutoRetryLimit }
            .sortedBy { it.timestampUtc }
            .forEach { message ->
                deliverBluetoothMessage(message.id, forceManualRetry = false)
            }
    }

    private suspend fun deliverBluetoothMessage(messageId: String, forceManualRetry: Boolean) {
        sendMutex.withLock {
            val original = messages.value.firstOrNull { it.id == messageId } ?: return
            val peer = bridgeConnectionService.activePeer.value
                ?: bridgeConnectionService.connectionState.value.connectedPeer

            if (peer == null || peer.transportMode != AppConnectionMode.BluetoothFallback) {
                chatRepository.replace(
                    original.copy(
                        status = ChatMessageStatus.Failed,
                        lastError = "bluetooth_not_connected"
                    )
                )
                return
            }

            if (!forceManualRetry && original.deliveryAttempts >= AppConstants.chatAutoRetryLimit) {
                return
            }

            chatRepository.replace(original.copy(status = ChatMessageStatus.Sending))
            val localDevice = localDeviceProfileRepository.getOrCreate()
            val receipt = bluetoothConnectionService.sendChatMessage(
                TextChatPacketDto(
                    id = original.id,
                    sessionId = bridgeConnectionService.connectionState.value.sessionId.orEmpty(),
                    senderId = localDevice.deviceId,
                    senderName = localDevice.deviceName,
                    receiverId = peer.id,
                    text = original.text,
                    timestampUtc = original.timestampUtc
                )
            )

            val attemptCount = original.deliveryAttempts + 1
            if (receipt.accepted && receipt.status == ProtocolConstants.deliveryStatusDelivered) {
                chatRepository.replace(
                    original.copy(
                        status = ChatMessageStatus.Delivered,
                        deliveryAttempts = attemptCount,
                        lastError = ""
                    )
                )
                loggerService.info("[BT-CHAT] Delivered Android Bluetooth chat message ${original.id} to ${peer.displayName}.")
            } else {
                chatRepository.replace(
                    original.copy(
                        status = ChatMessageStatus.Failed,
                        deliveryAttempts = attemptCount,
                        lastError = receipt.failureReason ?: "bluetooth_delivery_failed"
                    )
                )
                loggerService.warning("[BT-CHAT] Android Bluetooth chat delivery failed for ${original.id}: ${receipt.failureReason ?: "bluetooth_delivery_failed"}.")
            }
        }
    }
}
