namespace LocalBridge.Core.Security;

public sealed record DesktopIdentity(
    string DeviceId,
    string DeviceName,
    DateTimeOffset CreatedAtUtc);

public sealed record TrustedDevice(
    string DeviceId,
    string DeviceName,
    string SharedSecret,
    DateTimeOffset PairedAtUtc,
    DateTimeOffset LastSeenAtUtc);
