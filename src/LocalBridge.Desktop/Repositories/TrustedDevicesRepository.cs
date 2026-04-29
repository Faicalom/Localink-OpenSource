using System.IO;
using System.Text.Json;
using LocalBridge.Core;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Repositories;

public sealed class TrustedDevicesRepository : ITrustedDevicesRepository
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly string _trustedDevicesPath;
    private List<DevicePeer>? _cachedPeers;

    public TrustedDevicesRepository()
    {
        var root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            AppConstants.AppName,
            "Desktop");

        Directory.CreateDirectory(root);
        _trustedDevicesPath = Path.Combine(root, AppConstants.DefaultTrustedDevicesFileName);
    }

    public async Task<IReadOnlyList<DevicePeer>> LoadAsync(CancellationToken cancellationToken = default)
    {
        if (_cachedPeers is not null)
        {
            return _cachedPeers.Select(Clone).ToList();
        }

        await _gate.WaitAsync(cancellationToken);
        try
        {
            if (_cachedPeers is not null)
            {
                return _cachedPeers.Select(Clone).ToList();
            }

            if (!File.Exists(_trustedDevicesPath))
            {
                _cachedPeers = [];
                return [];
            }

            try
            {
                await using var readStream = File.OpenRead(_trustedDevicesPath);
                _cachedPeers = await JsonSerializer.DeserializeAsync<List<DevicePeer>>(
                    readStream,
                    JsonDefaults.Options,
                    cancellationToken) ?? [];
            }
            catch (JsonException)
            {
                JsonFileStore.BackupCorruptedFile(_trustedDevicesPath);
                _cachedPeers = [];
            }

            return _cachedPeers.Select(Clone).ToList();
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task SaveAsync(IReadOnlyList<DevicePeer> peers, CancellationToken cancellationToken = default)
    {
        var normalized = peers
            .Select(Clone)
            .DistinctBy(peer => peer.Id, StringComparer.OrdinalIgnoreCase)
            .OrderBy(peer => peer.DisplayName, StringComparer.OrdinalIgnoreCase)
            .ToList();

        await _gate.WaitAsync(cancellationToken);
        try
        {
            await JsonFileStore.WriteJsonAtomicallyAsync(_trustedDevicesPath, normalized, cancellationToken);
            _cachedPeers = normalized;
        }
        finally
        {
            _gate.Release();
        }
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
