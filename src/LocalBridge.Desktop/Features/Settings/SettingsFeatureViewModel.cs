using LocalBridge.Desktop.Core.Mvvm;
using LocalBridge.Desktop.Models;
using LocalBridge.Desktop.Repositories;
using LocalBridge.Desktop.Services;

namespace LocalBridge.Desktop.Features.Settings;

public sealed class SettingsFeatureViewModel : ObservableObject
{
    private readonly ISettingsRepository _settingsRepository;
    private readonly ILocalizationService _localizationService;
    private AppConnectionMode _preferredConnectionMode = AppConnectionMode.Auto;
    private string _downloadFolder = string.Empty;
    private bool _autoStartDiscovery;
    private AppLanguage _selectedLanguage = AppLanguage.English;
    private IReadOnlyList<ConnectionModeOption> _availableConnectionModes = [];
    private IReadOnlyList<LanguageOption> _availableLanguages = [];

    public SettingsFeatureViewModel(
        ISettingsRepository settingsRepository,
        ILocalizationService localizationService)
    {
        _settingsRepository = settingsRepository;
        _localizationService = localizationService;
        SaveSettingsCommand = new AsyncRelayCommand(_ => SaveAsync());
        RebuildLanguages();
        RebuildConnectionModes();
        _localizationService.LanguageChanged += HandleLanguageChanged;
    }

    public IReadOnlyList<ConnectionModeOption> AvailableConnectionModes
    {
        get => _availableConnectionModes;
        private set => SetProperty(ref _availableConnectionModes, value);
    }

    public IReadOnlyList<LanguageOption> AvailableLanguages
    {
        get => _availableLanguages;
        private set => SetProperty(ref _availableLanguages, value);
    }

    public AppConnectionMode PreferredConnectionMode
    {
        get => _preferredConnectionMode;
        set => SetProperty(ref _preferredConnectionMode, value);
    }

    public string DownloadFolder
    {
        get => _downloadFolder;
        set => SetProperty(ref _downloadFolder, value);
    }

    public bool AutoStartDiscovery
    {
        get => _autoStartDiscovery;
        set => SetProperty(ref _autoStartDiscovery, value);
    }

    public AppLanguage SelectedLanguage
    {
        get => _selectedLanguage;
        set => SetProperty(ref _selectedLanguage, value);
    }

    public AsyncRelayCommand SaveSettingsCommand { get; }

    public async Task LoadAsync()
    {
        PreferredConnectionMode = await _settingsRepository.LoadPreferredModeAsync();
        DownloadFolder = await _settingsRepository.LoadDownloadFolderAsync();
        AutoStartDiscovery = await _settingsRepository.LoadAutoDiscoveryAsync();
        SelectedLanguage = await _settingsRepository.LoadLanguageAsync();
    }

    public async Task SaveAsync()
    {
        await _settingsRepository.SaveAsync(PreferredConnectionMode, DownloadFolder, AutoStartDiscovery, SelectedLanguage);
        await _localizationService.ApplyLanguageAsync(SelectedLanguage);
    }

    private void HandleLanguageChanged(AppLanguage _)
    {
        RebuildLanguages();
        RebuildConnectionModes();
    }

    private void RebuildLanguages()
    {
        AvailableLanguages =
        [
            new LanguageOption(AppLanguage.English, _localizationService.GetString("LanguageEnglish")),
            new LanguageOption(AppLanguage.Arabic, _localizationService.GetString("LanguageArabic"))
        ];
    }

    private void RebuildConnectionModes()
    {
        AvailableConnectionModes =
        [
            new ConnectionModeOption(AppConnectionMode.Auto, _localizationService.GetString("ConnectionModeAuto")),
            new ConnectionModeOption(AppConnectionMode.LocalLan, _localizationService.GetString("ConnectionModeLan")),
            new ConnectionModeOption(AppConnectionMode.BluetoothFallback, _localizationService.GetString("ConnectionModeBluetooth"))
        ];
    }

    public sealed record ConnectionModeOption(AppConnectionMode Mode, string Label)
    {
        public override string ToString() => Label;
    }

    public sealed record LanguageOption(AppLanguage Language, string Label)
    {
        public override string ToString() => Label;
    }
}
