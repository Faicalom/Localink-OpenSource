using System.IO;
using LocalBridge.Core.Protocol;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Services;

public sealed class BridgeFileTransferService : IFileTransferService, IFileTransferEndpointHandler
{
    private readonly IFileTransferService _lanFileTransferService;
    private readonly IFileTransferEndpointHandler _lanFileTransferHandler;
    private readonly IConnectionService _connectionService;
    private readonly ILoggerService _loggerService;

    public BridgeFileTransferService(
        IFileTransferService lanFileTransferService,
        IFileTransferEndpointHandler lanFileTransferHandler,
        IConnectionService connectionService,
        ILoggerService loggerService)
    {
        _lanFileTransferService = lanFileTransferService;
        _lanFileTransferHandler = lanFileTransferHandler;
        _connectionService = connectionService;
        _loggerService = loggerService;
    }

    public event Action<TransferItem>? TransferAdded
    {
        add => _lanFileTransferService.TransferAdded += value;
        remove => _lanFileTransferService.TransferAdded -= value;
    }

    public Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        return _lanFileTransferService.InitializeAsync(cancellationToken);
    }

    public Task ShutdownAsync(CancellationToken cancellationToken = default)
    {
        return _lanFileTransferService.ShutdownAsync(cancellationToken);
    }

    public Task<IReadOnlyList<TransferItem>> GetTransfersAsync(CancellationToken cancellationToken = default)
    {
        return _lanFileTransferService.GetTransfersAsync(cancellationToken);
    }

    public async Task QueueFilesAsync(IEnumerable<string> filePaths, CancellationToken cancellationToken = default)
    {
        var session = await _connectionService.GetActiveSessionAsync(cancellationToken);
        if (session is null)
        {
            await _loggerService.LogWarningAsync(
                "File selection was ignored because no local peer is connected.",
                cancellationToken);
            return;
        }
        await _lanFileTransferService.QueueFilesAsync(filePaths, cancellationToken);
    }

    public Task PauseTransferAsync(TransferItem transferItem, CancellationToken cancellationToken = default)
    {
        return _lanFileTransferService.PauseTransferAsync(transferItem, cancellationToken);
    }

    public Task ResumeTransferAsync(TransferItem transferItem, CancellationToken cancellationToken = default)
    {
        return _lanFileTransferService.ResumeTransferAsync(transferItem, cancellationToken);
    }

    public Task CancelTransferAsync(TransferItem transferItem, CancellationToken cancellationToken = default)
    {
        return _lanFileTransferService.CancelTransferAsync(transferItem, cancellationToken);
    }

    public Task OpenTransferAsync(TransferItem transferItem, CancellationToken cancellationToken = default)
    {
        return _lanFileTransferService.OpenTransferAsync(transferItem, cancellationToken);
    }

    public Task<int> ClearHistoryAsync(CancellationToken cancellationToken = default)
    {
        return _lanFileTransferService.ClearHistoryAsync(cancellationToken);
    }

    public Task<FileTransferPrepareResponseDto> PrepareIncomingTransferAsync(
        FileTransferPrepareRequestDto request,
        ConnectionSessionSnapshot session,
        CancellationToken cancellationToken = default)
    {
        return _lanFileTransferHandler.PrepareIncomingTransferAsync(request, session, cancellationToken);
    }

    public Task<FileTransferChunkResponseDto> ReceiveChunkAsync(
        FileTransferChunkDescriptorDto descriptor,
        Stream contentStream,
        ConnectionSessionSnapshot session,
        CancellationToken cancellationToken = default)
    {
        return _lanFileTransferHandler.ReceiveChunkAsync(descriptor, contentStream, session, cancellationToken);
    }

    public Task<FileTransferCompleteResponseDto> CompleteIncomingTransferAsync(
        FileTransferCompleteRequestDto request,
        ConnectionSessionSnapshot session,
        CancellationToken cancellationToken = default)
    {
        return _lanFileTransferHandler.CompleteIncomingTransferAsync(request, session, cancellationToken);
    }

    public Task<FileTransferCancelResponseDto> CancelIncomingTransferAsync(
        FileTransferCancelRequestDto request,
        ConnectionSessionSnapshot session,
        CancellationToken cancellationToken = default)
    {
        return _lanFileTransferHandler.CancelIncomingTransferAsync(request, session, cancellationToken);
    }
}
