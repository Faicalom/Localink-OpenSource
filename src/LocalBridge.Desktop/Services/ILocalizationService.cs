using System.Windows;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Services;

public interface ILocalizationService
{
    AppLanguage CurrentLanguage { get; }

    FlowDirection CurrentFlowDirection { get; }

    event Action<AppLanguage>? LanguageChanged;

    Task InitializeAsync(AppLanguage language, CancellationToken cancellationToken = default);

    Task ApplyLanguageAsync(AppLanguage language, CancellationToken cancellationToken = default);

    string GetString(string key);
}
