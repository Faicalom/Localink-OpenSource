using LocalBridge.Core.Discovery;
using LocalBridge.Core.Protocol;
using Xunit;

namespace LocalBridge.Core.Tests;

public sealed class DiscoveryEnvelopeFactoryTests
{
    [Fact]
    public void Create_UsesDiscoveryPayloadFieldsInEnvelopeMetadata()
    {
        var sentAt = DateTimeOffset.UtcNow;
        var packet = new DiscoveryPacket(
            DeviceId: "windows-pc",
            DeviceName: "Windows-PC",
            Platform: "Windows",
            LocalIp: "192.168.137.1",
            ApiPort: 45870,
            AppVersion: "1.0.0",
            SupportedModes: [DiscoverySupportedModes.LocalLan, DiscoverySupportedModes.BluetoothFallback],
            PairingRequired: true,
            SentAtUtc: sentAt);

        var envelope = DiscoveryEnvelopeFactory.Create(DiscoveryPacketTypes.Announcement, packet);

        Assert.Equal(ProtocolConstants.Version, envelope.Meta.Version);
        Assert.Equal(ProtocolPacketTypes.DiscoveryAnnouncement, envelope.Meta.PacketType);
        Assert.Equal("windows-pc", envelope.Meta.SenderDeviceId);
        Assert.Equal(sentAt, envelope.Meta.SentAtUtc);
        Assert.Equal(packet, envelope.Payload);
    }
}
