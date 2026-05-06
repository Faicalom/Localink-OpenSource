package com.localbridge.android.services

import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.repositories.TrustedDevicesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PersistentTrustedDevicesService(
    private val repository: TrustedDevicesRepository,
    private val loggerService: LoggerService
) : TrustedDevicesService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val trustedDeviceIds: StateFlow<Set<String>> = repository.trustedDeviceIds.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = emptySet()
    )

    override fun trust(deviceId: String) {
        scope.launch {
            val updated = trustedDeviceIds.value + deviceId
            repository.save(updated)
            loggerService.info("Device $deviceId marked as trusted.")
        }
    }

    override fun untrust(deviceId: String) {
        scope.launch {
            val updated = trustedDeviceIds.value - deviceId
            repository.save(updated)
            loggerService.info("Device $deviceId removed from trusted list.")
        }
    }
}
