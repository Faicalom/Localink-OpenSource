using System.IO;
using System.Windows;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Core.Routing;
using LocalBridge.Desktop.Features.Chat;
using LocalBridge.Desktop.Features.Devices;
using LocalBridge.Desktop.Features.Settings;
using LocalBridge.Desktop.Features.Transfers;
using LocalBridge.Desktop.Repositories;
using LocalBridge.Desktop.Services;
using LocalBridge.Desktop.Ui.Screens;

namespace LocalBridge.Desktop;

public partial class App : Application
{
    private HomeScreenViewModel? _homeScreenViewModel;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        try
        {
            var loggerService = new LoggerService();
            var router = new AppRouter();

            var localDeviceProfileRepository = new LocalDeviceProfileRepository();
            var trustedDevicesRepository = new TrustedDevicesRepository();
            var settingsRepository = new SettingsRepository();
            var localizationService = new LocalizationService();
            var chatRepository = new ChatRepository();
            var transferRepository = new TransferRepository();
            var language = await settingsRepository.LoadLanguageAsync();
            await localizationService.InitializeAsync(language);

            var trustedDevicesService = new TrustedDevicesService(trustedDevicesRepository, loggerService);
            var lanDiscoveryService = new DiscoveryService(loggerService, localDeviceProfileRepository);
            var bluetoothDiscoveryService = new BluetoothDiscoveryService(loggerService, localDeviceProfileRepository);
            var discoveryService = new BridgeDiscoveryService(lanDiscoveryService, bluetoothDiscoveryService);
            var lanConnectionService = new ConnectionService(lanDiscoveryService, trustedDevicesService, loggerService);
            var bluetoothConnectionService = new BluetoothConnectionService(localDeviceProfileRepository, trustedDevicesService, loggerService);
            var connectionService = new BridgeConnectionService(lanConnectionService, bluetoothConnectionService, loggerService);
            var chatService = new ChatService(connectionService, chatRepository, loggerService);
            var lanFileTransferService = new FileTransferService(connectionService, bluetoothConnectionService, settingsRepository, transferRepository, loggerService);
            var fileTransferService = new BridgeFileTransferService(lanFileTransferService, lanFileTransferService, connectionService, loggerService);
            connectionService.RegisterFileTransferHandler(fileTransferService);

            var devicesFeature = new DevicesFeatureViewModel(discoveryService, trustedDevicesService, loggerService);
            var chatFeature = new ChatFeatureViewModel(chatService, connectionService);
            var transfersFeature = new TransfersFeatureViewModel(fileTransferService, connectionService);
            var settingsFeature = new SettingsFeatureViewModel(settingsRepository, localizationService);

            _homeScreenViewModel = new HomeScreenViewModel(
                router,
                discoveryService,
                connectionService,
                loggerService,
                devicesFeature,
                chatFeature,
                transfersFeature,
                settingsFeature);

            var window = new MainWindow
            {
                DataContext = _homeScreenViewModel,
                FlowDirection = localizationService.CurrentFlowDirection
            };

            MainWindow = window;
            window.Show();
            await _homeScreenViewModel.InitializeAsync();
        }
        catch (Exception ex)
        {
            WriteStartupCrashLog(ex);
            MessageBox.Show(
                $"Localink could not start.\n\n{ex.Message}",
                "Startup Error",
                MessageBoxButton.OK,
                MessageBoxImage.Error);

            Shutdown();
        }
    }

    protected override async void OnExit(ExitEventArgs e)
    {
        if (_homeScreenViewModel is not null)
        {
            await _homeScreenViewModel.ShutdownAsync();
        }

        base.OnExit(e);
    }

    private static void WriteStartupCrashLog(Exception exception)
    {
        try
        {
            var root = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                AppConstants.AppName,
                "Desktop");

            Directory.CreateDirectory(root);
            var crashPath = Path.Combine(root, "startup-crash.log");
            var payload = $"[{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss}] {exception}\n\n";
            File.AppendAllText(crashPath, payload);
        }
        catch
        {
            // Best effort only; startup must still surface the original error dialog.
        }
    }
}
