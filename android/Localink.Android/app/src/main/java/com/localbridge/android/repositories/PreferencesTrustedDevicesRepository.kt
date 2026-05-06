package com.localbridge.android.repositories

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.localbridge.android.core.AppConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.trustedDevicesDataStore by preferencesDataStore(name = AppConstants.trustedDevicesStoreName)

class PreferencesTrustedDevicesRepository(
    private val context: Context
) : TrustedDevicesRepository {
    private val trustedIdsKey = stringSetPreferencesKey("trusted_device_ids")

    override val trustedDeviceIds: Flow<Set<String>> = context.trustedDevicesDataStore.data.map { preferences ->
        preferences[trustedIdsKey].orEmpty()
    }

    override suspend fun save(deviceIds: Set<String>) {
        context.trustedDevicesDataStore.edit { preferences ->
            preferences[trustedIdsKey] = deviceIds
        }
    }
}
