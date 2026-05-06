package com.localbridge.android.repositories

import com.localbridge.android.models.LocalDeviceProfile

interface LocalDeviceProfileRepository {
    suspend fun getOrCreate(): LocalDeviceProfile
}
