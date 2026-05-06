namespace LocalBridge.Desktop.Models;

public sealed class ConnectionSessionSnapshot
{
    public string SessionId { get; init; } = string.Empty;

    public string LocalDeviceId { get; init; } = string.Empty;

    public string LocalDeviceName { get; init; } = string.Empty;

    public DevicePeer Peer { get; init; } = new();

    public bool IsConnected { get; init; }

    public bool IsIncoming { get; init; }

    public AppConnectionMode TransportMode { get; init; } = AppConnectionMode.LocalLan;
}
