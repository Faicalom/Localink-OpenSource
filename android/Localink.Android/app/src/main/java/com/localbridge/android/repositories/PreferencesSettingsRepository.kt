package com.localbridge.android.repositories

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.core.storage.StorageDirectories
import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.AppLanguage
import com.localbridge.android.models.LocalBridgeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = AppConstants.settingsStoreName)

class PreferencesSettingsRepository(
    private val context: Context,
    private val storageDirectories: StorageDirectories,
    private val loggerService: LoggerService
) : SettingsRepository {
    private val preferredModeKey = stringPreferencesKey("preferred_mode")
    private val receiveFolderLabelKey = stringPreferencesKey("receive_folder_label")
    private val receiveTreeUriKey = stringPreferencesKey("receive_tree_uri")
    private val receiveTreeDisplayNameKey = stringPreferencesKey("receive_tree_display_name")
    private val darkThemeEnabledKey = booleanPreferencesKey("dark_theme_enabled")
    private val deviceAliasKey = stringPreferencesKey("device_alias")
    private val languageKey = stringPreferencesKey("language")

    override val settings: Flow<LocalBridgeSettings> = context.dataStore.data.map { preferences ->
        preferences.toSettings()
    }

    override suspend fun updatePreferredMode(mode: AppConnectionMode) {
        context.dataStore.edit { preferences ->
            preferences[preferredModeKey] = mode.wireValue
        }
        loggerService.info("Preferred mode updated to ${mode.wireValue}.")
    }

    override suspend fun updateReceiveFolderLabel(label: String) {
        val normalized = normalizeReceiveFolderName(label)
        context.dataStore.edit { preferences ->
            preferences[receiveFolderLabelKey] = normalized
        }
        loggerService.info("[STORAGE] Receive fallback folder updated to ${storageDirectories.resolveReceivedDirectory(normalized).absolutePath}.")
    }

    override suspend fun updateReceiveTree(uri: String, displayName: String) {
        context.dataStore.edit { preferences ->
            preferences[receiveTreeUriKey] = uri
            preferences[receiveTreeDisplayNameKey] = displayName
        }
        loggerService.info("[STORAGE] External SAF receive folder updated to $displayName.")
    }

    override suspend fun clearReceiveTree() {
        context.dataStore.edit { preferences ->
            preferences.remove(receiveTreeUriKey)
            preferences.remove(receiveTreeDisplayNameKey)
        }
        loggerService.info("[STORAGE] External SAF receive folder cleared; app-private fallback is active again.")
    }

    override suspend fun updateDarkThemeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[darkThemeEnabledKey] = enabled
        }
        loggerService.info("Theme preference updated.")
    }

    override suspend fun updateDeviceAlias(alias: String) {
        context.dataStore.edit { preferences ->
            preferences[deviceAliasKey] = alias
        }
        loggerService.info("Device alias updated to $alias.")
    }

    override suspend fun updateLanguage(language: AppLanguage) {
        context.dataStore.edit { preferences ->
            preferences[languageKey] = language.wireValue
        }
        loggerService.info("App language updated to ${language.wireValue}.")
    }

    private fun Preferences.toSettings(): LocalBridgeSettings {
        val folderName = normalizeReceiveFolderName(this[receiveFolderLabelKey])
        return LocalBridgeSettings(
            preferredMode = AppConnectionMode.fromWireValue(this[preferredModeKey]),
            receiveFolderName = folderName,
            receiveTreeUri = this[receiveTreeUriKey],
            receiveTreeDisplayName = this[receiveTreeDisplayNameKey],
            deviceAlias = this[deviceAliasKey] ?: "Android Phone",
            darkThemeEnabled = this[darkThemeEnabledKey] ?: true,
            language = AppLanguage.fromWireValue(this[languageKey])
        )
    }

    private fun normalizeReceiveFolderName(value: String?): String {
        val raw = value
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?: AppConstants.defaultReceiveFolderName

        return StorageDirectories.sanitizeFolderName(raw)
    }
}
