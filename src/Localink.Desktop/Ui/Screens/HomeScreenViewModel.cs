using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Windows.Media;
using Localink.Desktop.Core;
using Localink.Desktop.Core.Mvvm;
using Localink.Desktop.Core.Routing;
using Localink.Desktop.Features.Chat;
using Localink.Desktop.Features.Devices;
using Localink.Desktop.Features.Settings;
using Localink.Desktop.Features.Transfers;
using Localink.Desktop.Infrastructure;
using Localink.Desktop.Models;
using Localink.Desktop.Services;

namespace Localink.Desktop.Ui.Screens;

public sealed class HomeScreenViewModel : ObservableObject
{
    private readonly AppRouter _router;
    private readonly IDiscoveryService _discoveryService;
    private readonly IConnectionService _connectionService;
    private readonly ILoggerService _loggerService;
    private string _connectionRouteBadge = string.Empty;
    private ImageSource? _pairingQrImage;

    public HomeScreenViewModel(
        AppRouter router,
        IDiscoveryService discoveryService,
        IConnectionService connectionService,
        ILoggerService loggerService,
        DevicesFeatureViewModel devices,
        ChatFeatureViewModel chat,
        TransfersFeatureViewModel transfers,
        SettingsFeatureViewModel settings)
    {
        _router = router;
        _discoveryService = discoveryService;
        _connectionService = connectionService;
        _loggerService = loggerService;

        ConnectionState = new ConnectionStateModel();
        _connectionRouteBadge = ConnectionState.ActiveTransportLabel;
        Devices = devices;
        Chat = chat;
        Transfers = transfers;
        Settings = settings;

        NavigateHomeCommand = new RelayCommand(_ => _router.Navigate(AppRoutes.Home));
        ConnectToSelectedPeerCommand = new AsyncRelayCommand(_ => ConnectToSelectedPeerAsync(), _ => CanConnectToSelectedPeer());
        DisconnectPeerCommand = new AsyncRelayCommand(_ => DisconnectPeerAsync(), _ => CanDisconnectPeer());
        ClearChatHistoryCommand = new AsyncRelayCommand(_ => ClearChatHistoryAsync());
        ClearTransferHistoryCommand = new AsyncRelayCommand(_ => ClearTransferHistoryAsync());
        ClearAppLogCommand = new AsyncRelayCommand(_ => ClearAppLogAsync());
    }

    public string Title => AppConstants.AppName;

    public string Subtitle => AppConstants.AppSubtitle;

    public string CurrentRoute => _router.CurrentRoute;

    public string ConnectionRouteBadge
    {
        get => _connectionRouteBadge;
        set => SetProperty(ref _connectionRouteBadge, value);
    }

    public ConnectionStateModel ConnectionState { get; }

    public DevicesFeatureViewModel Devices { get; }

    public ChatFeatureViewModel Chat { get; }

    public TransfersFeatureViewModel Transfers { get; }

    public SettingsFeatureViewModel Settings { get; }

    public ObservableCollection<string> Logs => _loggerService.Entries;

    public string LogFilePath => _loggerService.LogFilePath;

    public ImageSource? PairingQrImage
    {
        get => _pairingQrImage;
        private set => SetProperty(ref _pairingQrImage, value);
    }

    public RelayCommand NavigateHomeCommand { get; }

    public AsyncRelayCommand ConnectToSelectedPeerCommand { get; }

    public AsyncRelayCommand DisconnectPeerCommand { get; }

    public AsyncRelayCommand ClearChatHistoryCommand { get; }

    public AsyncRelayCommand ClearTransferHistoryCommand { get; }

    public AsyncRelayCommand ClearAppLogCommand { get; }

