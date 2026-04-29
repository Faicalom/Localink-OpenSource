using System.IO;
using System.Text.Json.Serialization;
using LocalBridge.Desktop.Core.Mvvm;

namespace LocalBridge.Desktop.Models;

public sealed class TransferItem : ObservableObject
{
    private string _id = Guid.NewGuid().ToString("N");
    private string _fileName = string.Empty;
    private string _peerId = string.Empty;
    private string _peerName = string.Empty;
    private TransferDirection _direction = TransferDirection.Outgoing;
    private string _kind = "file";
    private string _mimeType = "application/octet-stream";
    private long _totalBytes;
    private long _transferredBytes;
    private TransferState _status = TransferState.Queued;
    private DateTimeOffset _createdAtUtc = DateTimeOffset.UtcNow;
    private DateTimeOffset _fileCreatedAtUtc = DateTimeOffset.UtcNow;
    private DateTimeOffset? _startedAtUtc;
    private DateTimeOffset? _completedAtUtc;
    private double _speedBytesPerSecond;
    private double _estimatedSecondsRemaining;
    private string _sourcePath = string.Empty;
    private string _savedFilePath = string.Empty;
    private string _lastError = string.Empty;
    private int _chunkSize;
    private int _totalChunks;
    private int _processedChunks;

    public string Id
    {
        get => _id;
        set => SetProperty(ref _id, value);
    }

    public string FileName
    {
        get => _fileName;
        set
        {
            if (SetProperty(ref _fileName, value))
            {
                RaisePropertyChanged(nameof(PreviewFallbackLabel));
            }
        }
    }

    public string PeerId
    {
        get => _peerId;
        set => SetProperty(ref _peerId, value);
    }

    public string PeerName
    {
        get => _peerName;
        set => SetProperty(ref _peerName, value);
    }

    [JsonConverter(typeof(JsonStringEnumConverter))]
    public TransferDirection Direction
    {
        get => _direction;
        set
        {
            if (SetProperty(ref _direction, value))
            {
                RaisePropertyChanged(nameof(DirectionLabel));
            }
        }
    }

    public string Kind
    {
        get => _kind;
        set
        {
            if (SetProperty(ref _kind, value))
            {
                RaisePropertyChanged(nameof(IsImagePreviewAvailable));
                RaisePropertyChanged(nameof(PreviewFallbackLabel));
            }
        }
    }

    public string MimeType
    {
        get => _mimeType;
        set
        {
            if (SetProperty(ref _mimeType, value))
            {
                RaisePropertyChanged(nameof(IsImagePreviewAvailable));
                RaisePropertyChanged(nameof(PreviewFallbackLabel));
            }
        }
    }

    public long TotalBytes
    {
        get => _totalBytes;
        set
        {
            if (SetProperty(ref _totalBytes, value))
            {
                RaisePropertyChanged(nameof(ProgressPercentage));
                RaisePropertyChanged(nameof(SizeLabel));
                RaisePropertyChanged(nameof(EtaLabel));
                RaisePropertyChanged(nameof(TransferTelemetryLabel));
            }
        }
    }

    public long TransferredBytes
    {
        get => _transferredBytes;
        set
        {
            if (SetProperty(ref _transferredBytes, value))
            {
                RaisePropertyChanged(nameof(ProgressPercentage));
                RaisePropertyChanged(nameof(SizeLabel));
                RaisePropertyChanged(nameof(EtaLabel));
                RaisePropertyChanged(nameof(TransferTelemetryLabel));
            }
        }
    }

    [JsonConverter(typeof(JsonStringEnumConverter))]
    public TransferState Status
    {
        get => _status;
        set
        {
            if (SetProperty(ref _status, value))
            {
                RaisePropertyChanged(nameof(StatusLabel));
                RaisePropertyChanged(nameof(CanOpen));
                RaisePropertyChanged(nameof(CanPause));
                RaisePropertyChanged(nameof(CanResume));
                RaisePropertyChanged(nameof(CanCancel));
                RaisePropertyChanged(nameof(IsImagePreviewAvailable));
                RaisePropertyChanged(nameof(TransferTelemetryLabel));
            }
        }
    }

