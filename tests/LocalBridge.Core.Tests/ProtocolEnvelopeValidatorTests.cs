using LocalBridge.Core.Protocol;
using Xunit;

namespace LocalBridge.Core.Tests;

public sealed class ProtocolEnvelopeValidatorTests
{
    [Fact]
    public void Validate_ReturnsValid_WhenEnvelopeMatchesVersionTypeAndPayload()
    {
        var envelope = ProtocolEnvelopeFactory.Create(
            packetType: ProtocolPacketTypes.ChatTextMessage,
            payload: new TextChatPacketDto(
                Id: "msg-1",
                SessionId: "session-1",
                SenderId: "sender-1",
                SenderName: "Sender",
                ReceiverId: "receiver-1",
                Text: "hello",
                TimestampUtc: DateTimeOffset.UtcNow),
            senderDeviceId: "sender-1",
            receiverDeviceId: "receiver-1",
            sessionId: "session-1");

        var result = ProtocolEnvelopeValidator.Validate(
            envelope,
            expectedPacketTypes: [ProtocolPacketTypes.ChatTextMessage]);

        Assert.True(result.IsValid);
        Assert.Null(result.ErrorCode);
    }

    [Fact]
    public void Validate_ReturnsProtocolMismatch_WhenVersionDiffers()
    {
        var envelope = new ProtocolEnvelope<TextChatPacketDto>(
            Meta: new ProtocolMetadata(
                Version: "0.9",
                PacketType: ProtocolPacketTypes.ChatTextMessage,
                MessageId: "msg-2",
                SentAtUtc: DateTimeOffset.UtcNow),
            Payload: new TextChatPacketDto(
                Id: "msg-2",
                SessionId: "session-2",
                SenderId: "sender-2",
                SenderName: "Sender",
                ReceiverId: "receiver-2",
                Text: "hello",
                TimestampUtc: DateTimeOffset.UtcNow),
            Error: null);

        var result = ProtocolEnvelopeValidator.Validate(
            envelope,
            expectedPacketTypes: [ProtocolPacketTypes.ChatTextMessage]);

        Assert.False(result.IsValid);
        Assert.Equal(ProtocolErrorCodes.ProtocolMismatch, result.ErrorCode);
    }

    [Fact]
    public void Validate_ReturnsInvalidRequest_WhenPayloadIsRequiredButMissing()
    {
        var envelope = new ProtocolEnvelope<object?>(
            Meta: new ProtocolMetadata(
                Version: ProtocolConstants.Version,
                PacketType: ProtocolPacketTypes.ConnectionHandshakeRequest,
                MessageId: "msg-3",
                SentAtUtc: DateTimeOffset.UtcNow),
            Payload: null,
            Error: null);

        var result = ProtocolEnvelopeValidator.Validate(
            envelope,
            requirePayload: true,
            expectedPacketTypes: [ProtocolPacketTypes.ConnectionHandshakeRequest]);

        Assert.False(result.IsValid);
        Assert.Equal(ProtocolErrorCodes.InvalidRequest, result.ErrorCode);
    }
}
