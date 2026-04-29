using LocalBridge.Core.Protocol;

namespace LocalBridge.Core.Discovery;

public static class DiscoveryPacketTypes
{
    public const string Probe = ProtocolPacketTypes.DiscoveryProbe;
    public const string Reply = ProtocolPacketTypes.DiscoveryReply;
    public const string Announcement = ProtocolPacketTypes.DiscoveryAnnouncement;
}

public static class DiscoverySupportedModes
{
    public const string LocalLan = "local-lan";
    public const string BluetoothFallback = "bluetooth-fallback";
}

public sealed record DiscoveryPacket(
    string DeviceId,
    string DeviceName,
    string Platform,
    string LocalIp,
    int ApiPort,
    string AppVersion,
    string[] SupportedModes,
    bool PairingRequired,
    DateTimeOffset SentAtUtc);

public static class DiscoveryEnvelopeFactory
{
    public static ProtocolEnvelope<DiscoveryPacket> Create(string packetType, DiscoveryPacket payload)
    {
        return ProtocolEnvelopeFactory.Create(
            packetType: packetType,
            payload: payload,
            senderDeviceId: payload.DeviceId,
            sentAtUtc: payload.SentAtUtc);
    }
}
