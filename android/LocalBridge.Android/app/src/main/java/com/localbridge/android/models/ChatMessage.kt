package com.localbridge.android.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String,
    val senderId: String,
    val receiverId: String,
    val senderName: String,
    val text: String,
    val timestampUtc: String,
    val status: ChatMessageStatus,
    val isMine: Boolean,
    val deliveryAttempts: Int = 0,
    val lastError: String = ""
)
