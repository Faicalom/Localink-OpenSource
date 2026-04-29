using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using LocalBridge.Desktop.Core;

namespace LocalBridge.Desktop.Services;

public sealed class LoggerService : ILoggerService
{
    private readonly SemaphoreSlim _fileGate = new(1, 1);

    public LoggerService()
    {
        var root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            AppConstants.AppName,
            "Desktop");

        Directory.CreateDirectory(root);
        LogFilePath = Path.Combine(root, AppConstants.DefaultLogFileName);
    }

    public ObservableCollection<string> Entries { get; } = [];

    public string LogFilePath { get; }

    public Task LogInfoAsync(string message, CancellationToken cancellationToken = default)
    {
        return AddEntryAsync("INFO", message, cancellationToken);
    }

    public Task LogWarningAsync(string message, CancellationToken cancellationToken = default)
    {
        return AddEntryAsync("WARN", message, cancellationToken);
    }

    public Task LogErrorAsync(string message, CancellationToken cancellationToken = default)
    {
        return AddEntryAsync("ERROR", message, cancellationToken);
    }

    public async Task ClearAsync(CancellationToken cancellationToken = default)
    {
        await _fileGate.WaitAsync(cancellationToken);
        try
        {
            await File.WriteAllTextAsync(LogFilePath, string.Empty, cancellationToken);
        }
        finally
        {
            _fileGate.Release();
        }

        var dispatcher = Application.Current?.Dispatcher;

        if (dispatcher is null || dispatcher.CheckAccess())
        {
            Entries.Clear();
            return;
        }

        await dispatcher.InvokeAsync(Entries.Clear).Task;
    }

    private async Task AddEntryAsync(string level, string message, CancellationToken cancellationToken)
    {
        var line = $"[{DateTime.Now:HH:mm:ss}] {level}  {message}";

        await AppendToFileAsync(line, cancellationToken);

        var dispatcher = Application.Current?.Dispatcher;

        if (dispatcher is null || dispatcher.CheckAccess())
        {
            AddEntry(line);
            return;
        }

        await dispatcher.InvokeAsync(() => AddEntry(line)).Task;
    }

    private async Task AppendToFileAsync(string line, CancellationToken cancellationToken)
    {
        await _fileGate.WaitAsync(cancellationToken);
        try
        {
            await File.AppendAllTextAsync(LogFilePath, line + Environment.NewLine, cancellationToken);
        }
        finally
        {
            _fileGate.Release();
        }
    }

    private void AddEntry(string line)
    {
        Entries.Insert(0, line);

        while (Entries.Count > 120)
        {
            Entries.RemoveAt(Entries.Count - 1);
        }
    }
}
