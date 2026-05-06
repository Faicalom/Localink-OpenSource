package com.localbridge.android.services

import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.core.network.ProtocolHttpClient
import com.localbridge.android.core.network.local.LocalHttpHostService
import com.localbridge.android.core.network.local.LocalHttpRequest
import com.localbridge.android.core.network.local.LocalHttpResponse
import com.localbridge.android.core.protocol.ProtocolConstants
import com.localbridge.android.core.protocol.ProtocolEnvelope
import com.localbridge.android.core.protocol.ProtocolEnvelopeFactory
import com.localbridge.android.core.protocol.ProtocolEnvelopeValidator
import com.localbridge.android.core.protocol.ProtocolError
import com.localbridge.android.core.protocol.ProtocolErrorCodes
import com.localbridge.android.core.protocol.ProtocolMetadata
import com.localbridge.android.core.protocol.ProtocolPacketTypes
import com.localbridge.android.core.protocol.ProtocolJson
import com.localbridge.android.core.protocol.TextChatDeliveryReceiptDto
import com.localbridge.android.core.protocol.TextChatPacketDto
import com.localbridge.android.models.ChatMessage
import com.localbridge.android.models.ChatMessageStatus
import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.ConnectionLifecycleState
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.models.LocalDeviceProfile
import com.localbridge.android.repositories.ChatRepository
import com.localbridge.android.repositories.LocalDeviceProfileRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class LanChatService(
    private val chatRepository: ChatRepository,
    private val connectionService: ConnectionService,
    private val localDeviceProfileRepository: LocalDeviceProfileRepository,
    private val localHttpHostService: LocalHttpHostService,
    private val loggerService: LoggerService
) : ChatService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deliveryMutex = Mutex()
    private val inFlightMessageIds = mutableSetOf<String>()

    private var retryJob: Job? = null
    private var isStarted = false

    override val messages: StateFlow<List<ChatMessage>> = chatRepository.messages

    override fun start() {
        if (isStarted) {
            return
        }

        isStarted = true
        scope.launch {
            chatRepository.load()
            localHttpHostService.register("POST", AppConstants.chatMessagesPath, ::handleIncomingChatRequest)
            startRetryLoop()
            loggerService.info("Android LAN chat service started with ${chatRepository.messages.value.size} stored message(s).")
        }
    }

    override fun send(text: String) {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return
        }

        scope.launch {
            val localDevice = localDeviceProfileRepository.getOrCreate()
            val peer = connectionService.activePeer.value ?: connectionService.connectionState.value.connectedPeer
            val message = ChatMessage(
                id = UUID.randomUUID().toString().replace("-", ""),
                senderId = localDevice.deviceId,
                receiverId = peer?.id.orEmpty(),
                senderName = localDevice.deviceName,
                text = normalizedText,
                timestampUtc = Instant.now().toString(),
                status = ChatMessageStatus.Sent,
                isMine = true
            )

            chatRepository.append(message)
            deliverMessage(message.id, forceManualRetry = false)
        }
    }

    override fun retry(messageId: String) {
        scope.launch {
            deliverMessage(messageId, forceManualRetry = true)
        }
    }

    override fun clearHistory() {
        scope.launch {
            val removedCount = messages.value.size
            chatRepository.replaceAll(emptyList())
            loggerService.info("[HISTORY] Android chat history cleared ($removedCount message(s)).")
        }
    }

    private fun startRetryLoop() {
        if (retryJob?.isActive == true) {
            return
        }

        retryJob = scope.launch {
            while (isActive) {
                delay(AppConstants.chatRetryIntervalSeconds * 1_000L)
                retryPendingMessages()
            }
        }
    }

    private suspend fun retryPendingMessages() {
        val activePeer = connectionService.activePeer.value
        if (activePeer?.transportMode == AppConnectionMode.BluetoothFallback) {
            return
        }

        messages.value
            .filter { it.isMine }
            .filter { it.status == ChatMessageStatus.Sent || it.status == ChatMessageStatus.Failed }
            .filter { it.deliveryAttempts < AppConstants.chatAutoRetryLimit }
            .sortedBy { it.timestampUtc }
            .forEach { message ->
                deliverMessage(message.id, forceManualRetry = false)
            }
    }

    private suspend fun deliverMessage(messageId: String, forceManualRetry: Boolean) {
        val isAlreadySending = deliveryMutex.withLock {
            if (inFlightMessageIds.contains(messageId)) {
                true
            } else {
                inFlightMessageIds.add(messageId)
                false
            }
        }
        if (isAlreadySending) {
            return
        }

        try {
            val original = messages.value.firstOrNull { it.id == messageId } ?: return
            val connectionState = connectionService.connectionState.value
            val peer = connectionService.activePeer.value ?: connectionState.connectedPeer
            val sessionId = connectionState.sessionId

            if (peer?.transportMode == AppConnectionMode.BluetoothFallback) {
                return
            }

            var message = original
            if (message.receiverId.isBlank() && peer != null) {
                message = message.copy(receiverId = peer.id)
                chatRepository.replace(message)
            }

            if (peer == null || sessionId.isNullOrBlank() || !connectionState.isConnected) {
                chatRepository.replace(
                    message.copy(
                        status = ChatMessageStatus.Failed,
                        lastError = ProtocolErrorCodes.notConnected
                    )
                )
                loggerService.warning("Chat delivery paused because there is no active Windows peer.")
                return
            }

            if (message.receiverId.isNotBlank() && !message.receiverId.equals(peer.id, ignoreCase = true)) {
                chatRepository.replace(
                    message.copy(
                        status = ChatMessageStatus.Failed,
                        lastError = "different_active_peer"
                    )
                )
                loggerService.warning("Chat retry skipped for ${message.id} because a different peer is active.")
                return
            }

            if (!forceManualRetry && message.deliveryAttempts >= AppConstants.chatAutoRetryLimit) {
                return
            }

            chatRepository.replace(message.copy(status = ChatMessageStatus.Sending))
            val localDevice = localDeviceProfileRepository.getOrCreate()
            val packet = TextChatPacketDto(
                id = message.id,
                sessionId = sessionId,
                senderId = localDevice.deviceId,
                senderName = localDevice.deviceName,
                receiverId = peer.id,
                text = message.text,
                timestampUtc = message.timestampUtc
            )

            val receipt = try {
                val envelope = ProtocolEnvelopeFactory.create(
                    packetType = ProtocolPacketTypes.chatTextMessage,
                    payload = packet,
                    senderDeviceId = localDevice.deviceId,
                    receiverDeviceId = peer.id,
                    sessionId = sessionId,
                    messageId = message.id,
                    sentAtUtc = message.timestampUtc
                )
                val response = ProtocolHttpClient.postEnvelope<TextChatPacketDto, TextChatDeliveryReceiptDto>(
                    url = buildPeerUrl(peer, AppConstants.chatMessagesPath),
                    envelope = envelope,
                    timeoutMillis = AppConstants.connectionRequestTimeoutMillis
                )
                val validation = ProtocolEnvelopeValidator.validate(
                    response.envelope,
                    expectedPacketTypes = *arrayOf(ProtocolPacketTypes.chatDeliveryReceipt)
                )
                if (!validation.isValid || response.statusCode !in 200..299 || response.envelope?.payload == null) {
                    TextChatDeliveryReceiptDto(
                        accepted = false,
                        messageId = message.id,
                        status = ProtocolConstants.deliveryStatusFailed,
                        failureReason = response.envelope?.error?.code
                            ?: validation.errorCode
                            ?: "chat_http_${response.statusCode}",
                        receiverDeviceId = peer.id,
                        receiverDeviceName = peer.displayName,
                        receivedAtUtc = Instant.now().toString()
                    )
                } else {
                    response.envelope.payload
                }
            } catch (exception: Exception) {
                TextChatDeliveryReceiptDto(
                    accepted = false,
                    messageId = message.id,
                    status = ProtocolConstants.deliveryStatusFailed,
                    failureReason = exception.message ?: "chat_transport_error",
                    receiverDeviceId = peer.id,
                    receiverDeviceName = peer.displayName,
                    receivedAtUtc = Instant.now().toString()
                )
            }

            val updated = messages.value.firstOrNull { it.id == message.id } ?: message
            val attemptCount = updated.deliveryAttempts + 1
            if (receipt.accepted && receipt.status == ProtocolConstants.deliveryStatusDelivered) {
                chatRepository.replace(
                    updated.copy(
                        status = ChatMessageStatus.Delivered,
                        deliveryAttempts = attemptCount,
                        lastError = ""
                    )
                )
                loggerService.info("Delivered Android chat message ${message.id} to ${peer.displayName}.")
            } else {
                chatRepository.replace(
                    updated.copy(
                        status = ChatMessageStatus.Failed,
                        deliveryAttempts = attemptCount,
                        lastError = receipt.failureReason ?: "delivery_failed"
                    )
                )
                loggerService.warning(
                    "Android chat delivery failed for ${message.id}: ${receipt.failureReason ?: "delivery_failed"}."
                )
            }
        } finally {
            deliveryMutex.withLock {
                inFlightMessageIds.remove(messageId)
            }
        }
    }

    private suspend fun handleIncomingChatRequest(request: LocalHttpRequest): LocalHttpResponse {
        if (!request.method.equals("POST", ignoreCase = true)) {
            return LocalHttpResponse.text(405, "Method Not Allowed", "Method not allowed.")
        }

        val localDevice = localDeviceProfileRepository.getOrCreate()
        val envelope = runCatching {
            ProtocolJson.format.decodeFromString(
                ProtocolEnvelope.serializer(TextChatPacketDto.serializer()),
                request.bodyText
            )
        }.getOrNull()
        val packet = envelope?.payload
        val correlationId = envelope?.meta?.messageId

        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            expectedPacketTypes = *arrayOf(ProtocolPacketTypes.chatTextMessage)
        )

        if (!validation.isValid) {
            loggerService.warning(
                "[CHAT][RX] Rejected incoming chat envelope: ${validation.errorCode ?: ProtocolErrorCodes.invalidRequest} " +
                    "(${validation.errorMessage ?: "unknown validation error"}), bodyBytes=${request.bodyBytes.size}."
            )
            return protocolErrorResponse(
                statusCode = if (validation.errorCode == ProtocolErrorCodes.protocolMismatch) 426 else 400,
                packetType = ProtocolPacketTypes.chatDeliveryReceipt,
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidRequest,
                errorMessage = validation.errorMessage ?: "Chat packet is malformed.",
                payload = createChatFailureReceipt(packet?.id.orEmpty(), validation.errorCode ?: ProtocolErrorCodes.invalidRequest, localDevice),
                localDevice = localDevice,
                receiverDeviceId = packet?.senderId,
                sessionId = packet?.sessionId,
                correlationId = correlationId
            )
        }

        val incomingPacket = envelope!!.payload!!
        if (incomingPacket.id.isBlank() ||
            incomingPacket.sessionId.isBlank() ||
            incomingPacket.senderId.isBlank() ||
            incomingPacket.text.isBlank()
        ) {
            loggerService.warning(
                "[CHAT][RX] Chat packet is missing required fields. " +
                    "id='${incomingPacket.id}', session='${incomingPacket.sessionId}', sender='${incomingPacket.senderId}', textLength=${incomingPacket.text.length}."
            )
            return protocolErrorResponse(
                statusCode = 400,
                packetType = ProtocolPacketTypes.chatDeliveryReceipt,
                errorCode = ProtocolErrorCodes.invalidRequest,
                errorMessage = "Chat packet is missing required fields.",
                payload = createChatFailureReceipt(incomingPacket.id, ProtocolErrorCodes.invalidRequest, localDevice),
                localDevice = localDevice,
                receiverDeviceId = incomingPacket.senderId.ifBlank { null },
                sessionId = incomingPacket.sessionId.ifBlank { null },
                correlationId = correlationId
            )
        }

        if (incomingPacket.receiverId.isNotBlank() &&
            !incomingPacket.receiverId.equals(localDevice.deviceId, ignoreCase = true)
        ) {
            loggerService.warning(
                "[CHAT][RX] Wrong receiver for incoming chat ${incomingPacket.id}. " +
                    "Expected ${localDevice.deviceId}, got ${incomingPacket.receiverId}."
            )
            return protocolErrorResponse(
                statusCode = 409,
                packetType = ProtocolPacketTypes.chatDeliveryReceipt,
                errorCode = ProtocolErrorCodes.wrongReceiver,
                errorMessage = "The receiver id does not match this host.",
                payload = createChatFailureReceipt(incomingPacket.id, ProtocolErrorCodes.wrongReceiver, localDevice),
                localDevice = localDevice,
                receiverDeviceId = incomingPacket.senderId,
                sessionId = incomingPacket.sessionId,
                correlationId = correlationId
            )
        }

        val connectionState = connectionService.connectionState.value
        val activePeer = connectionService.activePeer.value ?: connectionState.connectedPeer
        val sessionMatches = connectionState.lifecycleState == ConnectionLifecycleState.Connected &&
            connectionState.sessionId == incomingPacket.sessionId &&
            activePeer?.id.equals(incomingPacket.senderId, ignoreCase = true)

        if (!sessionMatches) {
            loggerService.warning(
                "[CHAT][RX] Session mismatch for chat ${incomingPacket.id}. " +
                    "expectedSession='${connectionState.sessionId}', incomingSession='${incomingPacket.sessionId}', " +
                    "activePeer='${activePeer?.id ?: "<none>"}', sender='${incomingPacket.senderId}'."
            )
            return protocolErrorResponse(
                statusCode = 404,
                packetType = ProtocolPacketTypes.chatDeliveryReceipt,
                errorCode = ProtocolErrorCodes.sessionNotFound,
                errorMessage = "The chat session is not active on this host.",
                payload = createChatFailureReceipt(incomingPacket.id, ProtocolErrorCodes.sessionNotFound, localDevice),
                localDevice = localDevice,
                receiverDeviceId = incomingPacket.senderId,
                sessionId = incomingPacket.sessionId,
                correlationId = correlationId
            )
        }

        if (messages.value.none { it.id == incomingPacket.id }) {
            chatRepository.append(
                ChatMessage(
                    id = incomingPacket.id,
                    senderId = incomingPacket.senderId,
                    receiverId = incomingPacket.receiverId,
                    senderName = incomingPacket.senderName,
                    text = incomingPacket.text,
                    timestampUtc = incomingPacket.timestampUtc,
                    status = ChatMessageStatus.Delivered,
                    isMine = false,
                    deliveryAttempts = 1
                )
            )
        }

        loggerService.info("Accepted Android chat message ${incomingPacket.id} from ${incomingPacket.senderName}.")
        val responseEnvelope = ProtocolEnvelopeFactory.create(
            packetType = ProtocolPacketTypes.chatDeliveryReceipt,
            payload = TextChatDeliveryReceiptDto(
                accepted = true,
                messageId = incomingPacket.id,
                status = ProtocolConstants.deliveryStatusDelivered,
                failureReason = null,
                receiverDeviceId = localDevice.deviceId,
                receiverDeviceName = localDevice.deviceName,
                receivedAtUtc = Instant.now().toString()
            ),
            senderDeviceId = localDevice.deviceId,
            receiverDeviceId = incomingPacket.senderId,
            sessionId = incomingPacket.sessionId,
            correlationId = correlationId
        )

        return LocalHttpResponse.json(
            statusCode = 200,
            reasonPhrase = "OK",
            body = ProtocolJson.format.encodeToString(
                ProtocolEnvelope.serializer(TextChatDeliveryReceiptDto.serializer()),
                responseEnvelope
            )
        )
    }

    private fun protocolErrorResponse(
        statusCode: Int,
        packetType: String,
        errorCode: String,
        errorMessage: String,
        payload: TextChatDeliveryReceiptDto,
        localDevice: LocalDeviceProfile,
        receiverDeviceId: String?,
        sessionId: String?,
        correlationId: String?
    ): LocalHttpResponse {
        val envelope = ProtocolEnvelope(
            meta = ProtocolMetadata(
                version = ProtocolConstants.version,
                packetType = packetType,
                messageId = UUID.randomUUID().toString().replace("-", ""),
                sentAtUtc = Instant.now().toString(),
                sessionId = sessionId,
                senderDeviceId = localDevice.deviceId,
                receiverDeviceId = receiverDeviceId,
                correlationId = correlationId
            ),
            payload = payload,
            error = ProtocolError(
                code = errorCode,
                message = errorMessage,
                isRetryable = false
            )
        )

        return LocalHttpResponse.json(
            statusCode = statusCode,
            reasonPhrase = httpReason(statusCode),
            body = ProtocolJson.format.encodeToString(
                ProtocolEnvelope.serializer(TextChatDeliveryReceiptDto.serializer()),
                envelope
            )
        )
    }

    private fun createChatFailureReceipt(
        messageId: String,
        failureReason: String,
        localDevice: LocalDeviceProfile
    ): TextChatDeliveryReceiptDto {
        return TextChatDeliveryReceiptDto(
            accepted = false,
            messageId = messageId,
            status = ProtocolConstants.deliveryStatusFailed,
            failureReason = failureReason,
            receiverDeviceId = localDevice.deviceId,
            receiverDeviceName = localDevice.deviceName,
            receivedAtUtc = Instant.now().toString()
        )
    }

    private fun buildPeerUrl(peer: DevicePeer, path: String): String {
        return "http://${peer.ipAddress}:${peer.port}$path"
    }

    private fun httpReason(statusCode: Int): String {
        return when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            426 -> "Upgrade Required"
            else -> "Error"
        }
    }
}
