using LocalBridge.Desktop.Models;
using LocalBridge.Desktop.Repositories;

namespace LocalBridge.Desktop.Services;

public sealed class TrustedDevicesService : ITrustedDevicesService
{
    private readonly ITrustedDevicesRepository _repository;
    private readonly ILoggerService _loggerService;

    public TrustedDevicesService(ITrustedDevicesRepository repository, ILoggerService loggerService)
    {
        _repository = repository;
        _loggerService = loggerService;
    }

    public Task<IReadOnlyList<DevicePeer>> GetTrustedDevicesAsync(CancellationToken cancellationToken = default)
    {
        return _repository.LoadAsync(cancellationToken);
    }

    public async Task TrustDeviceAsync(DevicePeer peer, CancellationToken cancellationToken = default)
    {
        var trusted = (await _repository.LoadAsync(cancellationToken)).ToList();

        var existingIndex = trusted.FindIndex(existing => string.Equals(existing.Id, peer.Id, StringComparison.OrdinalIgnoreCase));

        if (existingIndex >= 0)
        {
            peer.IsTrusted = true;
            trusted[existingIndex] = Clone(peer);
            await _repository.SaveAsync(trusted, cancellationToken);
            await _loggerService.LogInfoAsync($"Trusted device entry updated for {peer.DisplayName}.", cancellationToken);
            return;
        }

        peer.IsTrusted = true;
        trusted.Add(Clone(peer));
        await _repository.SaveAsync(trusted, cancellationToken);
        await _loggerService.LogInfoAsync($"Trusted device entry saved for {peer.DisplayName}.", cancellationToken);
    }

    public async Task UntrustDeviceAsync(string peerId, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(peerId))
        {
            return;
        }

        var trusted = (await _repository.LoadAsync(cancellationToken)).ToList();
        var removedCount = trusted.RemoveAll(existing => string.Equals(existing.Id, peerId, StringComparison.OrdinalIgnoreCase));

        if (removedCount == 0)
        {
            return;
        }

        await _repository.SaveAsync(trusted, cancellationToken);
        await _loggerService.LogInfoAsync($"Trusted device {peerId} removed from the local trust store.", cancellationToken);
    }

    private static DevicePeer Clone(DevicePeer peer)
    {
        return new DevicePeer
        {
            Id = peer.Id,
            DisplayName = peer.DisplayName,
            Platform = peer.Platform,
            IpAddress = peer.IpAddress,
            Port = peer.Port,
            BluetoothAddress = peer.BluetoothAddress,
            AppVersion = peer.AppVersion,
            SupportedModes = peer.SupportedModes.ToArray(),
            TransportMode = peer.TransportMode,
            IsTrusted = peer.IsTrusted,
            IsOnline = peer.IsOnline,
            FirstSeenAtUtc = peer.FirstSeenAtUtc,
            LastSeenAtUtc = peer.LastSeenAtUtc,
            HasResolvedIdentity = peer.HasResolvedIdentity
        };
    }
}
