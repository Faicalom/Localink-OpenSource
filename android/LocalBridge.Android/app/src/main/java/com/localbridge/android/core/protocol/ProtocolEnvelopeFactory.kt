package com.localbridge.android.core.protocol

import java.time.Instant
import java.util.UUID

object ProtocolEnvelopeFactory {
    fun <T> create(
        packetType: String,
        payload: T,
        senderDeviceId: String? = null,
        receiverDeviceId: String? = null,
        sessionId: String? = null,
        correlationId: String? = null,
        messageId: String? = null,
        sentAtUtc: String = Instant.now().toString()
    ): ProtocolEnvelope<T> {
        return ProtocolEnvelope(
            meta = ProtocolMetadata(
                version = ProtocolConstants.version,
                packetType = packetType,
                messageId = messageId ?: UUID.randomUUID().toString().replace("-", ""),
                sentAtUtc = sentAtUtc,
                sessionId = sessionId,
                senderDeviceId = senderDeviceId,
                receiverDeviceId = receiverDeviceId,
                correlationId = correlationId
            ),
            payload = payload,
            error = null
        )
    }
}
