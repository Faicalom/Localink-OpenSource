package com.localbridge.android.core.qr

import com.localbridge.android.core.protocol.ProtocolJson
import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.DevicePeer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

data class PairingQrScanResult(
    val pairingToken: String,
    val peer: DevicePeer? = null
)

object PairingQrPayloadParser {
    private const val payloadType = "localink_pairing"
    private const val rawPairingLength = 6

    fun parse(rawValue: String): PairingQrScanResult? {
        val normalized = rawValue.trim()
        if (normalized.isSixDigitPairingToken()) {
            return PairingQrScanResult(pairingToken = normalized)
        }

        val payload = runCatching {
            ProtocolJson.format.decodeFromString<PairingQrPayload>(normalized)
        }.getOrNull() ?: return null

        if (payload.type != payloadType) {
            return null
        }

        val token = payload.pairingToken.trim()
        if (!token.isSixDigitPairingToken()) {
            return null
        }

        return PairingQrScanResult(
            pairingToken = token,
            peer = payload.toDevicePeerOrNull()
        )
    }

    private fun PairingQrPayload.toDevicePeerOrNull(): DevicePeer? {
        val resolvedDeviceId = deviceId?.trim().orEmpty()
        val resolvedName = deviceName?.trim().orEmpty()
        val resolvedPort = apiPort?.takeIf { it > 0 } ?: return null
        val resolvedAddresses = localIpAddresses
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val primaryAddress = resolvedAddresses.firstOrNull() ?: return null
        if (resolvedDeviceId.isBlank() || resolvedName.isBlank()) {
            return null
        }

        return DevicePeer(
            id = resolvedDeviceId,
            displayName = resolvedName,
            platform = platform?.trim().takeUnless { it.isNullOrBlank() } ?: "Windows",
            ipAddress = primaryAddress,
            port = resolvedPort,
            appVersion = appVersion?.trim().orEmpty(),
            supportedModes = supportedModes
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .ifEmpty { listOf("local-lan") },
            transportMode = AppConnectionMode.LocalWifiLan,
            isTrusted = false,
            isOnline = true,
            pairingRequired = true,
            lastSeenAtUtc = generatedAtUtc?.trim().orEmpty(),
            candidateIpAddresses = resolvedAddresses
        )
    }

    private fun String.isSixDigitPairingToken(): Boolean {
        return length == rawPairingLength && all { it.isDigit() }
    }
}

@Serializable
private data class PairingQrPayload(
    val type: String,
    val version: Int = 1,
    val pairingToken: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val platform: String? = null,
    val appVersion: String? = null,
    val supportedModes: List<String> = emptyList(),
    val localIpAddresses: List<String> = emptyList(),
    val apiPort: Int? = null,
    val discoveryPort: Int? = null,
    val generatedAtUtc: String? = null
)
