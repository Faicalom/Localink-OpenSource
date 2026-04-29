using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using LocalBridge.Desktop.Core.Mvvm;
using LocalBridge.Desktop.Models;
using LocalBridge.Desktop.Services;
using Microsoft.Win32;

namespace LocalBridge.Desktop.Features.Transfers;

public sealed class TransfersFeatureViewModel : ObservableObject
{
    private readonly IFileTransferService _fileTransferService;
    private readonly IConnectionService _connectionService;
    private string _transferHubTitle = "No transfer session";
    private string _transferHubStatus = "Connect to a local peer to start sending or receiving files.";
    private bool _canSelectFiles;
    private bool _isLoaded;

    public TransfersFeatureViewModel(IFileTransferService fileTransferService, IConnectionService connectionService)
    {
        _fileTransferService = fileTransferService;
        _connectionService = connectionService;

        Transfers = [];
        SelectFilesCommand = new AsyncRelayCommand(_ => SelectFilesAsync(), _ => CanSelectFiles);
        SelectFolderCommand = new AsyncRelayCommand(_ => SelectFolderAsync(), _ => CanSelectFiles);
        PauseTransferCommand = new AsyncRelayCommand(
            parameter => PauseTransferAsync(parameter as TransferItem),
            parameter => parameter is TransferItem);
        ResumeTransferCommand = new AsyncRelayCommand(
            parameter => ResumeTransferAsync(parameter as TransferItem),
            parameter => parameter is TransferItem);
        CancelTransferCommand = new AsyncRelayCommand(
            parameter => CancelTransferAsync(parameter as TransferItem),
            parameter => parameter is TransferItem);
        OpenTransferCommand = new AsyncRelayCommand(
            parameter => OpenTransferAsync(parameter as TransferItem),
            parameter => parameter is TransferItem);
    }

    public ObservableCollection<TransferItem> Transfers { get; }

    public AsyncRelayCommand SelectFilesCommand { get; }

    public AsyncRelayCommand SelectFolderCommand { get; }

    public AsyncRelayCommand PauseTransferCommand { get; }

    public AsyncRelayCommand ResumeTransferCommand { get; }

    public AsyncRelayCommand CancelTransferCommand { get; }

    public AsyncRelayCommand OpenTransferCommand { get; }

    public string TransferHubTitle
    {
        get => _transferHubTitle;
        private set => SetProperty(ref _transferHubTitle, value);
    }

    public string TransferHubStatus
    {
        get => _transferHubStatus;
        private set => SetProperty(ref _transferHubStatus, value);
    }

    public bool CanSelectFiles
    {
        get => _canSelectFiles;
        private set
        {
            if (SetProperty(ref _canSelectFiles, value))
            {
                SelectFilesCommand.RaiseCanExecuteChanged();
                SelectFolderCommand.RaiseCanExecuteChanged();
            }
        }
    }

    public async Task LoadAsync()
    {
        if (!_isLoaded)
        {
            await _fileTransferService.InitializeAsync();
            _fileTransferService.TransferAdded += HandleTransferAdded;
            _connectionService.SessionChanged += HandleSessionChanged;
            _isLoaded = true;
        }

        var transfers = await _fileTransferService.GetTransfersAsync();

        await DispatchAsync(() =>
        {
            Transfers.Clear();
            foreach (var transfer in transfers)
            {
                Transfers.Add(transfer);
            }
        });

        await ApplySessionAsync(await _connectionService.GetActiveSessionAsync());
    }

    public async Task ShutdownAsync()
    {
        if (!_isLoaded)
        {
            return;
        }

        _fileTransferService.TransferAdded -= HandleTransferAdded;
        _connectionService.SessionChanged -= HandleSessionChanged;
        await _fileTransferService.ShutdownAsync();
        _isLoaded = false;
    }

    private async Task SelectFilesAsync()
    {
        var dialog = new OpenFileDialog
        {
            Multiselect = true,
            Title = "Select files to send",
            Filter = "Supported files|*.png;*.jpg;*.jpeg;*.gif;*.bmp;*.webp;*.pdf;*.txt;*.json;*.csv;*.mp4;*.mov;*.avi;*.mkv;*.webm;*.*|Images|*.png;*.jpg;*.jpeg;*.gif;*.bmp;*.webp|PDF files|*.pdf|Text files|*.txt;*.json;*.csv|Video files|*.mp4;*.mov;*.avi;*.mkv;*.webm|All files|*.*"
        };

        if (dialog.ShowDialog() == true)
        {
            await _fileTransferService.QueueFilesAsync(dialog.FileNames);
        }
    }

