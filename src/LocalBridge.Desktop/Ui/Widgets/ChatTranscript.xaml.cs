using System.Collections;
using System.Collections.Specialized;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;

namespace LocalBridge.Desktop.Ui.Widgets;

public partial class ChatTranscript : UserControl
{
    public static readonly DependencyProperty MessagesSourceProperty =
        DependencyProperty.Register(
            nameof(MessagesSource),
            typeof(IEnumerable),
            typeof(ChatTranscript),
            new PropertyMetadata(null, OnMessagesSourceChanged));

    public static readonly DependencyProperty RetryMessageCommandProperty =
        DependencyProperty.Register(
            nameof(RetryMessageCommand),
            typeof(ICommand),
            typeof(ChatTranscript),
            new PropertyMetadata(null));

    public static readonly DependencyProperty OpenTransferCommandProperty =
        DependencyProperty.Register(
            nameof(OpenTransferCommand),
            typeof(ICommand),
            typeof(ChatTranscript),
            new PropertyMetadata(null));

    private INotifyCollectionChanged? _observableMessages;
    public ChatTranscript()
    {
        InitializeComponent();
        Loaded += (_, _) =>
        {
            UpdateEmptyState();
            ScrollToLatestMessage();
        };
    }

    public IEnumerable? MessagesSource
    {
        get => (IEnumerable?)GetValue(MessagesSourceProperty);
        set => SetValue(MessagesSourceProperty, value);
    }

    public ICommand? RetryMessageCommand
    {
        get => (ICommand?)GetValue(RetryMessageCommandProperty);
        set => SetValue(RetryMessageCommandProperty, value);
    }

    public ICommand? OpenTransferCommand
    {
        get => (ICommand?)GetValue(OpenTransferCommandProperty);
        set => SetValue(OpenTransferCommandProperty, value);
    }

    private static void OnMessagesSourceChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        var control = (ChatTranscript)d;

        if (control._observableMessages is not null)
        {
            control._observableMessages.CollectionChanged -= control.HandleMessagesChanged;
        }

        control._observableMessages = e.NewValue as INotifyCollectionChanged;
        if (control._observableMessages is not null)
        {
            control._observableMessages.CollectionChanged += control.HandleMessagesChanged;
        }

        control.UpdateEmptyState();
        control.ScrollToLatestMessage();
    }

    private void HandleMessagesChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        UpdateEmptyState();
        ScrollToLatestMessage();
    }

    private void UpdateEmptyState()
    {
        var hasItems = MessagesItems.Items.Count > 0;
        EmptyStateText.Visibility = hasItems ? Visibility.Collapsed : Visibility.Visible;
    }

    private void ScrollToLatestMessage()
    {
        if (MessagesItems.Items.Count == 0)
        {
            return;
        }

        Dispatcher.BeginInvoke(() =>
        {
            MessagesScrollViewer.ScrollToEnd();
        }, DispatcherPriority.Background);
    }

    private void HandlePreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        if (!ScrollByMouseWheel(e.Delta))
        {
            return;
        }
        e.Handled = true;
    }

    public bool ScrollByMouseWheel(int delta)
    {
        if (MessagesScrollViewer.ScrollableHeight <= 0)
        {
            return false;
        }

        var targetOffset = MessagesScrollViewer.VerticalOffset - (delta / 2.5d);
        targetOffset = Math.Max(0, Math.Min(targetOffset, MessagesScrollViewer.ScrollableHeight));
        MessagesScrollViewer.ScrollToVerticalOffset(targetOffset);
        return true;
    }
}
