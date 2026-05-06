namespace LocalBridge.Desktop.Models;

public sealed class PairingSession
{
    public string SessionId { get; set; } = Guid.NewGuid().ToString("N");

    public string PeerId { get; set; } = string.Empty;

    public string PeerName { get; set; } = string.Empty;

    public string PairingCode { get; set; } = string.Empty;

    public DateTimeOffset RequestedAtUtc { get; set; } = DateTimeOffset.UtcNow;

    public DateTimeOffset ExpiresAtUtc { get; set; } = DateTimeOffset.UtcNow.AddMinutes(2);

    public bool IsApproved { get; set; }
}
