package com.localink.android.services

import com.localink.android.models.DiscoveryStateModel
import com.localink.android.models.DevicePeer
import kotlinx.coroutines.flow.StateFlow

interface DiscoveryService {
    val devices: StateFlow<List<DevicePeer>>
    val discoveryState: StateFlow<DiscoveryStateModel>

    fun start()
    fun refresh()
    fun stop()
}
