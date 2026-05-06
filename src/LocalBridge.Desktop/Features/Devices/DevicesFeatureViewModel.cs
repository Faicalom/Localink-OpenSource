using System.Collections.ObjectModel;
using System.Windows.Threading;
using LocalBridge.Desktop.Core.Mvvm;
using LocalBridge.Desktop.Models;
using LocalBridge.Desktop.Services;

namespace LocalBridge.Desktop.Features.Devices;

public sealed class DevicesFeatureViewModel : ObservableObject
{
    private readonly IDiscoveryService _discoveryService;
    private readonly ITrustedDevicesService _trustedDevicesService;
    private readonly ILoggerService _loggerService;
    private readonly DispatcherTimer _autoRefreshTimer;
    private bool _isRefreshing;
    private string _discoveryStatus = "Discovery has not started.";
    private DevicePeer? _selectedPeer;
    private string _pairingToken = string.Empty;

    public DevicesFeatureViewModel(
        IDiscoveryService discoveryService,
        ITrustedDevicesService trustedDevicesService,
        ILoggerService loggerService)
    {
        _discoveryService = discoveryService;
        _trustedDevicesService = trustedDevicesService;
        _loggerService = loggerService;

        Peers = [];
        TrustedPeers = [];
        PairingSessions = [];
        RefreshPeersCommand = new AsyncRelayCommand(_ => RefreshAsync(), _ => !IsRefreshing);
        ToggleSelectedPeerTrustCommand = new AsyncRelayCommand(_ => ToggleSelectedPeerTrustAsync(), _ => SelectedPeer is not null);
        RemoveTrustedPeerCommand = new AsyncRelayCommand(
            parameter => RemoveTrustedPeerAsync(parameter as DevicePeer),
            parameter => parameter is DevicePeer);

        _autoRefreshTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(2)
        };
        _autoRefreshTimer.Tick += async (_, _) => await LoadAsync(logRefresh: false);
    }

    public ObservableCollection<DevicePeer> Peers { get; }

    public ObservableCollection<DevicePeer> TrustedPeers { get; }

    public ObservableCollection<PairingSession> PairingSessions { get; }

    public AsyncRelayCommand RefreshPeersCommand { get; }

    public AsyncRelayCommand ToggleSelectedPeerTrustCommand { get; }

    public AsyncRelayCommand RemoveTrustedPeerCommand { get; }

    public bool IsRefreshing
    {
        get => _isRefreshing;
        private set
        {
            if (SetProperty(ref _isRefreshing, value))
            {
                RefreshPeersCommand.RaiseCanExecuteChanged();
            }
        }
    }

    public string DiscoveryStatus
    {
        get => _discoveryStatus;
        private set => SetProperty(ref _discoveryStatus, value);
    }

    public DevicePeer? SelectedPeer
    {
        get => _selectedPeer;
        set
        {
            if (SetProperty(ref _selectedPeer, value))
            {
                ToggleSelectedPeerTrustCommand.RaiseCanExecuteChanged();
            }
        }
    }

    public string PairingToken
    {
        get => _pairingToken;
        set => SetProperty(ref _pairingToken, value);
    }

    public async Task InitializeAsync()
    {
        _autoRefreshTimer.Start();
        await LoadAsync(logRefresh: true);
    }

    public Task ShutdownAsync()
    {
        _autoRefreshTimer.Stop();
        return Task.CompletedTask;
    }

    public async Task RefreshAsync()
    {
        IsRefreshing = true;

        try
        {
            await _discoveryService.RefreshAsync();
            await Task.Delay(600);
            await LoadAsync(logRefresh: true);
        }
        finally
        {
            IsRefreshing = false;
        }
    }

    public async Task LoadAsync(bool logRefresh = false)
    {
        var selectedPeerId = SelectedPeer?.Id;
        var trustedPeers = await _trustedDevicesService.GetTrustedDevicesAsync();
        var trustedIds = trustedPeers
            .Select(peer => peer.Id)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);

        var discoveredPeers = await _discoveryService.GetKnownPeersAsync();

        Peers.Clear();
        foreach (var peer in discoveredPeers)
        {
            peer.IsTrusted = peer.IsTrusted || trustedIds.Contains(peer.Id);
            Peers.Add(peer);
        }

        var nextSelectedPeer = string.IsNullOrWhiteSpace(selectedPeerId)
            ? null
            : Peers.FirstOrDefault(peer => string.Equals(peer.Id, selectedPeerId, StringComparison.OrdinalIgnoreCase));

        if (nextSelectedPeer is null && Peers.Count > 0)
        {
            nextSelectedPeer = Peers[0];
        }

        SelectedPeer = nextSelectedPeer;

        TrustedPeers.Clear();
        foreach (var trustedPeer in trustedPeers.OrderBy(peer => peer.DisplayName, StringComparer.OrdinalIgnoreCase))
        {
            TrustedPeers.Add(trustedPeer);
        }

        PairingSessions.Clear();

        DiscoveryStatus = Peers.Count == 0
            ? "Automatic hotspot/LAN and Bluetooth discovery is active. No compatible devices are visible right now."
            : $"Automatic hotspot/LAN and Bluetooth discovery is active. {Peers.Count} compatible device(s) currently visible.";

        if (logRefresh)
        {
            await _loggerService.LogInfoAsync($"Device list refreshed. {Peers.Count} device(s) currently discovered.");
        }
    }

    private async Task ToggleSelectedPeerTrustAsync()
    {
        if (SelectedPeer is null)
        {
            return;
        }

        if (SelectedPeer.IsTrusted)
        {
            await _trustedDevicesService.UntrustDeviceAsync(SelectedPeer.Id);
            SelectedPeer.IsTrusted = false;
            await _loggerService.LogInfoAsync($"Removed trust for {SelectedPeer.DisplayName}.");
        }
        else
        {
            await _trustedDevicesService.TrustDeviceAsync(SelectedPeer);
            SelectedPeer.IsTrusted = true;
            await _loggerService.LogInfoAsync($"Saved trust for {SelectedPeer.DisplayName}.");
        }

        await LoadAsync(logRefresh: false);
    }

    private async Task RemoveTrustedPeerAsync(DevicePeer? peer)
    {
        if (peer is null)
        {
            return;
        }

        await _trustedDevicesService.UntrustDeviceAsync(peer.Id);

        var discoveredPeer = Peers.FirstOrDefault(existing => string.Equals(existing.Id, peer.Id, StringComparison.OrdinalIgnoreCase));
        if (discoveredPeer is not null)
        {
            discoveredPeer.IsTrusted = false;
        }

        if (SelectedPeer is not null && string.Equals(SelectedPeer.Id, peer.Id, StringComparison.OrdinalIgnoreCase))
        {
            SelectedPeer.IsTrusted = false;
        }

        await _loggerService.LogInfoAsync($"Removed trusted device {peer.DisplayName} from the trust list.");
        await LoadAsync(logRefresh: false);
    }
}
