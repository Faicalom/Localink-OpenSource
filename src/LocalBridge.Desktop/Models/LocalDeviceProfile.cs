namespace LocalBridge.Desktop.Models;

public sealed class LocalDeviceProfile
{
    public string DeviceId { get; set; } = string.Empty;

    public string DeviceName { get; set; } = string.Empty;

    public string Platform { get; set; } = string.Empty;

    public string AppVersion { get; set; } = string.Empty;

    public string[] SupportedModes { get; set; } = [];

    public string[] LocalIpAddresses { get; set; } = [];

    public string[] LocalNetworkRoutes { get; set; } = [];

    public int ApiPort { get; set; }

    public int DiscoveryPort { get; set; }

    public DateTimeOffset CreatedAtUtc { get; set; } = DateTimeOffset.UtcNow;
}
