using System.Globalization;
using System.Windows;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Services;

public sealed class LocalizationService : ILocalizationService
{
    private const string LanguageDictionaryPrefix = "Theme/Strings.";
    private ResourceDictionary? _currentDictionary;

    public AppLanguage CurrentLanguage { get; private set; } = AppLanguage.English;

    public FlowDirection CurrentFlowDirection => CurrentLanguage == AppLanguage.Arabic
        ? FlowDirection.RightToLeft
        : FlowDirection.LeftToRight;

    public event Action<AppLanguage>? LanguageChanged;

    public Task InitializeAsync(AppLanguage language, CancellationToken cancellationToken = default)
    {
        return ApplyLanguageAsync(language, cancellationToken);
    }

    public async Task ApplyLanguageAsync(AppLanguage language, CancellationToken cancellationToken = default)
    {
        var dispatcher = Application.Current?.Dispatcher;

        if (dispatcher is null || dispatcher.CheckAccess())
        {
            ApplyLanguageCore(language);
            return;
        }

        await dispatcher.InvokeAsync(() => ApplyLanguageCore(language), System.Windows.Threading.DispatcherPriority.Send, cancellationToken);
    }

    public string GetString(string key)
    {
        var value = Application.Current?.TryFindResource(key) as string;
        return string.IsNullOrWhiteSpace(value) ? key : value;
    }

    private void ApplyLanguageCore(AppLanguage language)
    {
        var application = Application.Current;
        if (application is null)
        {
            return;
        }

        var culture = new CultureInfo(language == AppLanguage.Arabic ? "ar" : "en");
        CultureInfo.CurrentCulture = culture;
        CultureInfo.CurrentUICulture = culture;

        var dictionaries = application.Resources.MergedDictionaries;
        if (_currentDictionary is not null)
        {
            dictionaries.Remove(_currentDictionary);
        }

        _currentDictionary = new ResourceDictionary
        {
            Source = new Uri($"{LanguageDictionaryPrefix}{GetLanguageSuffix(language)}.xaml", UriKind.Relative)
        };
        dictionaries.Add(_currentDictionary);

        CurrentLanguage = language;
        application.Resources["AppFlowDirection"] = CurrentFlowDirection;

        if (application.MainWindow is not null)
        {
            application.MainWindow.FlowDirection = CurrentFlowDirection;
        }

        LanguageChanged?.Invoke(language);
    }

    private static string GetLanguageSuffix(AppLanguage language)
    {
        return language == AppLanguage.Arabic ? "ar" : "en";
    }
}
