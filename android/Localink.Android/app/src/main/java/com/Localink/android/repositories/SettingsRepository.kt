package com.localink.android.repositories

import com.localink.android.models.AppConnectionMode
import com.localink.android.models.AppLanguage
import com.localink.android.models.LocalinkSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<LocalinkSettings>

    suspend fun updatePreferredMode(mode: AppConnectionMode)
    suspend fun updateReceiveFolderLabel(label: String)
    suspend fun updateReceiveTree(uri: String, displayName: String)
    suspend fun clearReceiveTree()
    suspend fun updateDarkThemeEnabled(enabled: Boolean)
    suspend fun updateDeviceAlias(alias: String)
    suspend fun updateLanguage(language: AppLanguage)
}
