package com.localbridge.android.core.qr

import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.DevicePeer

object PairingQrPeerResolver {
    fun resolve(
        scanResult: PairingQrScanResult,
        discoveredPeers: List<DevicePeer>,
        preferredMode: AppConnectionMode
    ): DevicePeer? {
        val qrPeer = scanResult.peer
        val bluetoothMatch = findBluetoothPeer(discoveredPeers, qrPeer)
        val lanPeer = qrPeer

        return when (preferredMode) {
            AppConnectionMode.BluetoothFallback -> bluetoothMatch ?: lanPeer
            AppConnectionMode.LocalWifiLan -> lanPeer ?: bluetoothMatch
        }
    }

    private fun findBluetoothPeer(
        discoveredPeers: List<DevicePeer>,
        qrPeer: DevicePeer?
    ): DevicePeer? {
        val bluetoothPeers = discoveredPeers.filter { it.transportMode == AppConnectionMode.BluetoothFallback }
        if (bluetoothPeers.isEmpty()) {
            return null
        }

        if (qrPeer != null) {
            bluetoothPeers.firstOrNull { candidate ->
                candidate.displayName.equals(qrPeer.displayName, ignoreCase = true)
            }?.let { return it }
        }

        return bluetoothPeers.singleOrNull()
    }
}
