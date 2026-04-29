using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Ui.Widgets;

public partial class ChatMessageBubble : UserControl
{
    public static readonly DependencyProperty RetryMessageCommandProperty =
        DependencyProperty.Register(
            nameof(RetryMessageCommand),
            typeof(ICommand),
            typeof(ChatMessageBubble),
            new PropertyMetadata(null));

    public ChatMessageBubble()
    {
        InitializeComponent();
    }

    public ICommand? RetryMessageCommand
    {
        get => (ICommand?)GetValue(RetryMessageCommandProperty);
        set => SetValue(RetryMessageCommandProperty, value);
    }

    private void BubbleBorder_OnMouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (sender is not FrameworkElement element || !TryGetMessageText(element.DataContext, out _))
        {
            return;
        }

        if (element.ContextMenu is null)
        {
            return;
        }

        element.ContextMenu.PlacementTarget = element;
        element.ContextMenu.IsOpen = true;
        e.Handled = true;
    }

    private void CopyMenuItem_OnClick(object sender, RoutedEventArgs e)
    {
        if (sender is not MenuItem { CommandParameter: ChatMessage message } || string.IsNullOrWhiteSpace(message.Text))
        {
            return;
        }

        Clipboard.SetText(message.Text);
    }

    private static bool TryGetMessageText(object? dataContext, out string text)
    {
        if (dataContext is ChatMessage message && !string.IsNullOrWhiteSpace(message.Text))
        {
            text = message.Text;
            return true;
        }

        text = string.Empty;
        return false;
    }
}
