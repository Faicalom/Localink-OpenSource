package com.localbridge.android.models

import kotlinx.serialization.Serializable

@Serializable
enum class ChatMessageStatus {
    Sending,
    Sent,
    Delivered,
    Failed
}
