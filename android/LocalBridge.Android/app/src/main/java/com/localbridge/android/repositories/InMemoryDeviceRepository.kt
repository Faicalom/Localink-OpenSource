package com.localbridge.android.repositories

import com.localbridge.android.models.DevicePeer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryDeviceRepository : DeviceRepository {
    private val _devices = MutableStateFlow(emptyList<DevicePeer>())

    override val devices: StateFlow<List<DevicePeer>> = _devices.asStateFlow()

    override suspend fun replaceAll(devices: List<DevicePeer>) {
        _devices.value = devices
    }

    override suspend fun upsert(device: DevicePeer) {
        val existing = _devices.value
        val updated = if (existing.any { it.id == device.id }) {
            existing.map { current -> if (current.id == device.id) device else current }
        } else {
            existing + device
        }

        _devices.value = updated.sortedWith(
            compareByDescending<DevicePeer> { it.isOnline }
                .thenBy { it.displayName.lowercase() }
        )
    }

    override suspend fun remove(deviceId: String) {
        _devices.value = _devices.value.filterNot { it.id == deviceId }
    }

    override suspend fun updateTrust(deviceIds: Set<String>) {
        _devices.value = _devices.value.map { peer ->
            peer.copy(isTrusted = deviceIds.contains(peer.id))
        }
    }
}
