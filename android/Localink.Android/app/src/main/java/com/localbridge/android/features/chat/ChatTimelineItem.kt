package com.localbridge.android.features.chat

import com.localbridge.android.models.ChatMessage
import com.localbridge.android.models.TransferItem

sealed interface ChatTimelineItem {
    val stableKey: String
    val sortTimestampUtc: String

    data class MessageEntry(val message: ChatMessage) : ChatTimelineItem {
        override val stableKey: String = "message:${message.id}"
        override val sortTimestampUtc: String = message.timestampUtc
    }

    data class TransferEntry(val transfer: TransferItem) : ChatTimelineItem {
        override val stableKey: String = "transfer:${transfer.id}"
        override val sortTimestampUtc: String = transfer.createdAtUtc
    }
}
