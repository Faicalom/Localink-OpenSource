using LocalBridge.Desktop.Core.Mvvm;

namespace LocalBridge.Desktop.Models;

public sealed class ConnectionStateModel : ObservableObject
{
    private AppConnectionMode _currentMode = AppConnectionMode.Auto;
    private ConnectionLifecycleState _lifecycleState = ConnectionLifecycleState.Idle;
    private bool _isDiscovering;
    private bool _isConnected;
    private bool _canAcceptIncomingConnections;
    private bool _isServerListening;
    private bool _isReconnectScheduled;
    private int _reconnectAttemptCount;
    private string _localEndpointSummary = "Discovery has not started yet.";
    private string _statusText = "Waiting for initialization.";
    private string _localPairingCode = "------";
    private string _transportHint = "Auto prefers hotspot/LAN first, then falls back to Bluetooth.";
    private string _activePeerName = "No peer selected";
    private string _activePeerId = string.Empty;
    private string _sessionId = string.Empty;
    private string _lastFailure = string.Empty;
    private string _handshakeSummary = "No handshake yet.";
    private string _activeTransportLabel = "No active transport";
    private string _availableTransportsLabel = "Hotspot/LAN + Bluetooth discovery";
    private string _networkRoutesSummary = "No local adapters detected yet.";

    public AppConnectionMode CurrentMode
    {
        get => _currentMode;
        set => SetProperty(ref _currentMode, value);
    }

    public ConnectionLifecycleState LifecycleState
    {
        get => _lifecycleState;
        set => SetProperty(ref _lifecycleState, value);
    }

    public bool IsDiscovering
    {
        get => _isDiscovering;
        set => SetProperty(ref _isDiscovering, value);
    }

    public bool IsConnected
    {
        get => _isConnected;
        set => SetProperty(ref _isConnected, value);
    }

    public bool CanAcceptIncomingConnections
    {
        get => _canAcceptIncomingConnections;
        set => SetProperty(ref _canAcceptIncomingConnections, value);
    }

    public bool IsServerListening
    {
        get => _isServerListening;
        set => SetProperty(ref _isServerListening, value);
    }

    public bool IsReconnectScheduled
    {
        get => _isReconnectScheduled;
        set => SetProperty(ref _isReconnectScheduled, value);
    }

    public int ReconnectAttemptCount
    {
        get => _reconnectAttemptCount;
        set => SetProperty(ref _reconnectAttemptCount, value);
    }

    public string LocalEndpointSummary
    {
        get => _localEndpointSummary;
        set => SetProperty(ref _localEndpointSummary, value);
    }

    public string StatusText
    {
        get => _statusText;
        set => SetProperty(ref _statusText, value);
    }

    public string LocalPairingCode
    {
        get => _localPairingCode;
        set => SetProperty(ref _localPairingCode, value);
    }

    public string TransportHint
    {
        get => _transportHint;
        set => SetProperty(ref _transportHint, value);
    }

    public string ActivePeerName
    {
        get => _activePeerName;
        set => SetProperty(ref _activePeerName, value);
    }

    public string ActivePeerId
    {
        get => _activePeerId;
        set => SetProperty(ref _activePeerId, value);
    }

    public string SessionId
    {
        get => _sessionId;
        set => SetProperty(ref _sessionId, value);
    }

    public string LastFailure
    {
        get => _lastFailure;
        set => SetProperty(ref _lastFailure, value);
    }

    public string HandshakeSummary
    {
        get => _handshakeSummary;
        set => SetProperty(ref _handshakeSummary, value);
    }

    public string ActiveTransportLabel
    {
        get => _activeTransportLabel;
        set => SetProperty(ref _activeTransportLabel, value);
    }

    public string AvailableTransportsLabel
    {
        get => _availableTransportsLabel;
        set => SetProperty(ref _availableTransportsLabel, value);
    }

    public string NetworkRoutesSummary
    {
        get => _networkRoutesSummary;
        set => SetProperty(ref _networkRoutesSummary, value);
    }
}
