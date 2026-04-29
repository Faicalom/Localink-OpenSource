package com.localbridge.android.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ProtocolJson {
    val format = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

inline fun <reified T> decodeEnvelope(json: String): ProtocolEnvelope<T> {
    return ProtocolJson.format.decodeFromString(json)
}

inline fun <reified T> encodeEnvelope(envelope: ProtocolEnvelope<T>): String {
    return ProtocolJson.format.encodeToString(envelope)
}

@Serializable
data class ProtocolMetadata(
    val version: String,
    val packetType: String,
    val messageId: String,
    val sentAtUtc: String,
    val sessionId: String? = null,
    val senderDeviceId: String? = null,
    val receiverDeviceId: String? = null,
    val correlationId: String? = null
)

@Serializable
data class ProtocolError(
    val code: String,
    val message: String,
    val isRetryable: Boolean = false,
    val details: String? = null
)

@Serializable
data class ProtocolEnvelope<T>(
    val meta: ProtocolMetadata,
    val payload: T? = null,
    val error: ProtocolError? = null
)

@Serializable
data class StatusResponseDto(
    val serverDeviceId: String,
    val serverName: String,
    val pairingCode: String,
    val apiPort: Int,
    val discoveryPort: Int,
    val localAddresses: List<String>
)

@Serializable
data class DiscoveryPacket(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val localIp: String,
    val apiPort: Int,
    val appVersion: String,
    val supportedModes: List<String>,
    val pairingRequired: Boolean,
    val sentAtUtc: String
)

@Serializable
data class ConnectionHandshakeRequestDto(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
    val pairingToken: String,
    val supportedModes: List<String>
)

@Serializable
data class ConnectionHandshakeResponseDto(
    val accepted: Boolean,
    val sessionState: String,
    val sessionId: String? = null,
    val failureReason: String? = null,
    val serverDeviceId: String,
    val serverDeviceName: String,
    val serverPlatform: String,
    val serverAppVersion: String,
    val supportedModes: List<String>,
    val issuedAtUtc: String
)

@Serializable
data class ConnectionHeartbeatRequestDto(
    val sessionId: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String
)

@Serializable
data class ConnectionHeartbeatResponseDto(
    val alive: Boolean,
    val sessionState: String,
    val failureReason: String? = null,
    val serverDeviceId: String,
    val serverDeviceName: String,
    val serverPlatform: String,
    val serverAppVersion: String,
    val receivedAtUtc: String
)

@Serializable
data class ConnectionDisconnectRequestDto(
    val sessionId: String,
    val deviceId: String,
    val sentAtUtc: String
)

@Serializable
data class ConnectionDisconnectResponseDto(
    val acknowledged: Boolean,
    val sessionId: String,
    val receivedAtUtc: String
)

@Serializable
data class TextChatPacketDto(
    val id: String,
    val sessionId: String,
    val senderId: String,
    val senderName: String,
    val receiverId: String,
    val text: String,
    val timestampUtc: String
)

@Serializable
data class TextChatDeliveryReceiptDto(
    val accepted: Boolean,
    val messageId: String,
    val status: String,
    val failureReason: String? = null,
    val receiverDeviceId: String,
    val receiverDeviceName: String,
    val receivedAtUtc: String
)

@Serializable
data class FileTransferPrepareRequestDto(
    val transferId: String,
    val sessionId: String,
    val senderId: String,
    val senderName: String,
    val receiverId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val kind: String,
    val fileCreatedAtUtc: String,
    val chunkSize: Int,
    val totalChunks: Int,
    val requestedAtUtc: String
)

@Serializable
data class FileTransferPrepareResponseDto(
    val accepted: Boolean,
    val transferId: String,
    val status: String,
    val failureReason: String? = null,
    val nextExpectedChunkIndex: Int,
    val receivedBytes: Long,
    val receiverDeviceId: String,
    val receiverDeviceName: String,
    val suggestedFilePath: String? = null,
    val respondedAtUtc: String
)

@Serializable
data class FileTransferChunkDescriptorDto(
    val transferId: String,
    val sessionId: String,
    val senderId: String,
    val chunkIndex: Int,
    val chunkOffset: Long,
    val chunkLength: Int
)

@Serializable
data class FileTransferChunkResponseDto(
    val accepted: Boolean,
    val transferId: String,
    val chunkIndex: Int,
    val status: String,
    val failureReason: String? = null,
    val nextExpectedChunkIndex: Int,
    val receivedBytes: Long,
    val respondedAtUtc: String
)

@Serializable
data class FileTransferCompleteRequestDto(
    val transferId: String,
    val sessionId: String,
    val senderId: String,
    val totalChunks: Int,
    val totalBytes: Long,
    val sentAtUtc: String
)

@Serializable
data class FileTransferCompleteResponseDto(
    val accepted: Boolean,
    val transferId: String,
    val status: String,
    val failureReason: String? = null,
    val savedFilePath: String? = null,
    val completedAtUtc: String
)

@Serializable
data class FileTransferCancelRequestDto(
    val transferId: String,
    val sessionId: String,
    val senderId: String,
    val reason: String,
    val sentAtUtc: String
)

@Serializable
data class FileTransferCancelResponseDto(
    val accepted: Boolean,
    val transferId: String,
    val status: String,
    val failureReason: String? = null,
    val canceledAtUtc: String
)
