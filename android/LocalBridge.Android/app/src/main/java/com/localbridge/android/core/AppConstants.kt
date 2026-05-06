package com.localbridge.android.core

import com.localbridge.android.core.protocol.ProtocolConstants

object AppConstants {
    const val appName = "Localink"
    const val appSubtitle = "Offline Android companion for the Windows bridge"
    const val desktopPlatformName = "Windows"
    const val androidPlatformName = "Android"
    const val defaultApiPort = 45870
    const val defaultDiscoveryPort = 45871
    const val appVersion = "1.0.0-alpha"
    const val settingsStoreName = "localbridge_settings"
    const val trustedDevicesStoreName = "localbridge_trusted_devices"
    const val logsFileName = "localbridge.log"
    const val localDeviceFileName = "local-device-profile.json"
    const val defaultChatHistoryFileName = "chat-history.json"
    const val defaultTransferHistoryFileName = "transfers.json"
    const val defaultReceiveFolderName = "received"
    const val defaultPublicDownloadsFolderName = "Localink"
    const val defaultTrustedFolderName = "trusted"
    const val localWifiMode = "wifi_lan"
    const val bluetoothMode = "bluetooth_fallback"
    const val protocolVersion = ProtocolConstants.version
    const val bluetoothServiceName = "Localink Bluetooth Fallback"
    const val bluetoothServiceId = "8e2a108f-8f08-4760-be46-62a7d2a66b50"
    const val bluetoothSerialPortServiceId = "00001101-0000-1000-8000-00805f9b34fb"
    const val bluetoothDiscoveryRefreshSeconds = 12
    const val bluetoothPeerStaleTimeoutSeconds = 45
    const val bluetoothStreamReadTimeoutMillis = 15_000
    const val bluetoothFrameMaxJsonBytes = 256 * 1024
    const val bluetoothTransferChunkSizeBytes = 256 * 1024
    const val bluetoothSmallFileTransferLimitBytes = 300 * 1024 * 1024

    const val discoveryAnnouncementIntervalSeconds = 5
    const val discoveryPeerStaleTimeoutSeconds = 30
    const val discoveryScanWindowMillis = 2_500L
    const val connectionHeartbeatIntervalSeconds = 4
    const val connectionRequestTimeoutMillis = 5_000
    const val connectionReconnectDelayMillis = 4_000L
    const val chatRetryIntervalSeconds = 5
    const val chatAutoRetryLimit = 5
    const val localHttpServerBacklog = 16
    const val transferChunkSizeBytes = 1 * 1024 * 1024
    const val transferRequestTimeoutMillis = 120_000
    const val transferRecoveryRetryLimit = 4
    const val transferIncomingSyncChunkInterval = 8
    const val transferMaxFileSizeBytes = 20L * 1024 * 1024 * 1024
    const val transferForegroundNotificationChannelId = "localbridge.transfers"
    const val transferForegroundNotificationId = 145870

    const val connectionStatusPath = "/api/connection/status"
    const val connectionHandshakePath = "/api/connection/handshake"
    const val connectionHeartbeatPath = "/api/connection/heartbeat"
    const val connectionDisconnectPath = "/api/connection/disconnect"
    const val chatMessagesPath = "/api/chat/messages"
    const val transferPreparePath = "/api/transfers/prepare"
    const val transferChunkPath = "/api/transfers/chunk"
    const val transferCompletePath = "/api/transfers/complete"
    const val transferCancelPath = "/api/transfers/cancel"
}
