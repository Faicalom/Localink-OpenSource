package com.localbridge.android.core.protocol

object ProtocolConstants {
    const val version = "1.1"

    const val multipartMetadataPartName = "metadata"
    const val multipartBinaryPartName = "chunk"

    const val sessionStateIdle = "idle"
    const val sessionStateDiscovering = "discovering"
    const val sessionStateConnecting = "connecting"
    const val sessionStateWaitingForPairing = "waiting_for_pairing"
    const val sessionStatePaired = "paired"
    const val sessionStateConnected = "connected"
    const val sessionStateTransferInProgress = "transfer_in_progress"
    const val sessionStateDisconnected = "disconnected"
    const val sessionStateFailed = "failed"

    const val deliveryStatusSending = "sending"
    const val deliveryStatusSent = "sent"
    const val deliveryStatusDelivered = "delivered"
    const val deliveryStatusFailed = "failed"

    const val transferStateQueued = "queued"
    const val transferStatePreparing = "preparing"
    const val transferStateSending = "sending"
    const val transferStateReceiving = "receiving"
    const val transferStatePaused = "paused"
    const val transferStateCompleted = "completed"
    const val transferStateFailed = "failed"
    const val transferStateCanceled = "canceled"
}

object ProtocolPacketTypes {
    const val discoveryProbe = "discovery.probe"
    const val discoveryReply = "discovery.reply"
    const val discoveryAnnouncement = "discovery.announcement"

    const val connectionStatus = "connection.status"
    const val connectionHandshakeRequest = "connection.handshake.request"
    const val connectionHandshakeResponse = "connection.handshake.response"
    const val connectionHeartbeatRequest = "connection.heartbeat.request"
    const val connectionHeartbeatResponse = "connection.heartbeat.response"
    const val connectionDisconnectRequest = "connection.disconnect.request"
    const val connectionDisconnectResponse = "connection.disconnect.response"

    const val chatTextMessage = "chat.text.message"
    const val chatDeliveryReceipt = "chat.text.receipt"

    const val transferPrepareRequest = "transfer.prepare.request"
    const val transferPrepareResponse = "transfer.prepare.response"
    const val transferChunkRequest = "transfer.chunk.request"
    const val transferChunkResponse = "transfer.chunk.response"
    const val transferCompleteRequest = "transfer.complete.request"
    const val transferCompleteResponse = "transfer.complete.response"
    const val transferCancelRequest = "transfer.cancel.request"
    const val transferCancelResponse = "transfer.cancel.response"
}

object ProtocolErrorCodes {
    const val invalidRequest = "invalid_request"
    const val protocolMismatch = "protocol_mismatch"
    const val sessionNotFound = "session_not_found"
    const val selfConnectionNotAllowed = "self_connection_not_allowed"
    const val pairingTokenRequired = "pairing_token_required"
    const val invalidPairingToken = "invalid_pairing_token"
    const val notConnected = "not_connected"
    const val emptyMessage = "empty_message"
    const val wrongReceiver = "wrong_receiver"
    const val transferServiceUnavailable = "transfer_service_unavailable"
    const val invalidTransferPrepare = "invalid_transfer_prepare"
    const val invalidTransferChunk = "invalid_transfer_chunk"
    const val invalidTransferComplete = "invalid_transfer_complete"
    const val invalidTransferCancel = "invalid_transfer_cancel"
}
