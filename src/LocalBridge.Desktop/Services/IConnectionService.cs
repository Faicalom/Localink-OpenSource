using LocalBridge.Core.Protocol;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Services;

public interface IConnectionService
{
    event Action<ConnectionSessionSnapshot?>? SessionChanged;

    event Action<TextChatPacketDto>? ChatMessageReceived;

    void RegisterFileTransferHandler(IFileTransferEndpointHandler handler);

    Task InitializeAsync(ConnectionStateModel connectionState, CancellationToken cancellationToken = default);

    Task SetConnectionModeAsync(
        ConnectionStateModel connectionState,
        AppConnectionMode mode,
        CancellationToken cancellationToken = default);

    Task SetDiscoveryActivityAsync(
        ConnectionStateModel connectionState,
        bool isActive,
        CancellationToken cancellationToken = default);

    Task ConnectToPeerAsync(
        ConnectionStateModel connectionState,
        DevicePeer? peer,
        string pairingToken,
        CancellationToken cancellationToken = default);

    Task<ConnectionSessionSnapshot?> GetActiveSessionAsync(CancellationToken cancellationToken = default);

    Task<LocalDeviceProfile> GetLocalDeviceProfileAsync(CancellationToken cancellationToken = default);

    Task<TextChatDeliveryReceiptDto> SendChatMessageAsync(TextChatPacketDto packet, CancellationToken cancellationToken = default);

    Task DisconnectAsync(ConnectionStateModel connectionState, CancellationToken cancellationToken = default);

    Task ShutdownAsync(ConnectionStateModel connectionState, CancellationToken cancellationToken = default);
}
