package com.localink.android.core.di

import android.content.Context
import com.localink.android.core.logging.FileLoggerService
import com.localink.android.core.logging.LoggerService
import com.localink.android.core.network.local.LocalHttpHostService
import com.localink.android.core.permissions.PermissionsController
import com.localink.android.core.storage.StorageDirectories
import com.localink.android.repositories.ChatRepository
import com.localink.android.repositories.DeviceRepository
import com.localink.android.repositories.FileChatRepository
import com.localink.android.repositories.FileLocalDeviceProfileRepository
import com.localink.android.repositories.FileTransferRepository
import com.localink.android.repositories.InMemoryDeviceRepository
import com.localink.android.repositories.LocalDeviceProfileRepository
import com.localink.android.repositories.PreferencesSettingsRepository
import com.localink.android.repositories.PreferencesTrustedDevicesRepository
import com.localink.android.repositories.SettingsRepository
import com.localink.android.repositories.TransferRepository
import com.localink.android.services.ChatService
import com.localink.android.services.ConnectionService
import com.localink.android.services.DiscoveryService
import com.localink.android.services.FileTransferService
import com.localink.android.services.BluetoothConnectionService
import com.localink.android.services.BluetoothDiscoveryService
import com.localink.android.services.BridgeChatService
import com.localink.android.services.BridgeConnectionService
import com.localink.android.services.BridgeDiscoveryService
import com.localink.android.services.BridgeFileTransferService
import com.localink.android.services.LanChatService
import com.localink.android.services.LanConnectionService
import com.localink.android.services.LanDiscoveryService
import com.localink.android.services.LanFileTransferService
import com.localink.android.services.PersistentTrustedDevicesService
import com.localink.android.services.TrustedDevicesService

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val storageDirectories = StorageDirectories(appContext)
    val loggerService: LoggerService = FileLoggerService(storageDirectories)
    val localHttpHostService = LocalHttpHostService(loggerService)
    val permissionsController = PermissionsController()
    val settingsRepository: SettingsRepository = PreferencesSettingsRepository(appContext, storageDirectories, loggerService)
    val trustedDevicesRepository = PreferencesTrustedDevicesRepository(appContext)
    val deviceRepository: DeviceRepository = InMemoryDeviceRepository()
    val chatRepository: ChatRepository = FileChatRepository(storageDirectories, loggerService)
    val transferRepository: TransferRepository = FileTransferRepository(storageDirectories, loggerService)
    val localDeviceProfileRepository: LocalDeviceProfileRepository = FileLocalDeviceProfileRepository(
        storageDirectories = storageDirectories,
        settingsRepository = settingsRepository,
        loggerService = loggerService
    )
    val trustedDevicesService: TrustedDevicesService = PersistentTrustedDevicesService(
        repository = trustedDevicesRepository,
        loggerService = loggerService
    )
    private val lanDiscoveryService = LanDiscoveryService(
        context = appContext,
        deviceRepository = deviceRepository,
        trustedDevicesService = trustedDevicesService,
        localDeviceProfileRepository = localDeviceProfileRepository,
        loggerService = loggerService
    )
    private val bluetoothDiscoveryService = BluetoothDiscoveryService(
        context = appContext,
        deviceRepository = deviceRepository,
        trustedDevicesService = trustedDevicesService,
        loggerService = loggerService
    )
    private val lanConnectionService = LanConnectionService(
        deviceRepository = deviceRepository,
        trustedDevicesService = trustedDevicesService,
        localDeviceProfileRepository = localDeviceProfileRepository,
        localHttpHostService = localHttpHostService,
        loggerService = loggerService
    )
    private val bluetoothConnectionService = BluetoothConnectionService(
        context = appContext,
        deviceRepository = deviceRepository,
        trustedDevicesService = trustedDevicesService,
        localDeviceProfileRepository = localDeviceProfileRepository,
        loggerService = loggerService
    )
    val discoveryService: DiscoveryService = BridgeDiscoveryService(
        deviceRepository = deviceRepository,
        lanDiscoveryService = lanDiscoveryService,
        bluetoothDiscoveryService = bluetoothDiscoveryService
    )
    val connectionService: ConnectionService = BridgeConnectionService(
        settingsRepository = settingsRepository,
        lanConnectionService = lanConnectionService,
        bluetoothConnectionService = bluetoothConnectionService
    )
    private val lanChatService = LanChatService(
        chatRepository = chatRepository,
        connectionService = connectionService,
        localDeviceProfileRepository = localDeviceProfileRepository,
        localHttpHostService = localHttpHostService,
        loggerService = loggerService
    )
    val chatService: ChatService = BridgeChatService(
        chatRepository = chatRepository,
        lanChatService = lanChatService,
        bluetoothConnectionService = bluetoothConnectionService,
        bridgeConnectionService = connectionService as BridgeConnectionService,
        localDeviceProfileRepository = localDeviceProfileRepository,
        loggerService = loggerService
    )
    private val lanFileTransferService = LanFileTransferService(
        context = appContext,
        storageDirectories = storageDirectories,
        transferRepository = transferRepository,
        connectionService = connectionService,
        bluetoothConnectionService = bluetoothConnectionService,
        localDeviceProfileRepository = localDeviceProfileRepository,
        settingsRepository = settingsRepository,
        localHttpHostService = localHttpHostService,
        loggerService = loggerService
    )
    val fileTransferService: FileTransferService = BridgeFileTransferService(
        lanFileTransferService = lanFileTransferService,
        bluetoothConnectionService = bluetoothConnectionService,
        connectionService = connectionService,
        loggerService = loggerService
    )

    fun start() {
        storageDirectories.ensureAll()
        loggerService.launch()
        localHttpHostService.start()
        bluetoothConnectionService.registerFileTransferHandler(lanFileTransferService)
        connectionService.start()
        discoveryService.start()
        chatService.start()
        fileTransferService.start()
    }

    fun refreshRuntimeState() {
        connectionService.start()
        discoveryService.refresh()
    }
}
