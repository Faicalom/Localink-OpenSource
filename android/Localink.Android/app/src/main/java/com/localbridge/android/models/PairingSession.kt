package com.localbridge.android.models

data class PairingSession(
    val peerId: String,
    val peerName: String,
    val confirmationCode: String,
    val requestedAtUtc: String,
    val isApproved: Boolean
)
