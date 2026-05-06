package com.localink.android.models

import kotlinx.serialization.Serializable

@Serializable
enum class TransferDirection {
    Outgoing,
    Incoming
}
