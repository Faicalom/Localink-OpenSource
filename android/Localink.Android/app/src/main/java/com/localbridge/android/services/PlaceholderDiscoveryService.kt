package com.localbridge.android.services

import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.models.DiscoveryStateModel
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.repositories.DeviceRepository
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaceholderDiscoveryService(
    private val deviceRepository: DeviceRepository,
    private val loggerService: LoggerService
) : DiscoveryService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _discoveryState = MutableStateFlow(DiscoveryStateModel())

    override val devices = deviceRepository.devices
    override val discoveryState = _discoveryState.asStateFlow()

    override fun start() {
        _discoveryState.value = DiscoveryStateModel(
            isRunning = true,
            isScanning = true,
            statusText = "Placeholder discovery is scanning for test peers."
        )
        refresh()
    }

    override fun refresh() {
        scope.launch {
            val now = Instant.now().toString()
            deviceRepository.replaceAll(
                listOf(
                    DevicePeer(
                        id = "windows-reference-desktop",
                        displayName = "LocalBridge Desktop",
                        platform = AppConstants.desktopPlatformName,
                        ipAddress = "192.168.137.1",
                        port = AppConstants.defaultApiPort,
                        appVersion = "1.0.0",
                        supportedModes = listOf(AppConstants.localWifiMode),
                        isTrusted = false,
                        isOnline = true,
                        pairingRequired = true,
                        lastSeenAtUtc = now
                    ),
                    DevicePeer(
                        id = "windows-lab-node",
                        displayName = "QA Transfer Node",
                        platform = AppConstants.desktopPlatformName,
                        ipAddress = "192.168.0.12",
                        port = AppConstants.defaultApiPort,
                        appVersion = "1.0.0",
                        supportedModes = listOf(AppConstants.localWifiMode, AppConstants.bluetoothMode),
                        isTrusted = true,
                        isOnline = true,
                        pairingRequired = false,
                        lastSeenAtUtc = now
                    )
                )
            )
            _discoveryState.value = DiscoveryStateModel(
                isRunning = true,
                isScanning = false,
                statusText = "Placeholder peers loaded for Android UI preview.",
                lastScanAtUtc = now
            )
            loggerService.info("Device discovery refreshed with placeholder peers.")
        }
    }

    override fun stop() {
        _discoveryState.value = DiscoveryStateModel(
            isRunning = false,
            isScanning = false,
            statusText = "Placeholder discovery stopped."
        )
    }
}
