using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Repositories;

public interface ISettingsRepository
{
    Task<AppConnectionMode> LoadPreferredModeAsync(CancellationToken cancellationToken = default);

    Task<string> LoadDownloadFolderAsync(CancellationToken cancellationToken = default);

    Task<bool> LoadAutoDiscoveryAsync(CancellationToken cancellationToken = default);

    Task<AppLanguage> LoadLanguageAsync(CancellationToken cancellationToken = default);

    Task SaveAsync(
        AppConnectionMode preferredMode,
        string downloadFolder,
        bool autoStartDiscovery,
        AppLanguage language,
        CancellationToken cancellationToken = default);
}
