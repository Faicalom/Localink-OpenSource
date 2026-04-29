using LocalBridge.Core.Protocol;

namespace LocalBridge.Desktop.Core;

public static class AppConstants
{
    public const string AppName = "Localink";
    public const string AppSubtitle = "Offline Windows-to-phone bridge over local hotspot or LAN";
    public const string ProtocolVersion = ProtocolConstants.Version;
    public const string DesktopPlatformName = "Windows";
    public const string AndroidPlatformName = "Android";
    public const string BluetoothServiceName = "Localink Bluetooth Fallback";
    public static readonly Guid BluetoothServiceId = new("8E2A108F-8F08-4760-BE46-62A7D2A66B50");
    public const int BluetoothDiscoveryRefreshSeconds = 12;
    public const int BluetoothPeerStaleTimeoutSeconds = 45;
    public const int BluetoothStreamReadTimeoutMilliseconds = 15000;
    public const int BluetoothFrameMaxJsonBytes = 256 * 1024;
    public const int BluetoothTransferChunkSizeBytes = 256 * 1024;
    public const int BluetoothSmallFileTransferLimitBytes = 300 * 1024 * 1024;

    public const int DefaultApiPort = 45870;
    public const int DefaultDiscoveryPort = 45871;
    public const int DiscoveryAnnouncementIntervalSeconds = 5;
    public const int DiscoveryProbeIntervalSeconds = 7;
    public const int DiscoveryCleanupIntervalSeconds = 3;
    public const int DiscoveryPeerStaleTimeoutSeconds = 30;

    public const string DefaultDownloadFolderName = "Localink";
    public const string DefaultSettingsFileName = "desktop-settings.json";
    public const string DefaultTrustedDevicesFileName = "trusted-devices.json";
    public const string DefaultLocalDeviceProfileFileName = "local-device.json";
    public const string DefaultChatHistoryFileName = "chat-history.json";
    public const string DefaultTransferHistoryFileName = "transfers.json";
    public const string DefaultLogFileName = "desktop.log";

    public const int ConnectionHeartbeatIntervalSeconds = 4;
    public const int ConnectionRequestTimeoutSeconds = 5;
    public const int ConnectionReconnectDelaySeconds = 4;
    public const int ChatRetryIntervalSeconds = 5;
    public const int ChatAutoRetryLimit = 5;
    public const int TransferChunkSizeBytes = 16 * 1024 * 1024;
    public const int TransferRequestTimeoutSeconds = 120;
    public const int TransferRecoveryRetryLimit = 4;
    public const long TransferMaxFileSizeBytes = 20L * 1024 * 1024 * 1024;

    public const string ConnectionStatusPath = "/api/connection/status";
    public const string ConnectionHandshakePath = "/api/connection/handshake";
    public const string ConnectionHeartbeatPath = "/api/connection/heartbeat";
    public const string ConnectionDisconnectPath = "/api/connection/disconnect";
    public const string ChatMessagesPath = "/api/chat/messages";
    public const string TransferPreparePath = "/api/transfers/prepare";
    public const string TransferChunkPath = "/api/transfers/chunk";
    public const string TransferCompletePath = "/api/transfers/complete";
    public const string TransferCancelPath = "/api/transfers/cancel";
}