    public DateTimeOffset CreatedAtUtc
    {
        get => _createdAtUtc;
        set
        {
            if (SetProperty(ref _createdAtUtc, value))
            {
                RaisePropertyChanged(nameof(CreatedAtLabel));
            }
        }
    }

    public DateTimeOffset FileCreatedAtUtc
    {
        get => _fileCreatedAtUtc;
        set => SetProperty(ref _fileCreatedAtUtc, value);
    }

    public DateTimeOffset? StartedAtUtc
    {
        get => _startedAtUtc;
        set => SetProperty(ref _startedAtUtc, value);
    }

    public DateTimeOffset? CompletedAtUtc
    {
        get => _completedAtUtc;
        set => SetProperty(ref _completedAtUtc, value);
    }

    public double SpeedBytesPerSecond
    {
        get => _speedBytesPerSecond;
        set
        {
            if (SetProperty(ref _speedBytesPerSecond, value))
            {
                RaisePropertyChanged(nameof(SpeedLabel));
                RaisePropertyChanged(nameof(TransferRateLabel));
                RaisePropertyChanged(nameof(TransferTelemetryLabel));
            }
        }
    }

    public double EstimatedSecondsRemaining
    {
        get => _estimatedSecondsRemaining;
        set
        {
            if (SetProperty(ref _estimatedSecondsRemaining, value))
            {
                RaisePropertyChanged(nameof(EtaLabel));
                RaisePropertyChanged(nameof(TransferTelemetryLabel));
            }
        }
    }

    public string SourcePath
    {
        get => _sourcePath;
        set
        {
            if (SetProperty(ref _sourcePath, value))
            {
                RaisePropertyChanged(nameof(CanOpen));
                RaisePropertyChanged(nameof(OpenPath));
                RaisePropertyChanged(nameof(PreviewPath));
                RaisePropertyChanged(nameof(IsImagePreviewAvailable));
            }
        }
    }

    public string SavedFilePath
    {
        get => _savedFilePath;
        set
        {
            if (SetProperty(ref _savedFilePath, value))
            {
                RaisePropertyChanged(nameof(CanOpen));
                RaisePropertyChanged(nameof(OpenPath));
                RaisePropertyChanged(nameof(PreviewPath));
                RaisePropertyChanged(nameof(IsImagePreviewAvailable));
            }
        }
    }

    public string LastError
    {
        get => _lastError;
        set => SetProperty(ref _lastError, value);
    }

    public int ChunkSize
    {
        get => _chunkSize;
        set => SetProperty(ref _chunkSize, value);
    }

    public int TotalChunks
    {
        get => _totalChunks;
        set => SetProperty(ref _totalChunks, value);
    }

    public int ProcessedChunks
    {
        get => _processedChunks;
        set => SetProperty(ref _processedChunks, value);
    }

    [JsonIgnore]
    public double ProgressPercentage => TotalBytes <= 0
        ? 0
        : Math.Clamp((double)TransferredBytes / TotalBytes * 100d, 0d, 100d);

    [JsonIgnore]
    public string DirectionLabel => Direction == TransferDirection.Outgoing ? "Outgoing" : "Incoming";

    [JsonIgnore]
    public string CreatedAtLabel => CreatedAtUtc.ToLocalTime().ToString("MMM dd, HH:mm");

    [JsonIgnore]
    public string SizeLabel => $"{FormatBytes(TransferredBytes)} / {FormatBytes(TotalBytes)}";

    [JsonIgnore]
    public string SpeedLabel => SpeedBytesPerSecond <= 0.1
        ? "Speed: --"
        : $"Speed: {FormatBytes((long)SpeedBytesPerSecond)}/s";

    [JsonIgnore]
    public string TransferRateLabel => SpeedBytesPerSecond <= 0.1
        ? "--"
        : $"{FormatBytes((long)SpeedBytesPerSecond)}/s";

    [JsonIgnore]
    public string EtaLabel => EstimatedSecondsRemaining <= 0.1 || Status is TransferState.Completed or TransferState.Canceled
        ? "ETA: --"
        : $"ETA: {TimeSpan.FromSeconds(EstimatedSecondsRemaining):mm\\:ss}";

