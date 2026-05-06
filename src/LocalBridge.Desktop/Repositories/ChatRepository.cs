using System.IO;
using System.Text.Json;
using LocalBridge.Core;
using LocalBridge.Desktop.Core;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Repositories;

public sealed class ChatRepository : IChatRepository
{
    private readonly string _chatHistoryPath;

    public ChatRepository()
    {
        var root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            AppConstants.AppName,
            "Desktop");

        Directory.CreateDirectory(root);
        _chatHistoryPath = Path.Combine(root, AppConstants.DefaultChatHistoryFileName);
    }

    public async Task<IReadOnlyList<ChatMessage>> LoadAsync(CancellationToken cancellationToken = default)
    {
        if (!File.Exists(_chatHistoryPath))
        {
            return [];
        }

        List<ChatMessage>? messages;
        try
        {
            await using var readStream = File.OpenRead(_chatHistoryPath);
            messages = await JsonSerializer.DeserializeAsync<List<ChatMessage>>(readStream, JsonDefaults.Options, cancellationToken);
        }
        catch (JsonException)
        {
            JsonFileStore.BackupCorruptedFile(_chatHistoryPath);
            return [];
        }

        return messages?
            .Where(message => !IsDevelopmentArtifact(message))
            .OrderBy(message => message.TimestampUtc)
            .ToList() ?? [];
    }

    public async Task SaveAsync(IReadOnlyList<ChatMessage> messages, CancellationToken cancellationToken = default)
    {
        await JsonFileStore.WriteJsonAtomicallyAsync(
            _chatHistoryPath,
            messages.OrderBy(message => message.TimestampUtc).ToList(),
            cancellationToken);
    }

    private static bool IsDevelopmentArtifact(ChatMessage message)
    {
        return ContainsDevelopmentMarker(message.Id) ||
               ContainsDevelopmentMarker(message.SenderId) ||
               ContainsDevelopmentMarker(message.ReceiverId) ||
               ContainsDevelopmentMarker(message.SenderName);
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