    public async Task InitializeAsync()
    {
        _router.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName == nameof(AppRouter.CurrentRoute))
            {
                RaisePropertyChanged(nameof(CurrentRoute));
            }
        };

        ConnectionState.PropertyChanged += HandleStateChanged;
        Devices.PropertyChanged += HandleStateChanged;
        Settings.PropertyChanged += HandleSettingsChanged;

        await Settings.LoadAsync();
        await _connectionService.InitializeAsync(ConnectionState);
        await _connectionService.SetConnectionModeAsync(ConnectionState, Settings.PreferredConnectionMode);

        if (Settings.AutoStartDiscovery)
        {
            await _discoveryService.StartAsync();
            await _connectionService.SetDiscoveryActivityAsync(ConnectionState, true);
        }
        else
        {
            await _connectionService.SetDiscoveryActivityAsync(ConnectionState, false);
        }

        await Devices.InitializeAsync();
        await Chat.LoadAsync();
        await Transfers.LoadAsync();
        await RefreshPairingQrAsync();
        await _loggerService.LogInfoAsync("Localink Windows app is running with hotspot/LAN plus Bluetooth fallback support.");
    }

    public async Task ShutdownAsync()
    {
        await Devices.ShutdownAsync();
        await Chat.ShutdownAsync();
        await Transfers.ShutdownAsync();
        await _discoveryService.StopAsync();
        await _connectionService.SetDiscoveryActivityAsync(ConnectionState, false);
        await _connectionService.ShutdownAsync(ConnectionState);
        await _loggerService.LogInfoAsync("Localink Windows app shut down.");
    }

    private async Task ConnectToSelectedPeerAsync()
    {
        await _connectionService.ConnectToPeerAsync(
            ConnectionState,
            Devices.SelectedPeer,
            Devices.PairingToken);
    }

    private async Task DisconnectPeerAsync()
    {
        await _connectionService.DisconnectAsync(ConnectionState);
    }

    private async Task ClearChatHistoryAsync()
    {
        var removedCount = await Chat.ClearHistoryAsync();
        await _loggerService.LogInfoAsync($"[HISTORY] Desktop chat history clear requested from the settings tab. Removed {removedCount} message(s).");
    }

    private async Task ClearTransferHistoryAsync()
    {
        var removedCount = await Transfers.ClearHistoryAsync();
        await Chat.LoadAsync();
        await _loggerService.LogInfoAsync($"[HISTORY] Desktop transfer history clear requested from the settings tab. Removed {removedCount} record(s).");
    }

    private async Task ClearAppLogAsync()
    {
        await _loggerService.ClearAsync();
        await _loggerService.LogInfoAsync("[HISTORY] Desktop application log was cleared from the settings tab.");
    }

    private bool CanConnectToSelectedPeer()
    {
        return Devices.SelectedPeer is not null &&
               !ConnectionState.IsConnected &&
               IsPeerCompatibleWithCurrentMode(Devices.SelectedPeer) &&
               ConnectionState.LifecycleState is not ConnectionLifecycleState.Connecting;
    }

    private bool CanDisconnectPeer()
    {
        return ConnectionState.IsConnected ||
               ConnectionState.LifecycleState is ConnectionLifecycleState.Paired or ConnectionLifecycleState.WaitingForPairing;
    }

    private void HandleStateChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(ConnectionState.ActiveTransportLabel))
        {
            ConnectionRouteBadge = ConnectionState.ActiveTransportLabel;
        }

        if (e.PropertyName == nameof(ConnectionState.LocalPairingCode))
        {
            _ = RefreshPairingQrAsync();
        }

        ConnectToSelectedPeerCommand.RaiseCanExecuteChanged();
        DisconnectPeerCommand.RaiseCanExecuteChanged();
    }

    private void HandleSettingsChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(SettingsFeatureViewModel.PreferredConnectionMode))
        {
            _ = _connectionService.SetConnectionModeAsync(ConnectionState, Settings.PreferredConnectionMode);
            ConnectToSelectedPeerCommand.RaiseCanExecuteChanged();
        }
    }

    private bool IsPeerCompatibleWithCurrentMode(DevicePeer peer)
    {
        return ConnectionState.CurrentMode switch
        {
            AppConnectionMode.Auto => true,
            AppConnectionMode.LocalLan => peer.TransportMode == AppConnectionMode.LocalLan,
            AppConnectionMode.BluetoothFallback => peer.TransportMode == AppConnectionMode.BluetoothFallback,
            _ => false
        };
    }

    private async Task RefreshPairingQrAsync()
    {
        try
        {
            var localDevice = await _connectionService.GetLocalDeviceProfileAsync();
            PairingQrImage = PairingQrCodeBuilder.CreateImageSource(ConnectionState.LocalPairingCode, localDevice);
        }
        catch (Exception ex)
        {
            PairingQrImage = null;
            await _loggerService.LogWarningAsync($"[PAIRING][QR] QR refresh failed: {ex.Message}");
        }
    }
}

