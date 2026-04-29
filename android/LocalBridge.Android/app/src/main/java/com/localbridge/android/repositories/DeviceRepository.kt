package com.localbridge.android.repositories

import com.localbridge.android.models.DevicePeer
import kotlinx.coroutines.flow.StateFlow

interface DeviceRepository {
    val devices: StateFlow<List<DevicePeer>>

    suspend fun replaceAll(devices: List<DevicePeer>)
    suspend fun upsert(device: DevicePeer)
    suspend fun remove(deviceId: String)
    suspend fun updateTrust(deviceIds: Set<String>)
}
