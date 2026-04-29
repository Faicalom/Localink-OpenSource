package com.localbridge.android.models

import kotlinx.serialization.Serializable

@Serializable
enum class TransferState {
    Queued,
    Preparing,
    Sending,
    Receiving,
    Paused,
    Completed,
    Failed,
    Canceled
}
