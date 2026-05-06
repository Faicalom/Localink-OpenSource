namespace LocalBridge.Core.Protocol;

public sealed record ProtocolEnvelopeValidationResult(
    bool IsValid,
    string? ErrorCode = null,
    string? ErrorMessage = null)
{
    public static ProtocolEnvelopeValidationResult Valid { get; } = new(true);
}

public static class ProtocolEnvelopeValidator
{
    public static ProtocolEnvelopeValidationResult Validate<TPayload>(
        ProtocolEnvelope<TPayload>? envelope,
        bool requirePayload = true,
        params string[] expectedPacketTypes)
    {
        if (envelope is null || envelope.Meta is null)
        {
            return Invalid(
                ProtocolErrorCodes.InvalidRequest,
                "Protocol envelope is missing or malformed.");
        }

        if (!string.Equals(envelope.Meta.Version, ProtocolConstants.Version, StringComparison.OrdinalIgnoreCase))
        {
            return Invalid(
                ProtocolErrorCodes.ProtocolMismatch,
                "Protocol version mismatch.");
        }

        if (expectedPacketTypes.Length > 0 &&
            !Array.Exists(
                expectedPacketTypes,
                packetType => string.Equals(packetType, envelope.Meta.PacketType, StringComparison.OrdinalIgnoreCase)))
        {
            return Invalid(
                ProtocolErrorCodes.InvalidRequest,
                $"Unexpected packet type '{envelope.Meta.PacketType}'.");
        }

        if (requirePayload && envelope.Payload is null)
        {
            return Invalid(
                ProtocolErrorCodes.InvalidRequest,
                "Protocol payload is missing.");
        }

        return ProtocolEnvelopeValidationResult.Valid;
    }

    private static ProtocolEnvelopeValidationResult Invalid(string errorCode, string errorMessage)
    {
        return new ProtocolEnvelopeValidationResult(
            IsValid: false,
            ErrorCode: errorCode,
            ErrorMessage: errorMessage);
    }
}
