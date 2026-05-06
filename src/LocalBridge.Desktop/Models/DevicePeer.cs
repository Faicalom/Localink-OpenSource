namespace LocalBridge.Desktop.Models;

public sealed class DevicePeer
{
    public string Id { get; set; } = string.Empty;

    public string DisplayName { get; set; } = string.Empty;

    public string Platform { get; set; } = string.Empty;

    public string IpAddress { get; set; } = string.Empty;

    public int Port { get; set; }

    public string BluetoothAddress { get; set; } = string.Empty;

    public string AppVersion { get; set; } = string.Empty;

    public string[] SupportedModes { get; set; } = [];

    public AppConnectionMode TransportMode { get; set; } = AppConnectionMode.LocalLan;

    public bool IsTrusted { get; set; }

    public bool IsOnline { get; set; }

    public DateTimeOffset FirstSeenAtUtc { get; set; } = DateTimeOffset.UtcNow;

    public DateTimeOffset LastSeenAtUtc { get; set; } = DateTimeOffset.UtcNow;

    public bool HasResolvedIdentity { get; set; } = true;

    public string StatusLabel => IsOnline ? "Online" : "Offline";

    public string EndpointLabel => TransportMode == AppConnectionMode.BluetoothFallback
        ? (string.IsNullOrWhiteSpace(BluetoothAddress) ? "Bluetooth RFCOMM" : $"BT {BluetoothAddress}")
        : $"{IpAddress}:{Port}";

    public string TransportLabel => TransportMode switch
    {
        AppConnectionMode.Auto => "Auto",
        AppConnectionMode.BluetoothFallback => "Bluetooth",
        _ => "Hotspot/LAN"
    };

    public string SupportedModesLabel => SupportedModes.Length == 0
        ? "None"
        : string.Join(", ", SupportedModes);

    public string LastSeenLabel => LastSeenAtUtc.ToLocalTime().ToString("HH:mm:ss");
}
