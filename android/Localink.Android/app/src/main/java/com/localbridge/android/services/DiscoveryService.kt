package com.localbridge.android.services

import com.localbridge.android.models.DiscoveryStateModel
import com.localbridge.android.models.DevicePeer
import kotlinx.coroutines.flow.StateFlow

interface DiscoveryService {
    val devices: StateFlow<List<DevicePeer>>
    val discoveryState: StateFlow<DiscoveryStateModel>

    fun start()
    fun refresh()
    fun stop()
}
