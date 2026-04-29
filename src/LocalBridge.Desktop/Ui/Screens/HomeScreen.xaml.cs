using System.Windows.Controls;
using System.Windows.Input;

namespace LocalBridge.Desktop.Ui.Screens;

public partial class HomeScreen : UserControl
{
    public HomeScreen()
    {
        InitializeComponent();
    }

    private void HandleChatViewportMouseWheel(object sender, MouseWheelEventArgs e)
    {
        if (ChatTranscriptView.ScrollByMouseWheel(e.Delta))
        {
            e.Handled = true;
        }
    }

}
