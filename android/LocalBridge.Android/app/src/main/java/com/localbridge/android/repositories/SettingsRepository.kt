package com.localbridge.android.repositories

import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.AppLanguage
import com.localbridge.android.models.LocalBridgeSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<LocalBridgeSettings>

    suspend fun updatePreferredMode(mode: AppConnectionMode)
    suspend fun updateReceiveFolderLabel(label: String)
    suspend fun updateReceiveTree(uri: String, displayName: String)
    suspend fun clearReceiveTree()
    suspend fun updateDarkThemeEnabled(enabled: Boolean)
    suspend fun updateDeviceAlias(alias: String)
    suspend fun updateLanguage(language: AppLanguage)
}
