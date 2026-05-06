using System.Windows;
using Localink.Desktop.Models;

namespace Localink.Desktop.Services;

public interface ILocalizationService
{
    AppLanguage CurrentLanguage { get; }

    FlowDirection CurrentFlowDirection { get; }

    event Action<AppLanguage>? LanguageChanged;

    Task InitializeAsync(AppLanguage language, CancellationToken cancellationToken = default);

    Task ApplyLanguageAsync(AppLanguage language, CancellationToken cancellationToken = default);

    string GetString(string key);
}
