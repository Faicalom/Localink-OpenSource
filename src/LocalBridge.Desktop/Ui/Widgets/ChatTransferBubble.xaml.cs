using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace LocalBridge.Desktop.Ui.Widgets;

public partial class ChatTransferBubble : UserControl
{
    public static readonly DependencyProperty OpenTransferCommandProperty =
        DependencyProperty.Register(
            nameof(OpenTransferCommand),
            typeof(ICommand),
            typeof(ChatTransferBubble),
            new PropertyMetadata(null));

    public ChatTransferBubble()
    {
        InitializeComponent();
    }

    public ICommand? OpenTransferCommand
    {
        get => (ICommand?)GetValue(OpenTransferCommandProperty);
        set => SetValue(OpenTransferCommandProperty, value);
    }
}
