package com.localbridge.android.models

enum class AppConnectionMode(val wireValue: String) {
    LocalWifiLan("wifi_lan"),
    BluetoothFallback("bluetooth_fallback");

    companion object {
        fun fromWireValue(value: String?): AppConnectionMode {
            return entries.firstOrNull { it.wireValue == value } ?: LocalWifiLan
        }
    }
}
