using System.Windows;
using System.Windows.Threading;
using Button = System.Windows.Controls.Button;
using Clipboard = System.Windows.Clipboard;

namespace AethelHook.Tray;

public partial class PowerShellCommandsWindow : Window
{
    public PowerShellCommandsWindow()
    {
        InitializeComponent();
    }

    private void Copy_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: string command } button) return;

        Clipboard.SetText(command);

        var original = button.Content;
        button.Content = "Copied!";
        var timer = new DispatcherTimer { Interval = System.TimeSpan.FromSeconds(1.2) };
        timer.Tick += (_, _) =>
        {
            button.Content = original;
            timer.Stop();
        };
        timer.Start();
    }
}
