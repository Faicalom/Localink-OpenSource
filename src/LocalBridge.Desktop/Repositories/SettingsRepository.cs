using System.IO;
using System.Text.Json;
using LocalBridge.Core;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Repositories;

public sealed class SettingsRepository : ISettingsRepository
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly string _settingsPath;

    private SettingsDocument? _cachedDocument;

    public SettingsRepository()
    {
        var root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            AppConstants.AppName,
            "Desktop");

        Directory.CreateDirectory(root);
        _settingsPath = Path.Combine(root, AppConstants.DefaultSettingsFileName);
    }

    public async Task<AppConnectionMode> LoadPreferredModeAsync(CancellationToken cancellationToken = default)
    {
        var document = await LoadDocumentAsync(cancellationToken);
        return document.PreferredMode;
    }

    public async Task<string> LoadDownloadFolderAsync(CancellationToken cancellationToken = default)
    {
        var document = await LoadDocumentAsync(cancellationToken);
        return document.DownloadFolder;
    }

    public async Task<bool> LoadAutoDiscoveryAsync(CancellationToken cancellationToken = default)
    {
        var document = await LoadDocumentAsync(cancellationToken);
        return document.AutoStartDiscovery;
    }

    public async Task<AppLanguage> LoadLanguageAsync(CancellationToken cancellationToken = default)
    {
        var document = await LoadDocumentAsync(cancellationToken);
        return document.Language;
    }

    public async Task SaveAsync(
        AppConnectionMode preferredMode,
        string downloadFolder,
        bool autoStartDiscovery,
        AppLanguage language,
        CancellationToken cancellationToken = default)
    {
        var normalizedFolder = string.IsNullOrWhiteSpace(downloadFolder)
            ? GetDefaultDownloadFolder()
            : downloadFolder.Trim();

        Directory.CreateDirectory(normalizedFolder);

        var document = new SettingsDocument(
            preferredMode,
            normalizedFolder,
            autoStartDiscovery,
            language);

        await _gate.WaitAsync(cancellationToken);
        try
        {
            await JsonFileStore.WriteJsonAtomicallyAsync(_settingsPath, document, cancellationToken);
            _cachedDocument = document;
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task<SettingsDocument> LoadDocumentAsync(CancellationToken cancellationToken)
    {
        if (_cachedDocument is not null)
        {
            return _cachedDocument;
        }

        await _gate.WaitAsync(cancellationToken);
        try
        {
            if (_cachedDocument is not null)
            {
                return _cachedDocument;
            }

            if (!File.Exists(_settingsPath))
            {
                _cachedDocument = CreateDefaultDocument();
                return _cachedDocument;
            }

            try
            {
                await using var readStream = File.OpenRead(_settingsPath);
                _cachedDocument = await JsonSerializer.DeserializeAsync<SettingsDocument>(
                    readStream,
                    JsonDefaults.Options,
                    cancellationToken)
                    ?? CreateDefaultDocument();
            }
            catch (JsonException)
            {
                JsonFileStore.BackupCorruptedFile(_settingsPath);
                _cachedDocument = CreateDefaultDocument();
                await JsonFileStore.WriteJsonAtomicallyAsync(_settingsPath, _cachedDocument, cancellationToken);
            }

            if (string.IsNullOrWhiteSpace(_cachedDocument.DownloadFolder))
            {
                _cachedDocument = _cachedDocument with
                {
                    DownloadFolder = GetDefaultDownloadFolder()
                };
            }

            return _cachedDocument;
        }
        finally
        {
            _gate.Release();
        }
    }

    private static SettingsDocument CreateDefaultDocument()
    {
        return new SettingsDocument(
            AppConnectionMode.Auto,
            GetDefaultDownloadFolder(),
            true,
            AppLanguage.English);
    }

    private static string GetDefaultDownloadFolder()
    {
        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
            AppConstants.DefaultDownloadFolderName);
    }

    private sealed record SettingsDocument(
        AppConnectionMode PreferredMode,
        string DownloadFolder,
        bool AutoStartDiscovery,
        AppLanguage Language);
}
