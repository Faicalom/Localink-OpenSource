namespace LocalBridge.Desktop.Models;

public enum TransferState
{
    Queued = 0,
    Preparing = 1,
    Sending = 2,
    Receiving = 3,
    Paused = 4,
    Completed = 5,
    Failed = 6,
    Canceled = 7
}
