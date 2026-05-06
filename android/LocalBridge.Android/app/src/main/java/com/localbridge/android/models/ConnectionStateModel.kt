package com.localbridge.android.models

data class ConnectionStateModel(
    val lifecycleState: ConnectionLifecycleState = ConnectionLifecycleState.Idle,
    val statusText: String = "Ready to discover Localink desktop peers.",
    val sessionId: String? = null,
    val connectedPeer: DevicePeer? = null,
    val selectedMode: AppConnectionMode = AppConnectionMode.LocalWifiLan,
    val localPairingCode: String = "------",
    val protocolVersion: String,
    val lastError: String? = null,
    val handshakeSummary: String = "Waiting for a Windows peer.",
    val isReconnectScheduled: Boolean = false,
    val reconnectAttemptCount: Int = 0
) {
    val isConnected: Boolean
        get() = lifecycleState == ConnectionLifecycleState.Connected
}
