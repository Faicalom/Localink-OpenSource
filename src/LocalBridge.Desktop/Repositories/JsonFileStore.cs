using System.IO;
using System.Text.Json;
using LocalBridge.Core;

namespace LocalBridge.Desktop.Repositories;

internal static class JsonFileStore
{
    public static string BackupCorruptedFile(string path)
    {
        if (!File.Exists(path))
        {
            return path;
        }

        var directory = Path.GetDirectoryName(path)!;
        var fileName = Path.GetFileName(path);
        var stamp = DateTimeOffset.Now.ToString("yyyyMMdd-HHmmssfff");
        var backupPath = Path.Combine(directory, $"{fileName}.corrupt-{stamp}.bak");
        var suffix = 1;

        while (File.Exists(backupPath))
        {
            backupPath = Path.Combine(directory, $"{fileName}.corrupt-{stamp}-{suffix++}.bak");
        }

        File.Move(path, backupPath);
        return backupPath;
    }

    public static async Task WriteJsonAtomicallyAsync<T>(string path, T value, CancellationToken cancellationToken = default)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);

        var tempPath = $"{path}.tmp";
        if (File.Exists(tempPath))
        {
            File.Delete(tempPath);
        }

        await using (var writeStream = new FileStream(
                         tempPath,
                         FileMode.Create,
                         FileAccess.Write,
                         FileShare.None,
                         81920,
                         FileOptions.WriteThrough))
        {
            await JsonSerializer.SerializeAsync(writeStream, value, JsonDefaults.Options, cancellationToken);
            await writeStream.FlushAsync(cancellationToken);
        }

        File.Move(tempPath, path, true);
    }
}