    private async Task SelectFolderAsync()
    {
        var dialog = new OpenFolderDialog
        {
            Title = "Select a folder and Localink will queue every file inside it.",
            Multiselect = false
        };

        if (dialog.ShowDialog() != true || string.IsNullOrWhiteSpace(dialog.FolderName))
        {
            return;
        }

        string[] filePaths;
        try
        {
            filePaths = Directory.EnumerateFiles(dialog.FolderName, "*", SearchOption.AllDirectories)
                .OrderBy(path => path, StringComparer.OrdinalIgnoreCase)
                .ToArray();
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                $"Could not read the selected folder.\n\n{ex.Message}",
                "Localink",
                MessageBoxButton.OK,
                MessageBoxImage.Warning);
            return;
        }

        if (filePaths.Length == 0)
        {
            MessageBox.Show(
                "The selected folder does not contain any files that can be queued.",
                "Localink",
                MessageBoxButton.OK,
                MessageBoxImage.Information);
            return;
        }

        await _fileTransferService.QueueFilesAsync(filePaths);
    }

    private Task PauseTransferAsync(TransferItem? transfer)
    {
        return transfer is null
            ? Task.CompletedTask
            : _fileTransferService.PauseTransferAsync(transfer);
    }

    private Task ResumeTransferAsync(TransferItem? transfer)
    {
        return transfer is null
            ? Task.CompletedTask
            : _fileTransferService.ResumeTransferAsync(transfer);
    }

    private Task CancelTransferAsync(TransferItem? transfer)
    {
        return transfer is null
            ? Task.CompletedTask
            : _fileTransferService.CancelTransferAsync(transfer);
    }

    private Task OpenTransferAsync(TransferItem? transfer)
    {
        return transfer is null
            ? Task.CompletedTask
            : _fileTransferService.OpenTransferAsync(transfer);
    }

    public async Task<int> ClearHistoryAsync()
    {
        var removedCount = await _fileTransferService.ClearHistoryAsync();
        var transfers = await _fileTransferService.GetTransfersAsync();

        await DispatchAsync(() =>
        {
            Transfers.Clear();
            foreach (var transfer in transfers)
            {
                Transfers.Add(transfer);
            }
        });

        return removedCount;
    }

    private void HandleTransferAdded(TransferItem transfer)
    {
        _ = DispatchAsync(() =>
        {
            var existing = Transfers.FirstOrDefault(item => string.Equals(item.Id, transfer.Id, StringComparison.OrdinalIgnoreCase));
            if (existing is not null)
            {
                return;
            }

            Transfers.Insert(0, transfer);
        });
    }

    private void HandleSessionChanged(ConnectionSessionSnapshot? session)
    {
        _ = ApplySessionAsync(session);
    }

    private Task ApplySessionAsync(ConnectionSessionSnapshot? session)
    {
        return DispatchAsync(() =>
        {
            if (session is null || !session.IsConnected)
            {
                CanSelectFiles = false;
                TransferHubTitle = "No transfer session";
                TransferHubStatus = "Connect to a local peer to start sending files and receive incoming transfers.";
                return;
            }

            TransferHubTitle = session.Peer.DisplayName;
            if (session.TransportMode == AppConnectionMode.BluetoothFallback)
            {
                CanSelectFiles = true;
                TransferHubStatus = $"Connected over Bluetooth RFCOMM with {session.Peer.DisplayName}. Small files up to 300 MB are supported here. Use Local Wi-Fi / Hotspot for larger transfers and the best speed.";
                return;
            }

            CanSelectFiles = true;
            TransferHubStatus = $"Connected over local Wi-Fi / Hotspot with {session.Peer.EndpointLabel}. File transfers use a sequential queue on the same local session, so multiple files or an entire folder can be sent one item at a time with support for files up to 20 GB per transfer in this build.";
        });
    }

    private static Task DispatchAsync(Action action)
    {
        var dispatcher = Application.Current?.Dispatcher;

        if (dispatcher is null || dispatcher.CheckAccess())
        {
            action();
            return Task.CompletedTask;
        }

        return dispatcher.InvokeAsync(action).Task;
    }
}
