using System.IO;
using LocalBridge.Core.Protocol;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Services;

public interface IFileTransferEndpointHandler
{
    Task<FileTransferPrepareResponseDto> PrepareIncomingTransferAsync(
        FileTransferPrepareRequestDto request,
        ConnectionSessionSnapshot session,
        CancellationToken cancellationToken = default);

    Task<FileTransferChunkResponseDto> ReceiveChunkAsync(
        FileTransferChunkDescriptorDto descriptor,
        Stream contentStream,
        ConnectionSessionSnapshot session,
        CancellationToken cancellationToken = default);

    Task<FileTransferCompleteResponseDto> CompleteIncomingTransferAsync(
        FileTransferCompleteRequestDto request,
        ConnectionSessionSnapshot session,
        CancellationToken cancellationToken = default);

    Task<FileTransferCancelResponseDto> CancelIncomingTransferAsync(
        FileTransferCancelRequestDto request,
        ConnectionSessionSnapshot session,
        CancellationToken cancellationToken = default);
}
