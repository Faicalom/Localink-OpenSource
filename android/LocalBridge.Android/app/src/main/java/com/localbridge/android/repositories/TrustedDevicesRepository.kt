package com.localbridge.android.repositories

import kotlinx.coroutines.flow.Flow

interface TrustedDevicesRepository {
    val trustedDeviceIds: Flow<Set<String>>

    suspend fun save(deviceIds: Set<String>)
}
