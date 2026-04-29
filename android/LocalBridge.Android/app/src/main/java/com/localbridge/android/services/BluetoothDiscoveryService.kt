package com.localbridge.android.services

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.models.DiscoveryStateModel
import com.localbridge.android.repositories.DeviceRepository
import com.localbridge.android.services.TrustedDevicesService
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BluetoothDiscoveryService(
    context: Context,
    private val deviceRepository: DeviceRepository,
    private val trustedDevicesService: TrustedDevicesService,
    private val loggerService: LoggerService
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val bluetoothAdapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _discoveryState = MutableStateFlow(
        DiscoveryStateModel(
            isRunning = false,
            isScanning = false,
            statusText = "Bluetooth discovery is idle."
        )
    )

    val discoveryState: StateFlow<DiscoveryStateModel> = _discoveryState.asStateFlow()

    private var refreshJob: Job? = null
    private var isStarted = false

    fun start() {
        scope.launch {
            lifecycleMutex.withLock {
                if (isStarted) {
                    return@withLock
                }

                isStarted = true
                _discoveryState.value = DiscoveryStateModel(
                    isRunning = true,
                    isScanning = false,
                    statusText = "Watching paired Bluetooth peers for Localink fallback."
                )
                refreshJob = scope.launch { refreshLoop() }
                loggerService.info("[BT-DISCOVERY] Android Bluetooth discovery started.")
                refreshInternal("initial scan")
            }
        }
    }

    fun refresh() {
        scope.launch {
            refreshInternal("manual refresh")
        }
    }

    fun stop() {
        scope.launch {
            lifecycleMutex.withLock {
                isStarted = false
                refreshJob?.cancel()
                refreshJob = null
                _discoveryState.value = DiscoveryStateModel(
                    isRunning = false,
                    isScanning = false,
                    statusText = "Bluetooth discovery stopped."
                )
            }
        }
    }

    private suspend fun refreshLoop() {
        while (scope.isActive && isStarted) {
            delay(AppConstants.bluetoothDiscoveryRefreshSeconds * 1_000L)
            refreshInternal(null)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun refreshInternal(reason: String?) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            _discoveryState.update { current ->
                current.copy(
                    isRunning = isStarted,
                    isScanning = false,
                    statusText = "Bluetooth hardware is not available on this Android device.",
                    lastError = "bluetooth_not_available"
                )
            }
            return
        }

        if (!hasBluetoothPermission()) {
            _discoveryState.update { current ->
                current.copy(
                    isRunning = isStarted,
                    isScanning = false,
                    statusText = "Bluetooth permission is required before paired peers can be listed.",
                    lastError = "bluetooth_permission_missing"
                )
            }
            return
        }

        if (!adapter.isEnabled) {
            _discoveryState.update { current ->
                current.copy(
                    isRunning = isStarted,
                    isScanning = false,
                    statusText = "Bluetooth is off on Android. Turn it on to use the fallback mode.",
                    lastError = "bluetooth_disabled"
                )
            }
            return
        }

        _discoveryState.update { current ->
            current.copy(
                isRunning = isStarted,
                isScanning = true,
                statusText = "Scanning paired Bluetooth peers..."
            )
        }

        val now = Instant.now()
        val trustedIds = trustedDevicesService.trustedDeviceIds.value
        val pairedPeers = runCatching {
            adapter.bondedDevices.orEmpty()
                .filter { device -> !device.name.isNullOrBlank() || !device.address.isNullOrBlank() }
                .map { device ->
                    val normalizedAddress = device.address.orEmpty()
                    val peerId = "bt-" + normalizedAddress.replace(":", "").replace("-", "").lowercase()
                    DevicePeer(
                        id = peerId,
                        displayName = device.name?.takeIf { it.isNotBlank() } ?: normalizedAddress,
                        platform = "Bluetooth device",
                        ipAddress = "",
                        port = 0,
                        bluetoothAddress = normalizedAddress,
                        appVersion = when (device.bondState) {
                            android.bluetooth.BluetoothDevice.BOND_BONDED -> "paired"
                            android.bluetooth.BluetoothDevice.BOND_BONDING -> "pairing"
                            else -> "available"
                        },
                        supportedModes = listOf(AppConstants.bluetoothMode),
                        transportMode = AppConnectionMode.BluetoothFallback,
                        isTrusted = trustedIds.contains(peerId),
                        isOnline = true,
                        pairingRequired = true,
                        lastSeenAtUtc = now.toString()
                    )
                }
        }.getOrElse { exception ->
            loggerService.warning("[BT-DISCOVERY] Scan failed: ${exception.message}")
            _discoveryState.update { current ->
                current.copy(
                    isRunning = isStarted,
                    isScanning = false,
                    statusText = "Bluetooth discovery failed and will retry.",
                    lastError = exception.message ?: "bluetooth_discovery_failed"
                )
            }
            return
        }

        val existingBluetoothIds = deviceRepository.devices.value
            .filter { it.transportMode == AppConnectionMode.BluetoothFallback }
            .map { it.id }
            .toSet()

        pairedPeers.forEach { peer ->
            deviceRepository.upsert(peer)
        }
        (existingBluetoothIds - pairedPeers.map { it.id }.toSet()).forEach { peerId ->
            deviceRepository.remove(peerId)
        }

        _discoveryState.update { current ->
            current.copy(
                isRunning = isStarted,
                isScanning = false,
                lastScanAtUtc = now.toString(),
                statusText = if (pairedPeers.isEmpty()) {
                    "No paired Bluetooth peers are currently available for Localink fallback."
                } else {
                    "Bluetooth scan finished. Found ${pairedPeers.size} paired fallback peer(s)."
                },
                lastError = null
            )
        }

        if (!reason.isNullOrBlank()) {
            loggerService.info("[BT-DISCOVERY] Refreshed paired Bluetooth peers for $reason.")
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
