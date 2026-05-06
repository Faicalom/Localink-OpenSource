package com.localbridge.android.services

import com.localbridge.android.models.DiscoveryStateModel
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.repositories.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BridgeDiscoveryService(
    private val deviceRepository: DeviceRepository,
    private val lanDiscoveryService: DiscoveryService,
    private val bluetoothDiscoveryService: BluetoothDiscoveryService
) : DiscoveryService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _discoveryState = MutableStateFlow(DiscoveryStateModel())
    private var started = false

    override val devices: StateFlow<List<DevicePeer>> = deviceRepository.devices
    override val discoveryState: StateFlow<DiscoveryStateModel> = _discoveryState.asStateFlow()

    override fun start() {
        if (!started) {
            started = true
            scope.launch {
                lanDiscoveryService.discoveryState.collectLatest { mergeStates(it, bluetoothDiscoveryService.discoveryState.value) }
            }
            scope.launch {
                bluetoothDiscoveryService.discoveryState.collectLatest { mergeStates(lanDiscoveryService.discoveryState.value, it) }
            }
        }

        lanDiscoveryService.start()
        bluetoothDiscoveryService.start()
    }

    override fun refresh() {
        lanDiscoveryService.refresh()
        bluetoothDiscoveryService.refresh()
    }

    override fun stop() {
        lanDiscoveryService.stop()
        bluetoothDiscoveryService.stop()
    }

    private fun mergeStates(lan: DiscoveryStateModel, bluetooth: DiscoveryStateModel) {
        val combinedStatus = buildString {
            append(lan.statusText)
            if (bluetooth.statusText.isNotBlank()) {
                append(" ")
                append(bluetooth.statusText)
            }
        }.trim()

        _discoveryState.value = DiscoveryStateModel(
            isRunning = lan.isRunning || bluetooth.discoveryStateIsRunning(),
            isScanning = lan.isScanning || bluetooth.isScanning,
            statusText = combinedStatus.ifBlank { "Discovery is idle." },
            lastScanAtUtc = bluetooth.lastScanAtUtc?.ifBlank { lan.lastScanAtUtc } ?: lan.lastScanAtUtc,
            lastError = bluetooth.lastError ?: lan.lastError
        )
    }

    private fun DiscoveryStateModel.discoveryStateIsRunning(): Boolean {
        return isRunning || statusText.contains("Bluetooth", ignoreCase = true)
    }
}
