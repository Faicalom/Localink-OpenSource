package com.localbridge.android.repositories

import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.core.protocol.ProtocolJson
import com.localbridge.android.core.storage.StorageDirectories
import com.localbridge.android.models.LocalBridgeSettings
import com.localbridge.android.models.LocalDeviceProfile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class FileLocalDeviceProfileRepository(
    private val storageDirectories: StorageDirectories,
    private val settingsRepository: SettingsRepository,
    private val loggerService: LoggerService
) : LocalDeviceProfileRepository {
    private val fileLock = Mutex()
    private val filePath: File
        get() = File(storageDirectories.rootDirectory, AppConstants.localDeviceFileName)

    override suspend fun getOrCreate(): LocalDeviceProfile = fileLock.withLock {
        val settings = settingsRepository.settings.first()
        val existing = readFromDisk()

        if (existing == null) {
            val created = createProfile(settings)
            writeToDisk(created)
            loggerService.info("Created local Android device profile ${created.deviceId}.")
            return@withLock created
        }

        if (existing.deviceName != settings.deviceAlias) {
            val updated = existing.copy(deviceName = settings.deviceAlias)
            writeToDisk(updated)
            return@withLock updated
        }

        existing
    }

    private fun createProfile(settings: LocalBridgeSettings): LocalDeviceProfile {
        return LocalDeviceProfile(
            deviceId = "android-" + UUID.randomUUID().toString().replace("-", ""),
            deviceName = settings.deviceAlias,
            platform = AppConstants.androidPlatformName,
            appVersion = AppConstants.appVersion,
            supportedModes = listOf(AppConstants.localWifiMode, AppConstants.bluetoothMode)
        )
    }

    private fun readFromDisk(): LocalDeviceProfile? {
        val file = filePath
        if (!file.exists()) {
            return null
        }

        return runCatching {
            ProtocolJson.format.decodeFromString(LocalDeviceProfile.serializer(), file.readText())
        }.getOrNull()
    }

    private fun writeToDisk(profile: LocalDeviceProfile) {
        val file = filePath
        file.parentFile?.mkdirs()
        file.writeText(ProtocolJson.format.encodeToString(LocalDeviceProfile.serializer(), profile))
    }
}
