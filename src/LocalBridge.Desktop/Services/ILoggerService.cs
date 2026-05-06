using System.Collections.ObjectModel;

namespace LocalBridge.Desktop.Services;

public interface ILoggerService
{
    ObservableCollection<string> Entries { get; }

    string LogFilePath { get; }

    Task LogInfoAsync(string message, CancellationToken cancellationToken = default);

    Task LogWarningAsync(string message, CancellationToken cancellationToken = default);

    Task LogErrorAsync(string message, CancellationToken cancellationToken = default);

    Task ClearAsync(CancellationToken cancellationToken = default);
}
