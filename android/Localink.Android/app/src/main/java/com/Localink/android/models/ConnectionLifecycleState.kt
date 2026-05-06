package com.localink.android.models

enum class ConnectionLifecycleState {
    Idle,
    Discovering,
    Connecting,
    WaitingForPairing,
    Paired,
    Connected,
    TransferInProgress,
    Disconnected,
    Failed
}
