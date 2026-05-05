package com.localbridge.android.models

data class DiscoveryStateModel(
    val isRunning: Boolean = false,
    val isScanning: Boolean = false,
    val statusText: String = "Discovery is idle.",
    val lastScanAtUtc: String? = null,
    val lastError: String? = null
)
