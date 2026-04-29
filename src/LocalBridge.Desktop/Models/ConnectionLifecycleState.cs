namespace LocalBridge.Desktop.Models;

public enum ConnectionLifecycleState
{
    Idle = 0,
    Discovering = 1,
    Connecting = 2,
    WaitingForPairing = 3,
    Paired = 4,
    Connected = 5,
    TransferInProgress = 6,
    Disconnected = 7,
    Failed = 8
}
