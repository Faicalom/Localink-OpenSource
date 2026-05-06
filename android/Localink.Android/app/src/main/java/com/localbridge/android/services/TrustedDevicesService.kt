package com.localbridge.android.services

import kotlinx.coroutines.flow.StateFlow

interface TrustedDevicesService {
    val trustedDeviceIds: StateFlow<Set<String>>

    fun trust(deviceId: String)
    fun untrust(deviceId: String)
}
