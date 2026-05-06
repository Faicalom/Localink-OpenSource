package com.localink.android.services

import com.localink.android.models.ConnectionStateModel
import com.localink.android.models.DevicePeer
import kotlinx.coroutines.flow.StateFlow

interface ConnectionService {
    val connectionState: StateFlow<ConnectionStateModel>
    val activePeer: StateFlow<DevicePeer?>

    fun start() {}
    fun connect(peer: DevicePeer, pairingToken: String)
    fun disconnect()
}
