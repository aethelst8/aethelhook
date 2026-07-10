using System;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace AethelHook.Tray;

// Reimplements the same 3-call flow the loopback-only /pair browser page uses
// (POST /pair/session -> GET /pair/qr.png -> poll GET /pair/status), natively.
public partial class PairingWindow : Window
{
    private readonly AethelHookClient _client = App.Client;
    private readonly DispatcherTimer _pollTimer;
    private string? _sid;

    public PairingWindow()
    {
        InitializeComponent();

        _pollTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.5) };
        _pollTimer.Tick += async (_, _) => await PollStatusAsync();

        Loaded += async (_, _) => await StartNewSessionAsync();
        Closing += (_, _) => _pollTimer.Stop();
    }

    private async Task StartNewSessionAsync()
    {
        StatusText.Text = "Generating QR...";
        var session = await _client.CreatePairingSessionAsync();
        if (session == null)
        {
            StatusText.Text = "Could not reach AethelHook API.";
            return;
        }

        _sid = session.Sid;
        var png = await _client.GetPairingQrAsync(_sid);
        if (png == null)
        {
            StatusText.Text = "Could not generate QR.";
            return;
        }

        var image = new BitmapImage();
        using (var stream = new MemoryStream(png))
        {
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = stream;
            image.EndInit();
        }
        image.Freeze();
        QrImage.Source = image;

        StatusText.Text = "Waiting for scan...";
        _pollTimer.Start();
    }

    private async Task PollStatusAsync()
    {
        if (_sid == null) return;
        var status = await _client.GetPairingStatusAsync(_sid);
        switch (status)
        {
            case "claimed":
                _pollTimer.Stop();
                StatusText.Text = "✅ Paired!";
                await Task.Delay(1200);
                Close();
                break;
            case "expired":
            case "not_found":
                _pollTimer.Stop();
                await StartNewSessionAsync();
                break;
        }
    }
}
