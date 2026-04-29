using System.IO;
using System.Text.Json;
using LocalBridge.Core;
using LocalBridge.Core.Discovery;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Repositories;

public sealed class LocalDeviceProfileRepository : ILocalDeviceProfileRepository
{
    private readonly string _profilePath;

    public LocalDeviceProfileRepository()
    {
        var root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            AppConstants.AppName,
            "Desktop");

        Directory.CreateDirectory(root);
        _profilePath = Path.Combine(root, AppConstants.DefaultLocalDeviceProfileFileName);
    }

    public async Task<LocalDeviceProfile> LoadOrCreateAsync(CancellationToken cancellationToken = default)
    {
        if (File.Exists(_profilePath))
        {
            try
            {
                await using var readStream = File.OpenRead(_profilePath);
                var existing = await JsonSerializer.DeserializeAsync<LocalDeviceProfile>(readStream, JsonDefaults.Options, cancellationToken);
                if (existing is not null)
                {
                    existing.Platform = AppConstants.DesktopPlatformName;
                    existing.AppVersion = ResolveAppVersion();
                    existing.ApiPort = AppConstants.DefaultApiPort;
                    existing.DiscoveryPort = AppConstants.DefaultDiscoveryPort;
                    existing.SupportedModes = [DiscoverySupportedModes.LocalLan, DiscoverySupportedModes.BluetoothFallback];
                    return existing;
                }
            }
            catch (JsonException)
            {
                JsonFileStore.BackupCorruptedFile(_profilePath);
            }
        }

        var created = new LocalDeviceProfile
        {
            DeviceId = $"win-{Guid.NewGuid():N}",
            DeviceName = Environment.MachineName,
            Platform = AppConstants.DesktopPlatformName,
            AppVersion = ResolveAppVersion(),
            SupportedModes = [DiscoverySupportedModes.LocalLan, DiscoverySupportedModes.BluetoothFallback],
            ApiPort = AppConstants.DefaultApiPort,
            DiscoveryPort = AppConstants.DefaultDiscoveryPort,
            CreatedAtUtc = DateTimeOffset.UtcNow
        };

        await SaveAsync(created, cancellationToken);
        return created;
    }

    public async Task SaveAsync(LocalDeviceProfile profile, CancellationToken cancellationToken = default)
    {
        await JsonFileStore.WriteJsonAtomicallyAsync(_profilePath, profile, cancellationToken);
    }

    private static string ResolveAppVersion()
    {
        return typeof(LocalDeviceProfileRepository).Assembly.GetName().Version?.ToString(3) ?? "0.1.0";
    }
}
