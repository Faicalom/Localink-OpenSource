package com.localink.android.repositories

import com.localink.android.models.LocalDeviceProfile

interface LocalDeviceProfileRepository {
    suspend fun getOrCreate(): LocalDeviceProfile
}
