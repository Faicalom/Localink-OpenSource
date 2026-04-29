package com.localbridge.android.core.di

import android.content.Context
import com.localbridge.android.core.logging.FileLoggerService
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.core.network.local.LocalHttpHostService
import com.localbridge.android.core.permissions.PermissionsController
import com.localbridge.android.core.storage.StorageDirectories
import com.localbridge.android.repositories.ChatRepository
import com.localbridge.android.repositories.DeviceRepository
import com.localbridge.android.repositories.FileChatRepository
import com.localbridge.android.repositories.FileLocalDeviceProfileRepository
import com.localbridge.android.repositories.FileTransferRepository
import com.localbridge.android.repositories.InMemoryDeviceRepository
import com.localbridge.android.repositories.LocalDeviceProfileRepository
import com.localbridge.android.repositories.PreferencesSettingsRepository
import com.localbridge.android.repositories.PreferencesTrustedDevicesRepository
import com.localbridge.android.repositories.SettingsRepository
import com.localbridge.android.repositories.TransferRepository
import com.localbridge.android.services.ChatService
import com.localbridge.android.services.ConnectionService
import com.localbridge.android.services.DiscoveryService
import com.localbridge.android.services.FileTransferService
import com.localbridge.android.services.BluetoothConnectionService
import com.localbridge.android.services.BluetoothDiscoveryService
import com.localbridge.android.services.BridgeChatService
import com.localbridge.android.services.BridgeConnectionService
import com.localbridge.android.services.BridgeDiscoveryService
import com.localbridge.android.services.BridgeFileTransferService
import com.localbridge.android.services.LanChatService
import com.localbridge.android.services.LanConnectionService
import com.localbridge.android.services.LanDiscoveryService
import com.localbridge.android.services.LanFileTransferService
import com.localbridge.android.services.PersistentTrustedDevicesService
import com.localbridge.android.services.TrustedDevicesService

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
