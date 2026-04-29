using System.IO;
using System.Text.Json;
using LocalBridge.Core;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Repositories;

public sealed class TransferRepository : ITransferRepository
{
    private readonly string _transfersPath;

    public TransferRepository()
    {
        var root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            AppConstants.AppName,
            "Desktop");

        Directory.CreateDirectory(root);
        _transfersPath = Path.Combine(root, AppConstants.DefaultTransferHistoryFileName);
    }

    public async Task<IReadOnlyList<TransferItem>> LoadAsync(CancellationToken cancellationToken = default)
    {
        if (!File.Exists(_transfersPath))
        {
            return [];
        }

        List<TransferItem> transfers;
        try
        {
            await using var readStream = File.OpenRead(_transfersPath);
            transfers = await JsonSerializer.DeserializeAsync<List<TransferItem>>(readStream, JsonDefaults.Options, cancellationToken)
                ?? [];
        }
        catch (JsonException)
        {
            JsonFileStore.BackupCorruptedFile(_transfersPath);
            return [];
        }

        foreach (var transfer in transfers)
        {
            if (transfer.Status is TransferState.Queued or TransferState.Preparing or TransferState.Sending or TransferState.Receiving)
            {
                transfer.Status = TransferState.Failed;
                transfer.LastError = "Transfer was interrupted before the app closed.";
            }
        }

        return transfers
            .Where(transfer => !IsDevelopmentArtifact(transfer))
            .OrderByDescending(transfer => transfer.CreatedAtUtc)
            .ToList();
    }

    public async Task SaveAsync(IReadOnlyList<TransferItem> transfers, CancellationToken cancellationToken = default)
    {
        await JsonFileStore.WriteJsonAtomicallyAsync(
            _transfersPath,
            transfers.OrderByDescending(transfer => transfer.CreatedAtUtc).ToList(),
            cancellationToken);
    }

    private static bool IsDevelopmentArtifact(TransferItem transfer)
    {
        return ContainsDevelopmentMarker(transfer.Id) ||
               ContainsDevelopmentMarker(transfer.PeerId) ||
               ContainsDevelopmentMarker(transfer.PeerName);
    }

    private static bool ContainsDevelopmentMarker(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return false;
        }

        return value.Contains("loopback", StringComparison.OrdinalIgnoreCase) ||
               value.Contains("protocol-", StringComparison.OrdinalIgnoreCase) ||
               value.Contains("incoming-peer", StringComparison.OrdinalIgnoreCase) ||
               value.Contains("incoming peer", StringComparison.OrdinalIgnoreCase);
    }
}
