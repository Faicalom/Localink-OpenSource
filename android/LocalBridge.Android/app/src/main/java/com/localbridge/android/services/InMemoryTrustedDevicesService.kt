package com.localbridge.android.services

import com.localbridge.android.core.logging.LoggerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryTrustedDevicesService(
    private val loggerService: LoggerService
) : TrustedDevicesService {
    private val _trustedDeviceIds = MutableStateFlow(emptySet<String>())

    override val trustedDeviceIds: StateFlow<Set<String>> = _trustedDeviceIds.asStateFlow()

    override fun trust(deviceId: String) {
        _trustedDeviceIds.value = _trustedDeviceIds.value + deviceId
        loggerService.info("Device $deviceId marked as trusted.")
    }

    override fun untrust(deviceId: String) {
        _trustedDeviceIds.value = _trustedDeviceIds.value - deviceId
        loggerService.info("Device $deviceId removed from trusted list.")
    }
}