    [JsonIgnore]
    public string TransferTelemetryLabel
    {
        get
        {
            var progressLabel = $"{ProgressPercentage:0.#}% complete";

            return Status switch
            {
                TransferState.Completed => $"{progressLabel} • Finished",
                TransferState.Failed => $"{progressLabel} • Failed",
                TransferState.Canceled => $"{progressLabel} • Canceled",
                _ when EstimatedSecondsRemaining > 0.1 => $"{progressLabel} • {FormatEta(EstimatedSecondsRemaining)} left",
                _ => progressLabel
            };
        }
    }

    [JsonIgnore]
    public string StatusLabel => Status switch
    {
        TransferState.Queued => "Queued",
        TransferState.Preparing => "Preparing",
        TransferState.Sending => "Sending",
        TransferState.Receiving => "Receiving",
        TransferState.Paused => "Paused",
        TransferState.Completed => "Completed",
        TransferState.Failed => "Failed",
        TransferState.Canceled => "Canceled",
        _ => "Unknown"
    };

    [JsonIgnore]
    public string OpenPath => !string.IsNullOrWhiteSpace(SavedFilePath) ? SavedFilePath : SourcePath;

    [JsonIgnore]
    public bool CanOpen => Status == TransferState.Completed && !string.IsNullOrWhiteSpace(OpenPath);

    [JsonIgnore]
    public string PreviewPath => OpenPath;

    [JsonIgnore]
    public bool IsImagePreviewAvailable =>
        !string.IsNullOrWhiteSpace(PreviewPath) &&
        File.Exists(PreviewPath) &&
        (LooksLikeImageMimeType(MimeType) || LooksLikeImagePath(PreviewPath) || string.Equals(Kind, "image", StringComparison.OrdinalIgnoreCase));

    [JsonIgnore]
    public string PreviewFallbackLabel => Kind switch
    {
        "image" => "Image preview unavailable",
        "video" => "Video file",
        "document" => "PDF document",
        "text" => "Text file",
        _ => ResolveGenericFileLabel()
    };

    [JsonIgnore]
    public bool CanPause => Direction == TransferDirection.Outgoing && Status is TransferState.Queued or TransferState.Preparing or TransferState.Sending;

    [JsonIgnore]
    public bool CanResume => Direction == TransferDirection.Outgoing && Status == TransferState.Paused;

    [JsonIgnore]
    public bool CanCancel => Status is TransferState.Queued or TransferState.Preparing or TransferState.Sending or TransferState.Receiving or TransferState.Paused;

    private static string FormatBytes(long bytes)
    {
        var value = (double)Math.Max(bytes, 0);
        var units = new[] { "B", "KB", "MB", "GB" };
        var unitIndex = 0;

        while (value >= 1024 && unitIndex < units.Length - 1)
        {
            value /= 1024;
            unitIndex++;
        }

        return $"{value:0.#} {units[unitIndex]}";
    }

    private static bool LooksLikeImageMimeType(string mimeType)
    {
        return !string.IsNullOrWhiteSpace(mimeType) &&
               mimeType.StartsWith("image/", StringComparison.OrdinalIgnoreCase);
    }

    private static bool LooksLikeImagePath(string path)
    {
        var extension = Path.GetExtension(path);
        return extension.Equals(".png", StringComparison.OrdinalIgnoreCase) ||
               extension.Equals(".jpg", StringComparison.OrdinalIgnoreCase) ||
               extension.Equals(".jpeg", StringComparison.OrdinalIgnoreCase) ||
               extension.Equals(".gif", StringComparison.OrdinalIgnoreCase) ||
               extension.Equals(".bmp", StringComparison.OrdinalIgnoreCase) ||
               extension.Equals(".webp", StringComparison.OrdinalIgnoreCase);
    }

    private string ResolveGenericFileLabel()
    {
        if (string.IsNullOrWhiteSpace(FileName))
        {
            return "Generic file";
        }

        var extension = Path.GetExtension(FileName).TrimStart('.').ToUpperInvariant();
        return string.IsNullOrWhiteSpace(extension) ? "Generic file" : $"{extension} file";
    }

    private static string FormatEta(double secondsRemaining)
    {
        var eta = TimeSpan.FromSeconds(secondsRemaining);

        if (eta.TotalHours >= 1)
        {
            return eta.ToString(@"hh\:mm\:ss");
        }

        return eta.ToString(@"mm\:ss");
    }
}
