package com.localbridge.android.models

data class DevicePeer(
    val id: String,
    val displayName: String,
    val platform: String,
    val ipAddress: String,
    val port: Int,
    val bluetoothAddress: String = "",
    val appVersion: String,
    val supportedModes: List<String>,
    val transportMode: AppConnectionMode = AppConnectionMode.LocalWifiLan,
    val isTrusted: Boolean = false,
    val isOnline: Boolean = true,
    val pairingRequired: Boolean = true,
    val lastSeenAtUtc: String
)
