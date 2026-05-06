package com.localbridge.android.models

import kotlinx.serialization.Serializable

@Serializable
data class LocalDeviceProfile(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
    val supportedModes: List<String>
)
