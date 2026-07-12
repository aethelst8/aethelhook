using System;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using MessageBox = System.Windows.MessageBox;

namespace AethelHook.Tray;

public class DeviceRow
{
    public string Id { get; set; } = "";
    public string Label { get; set; } = "";
    public string MaskedToken { get; set; } = "";
    public string PairedAt { get; set; } = "";
    public bool IsActive { get; set; }
    public string StatusText => IsActive ? "Active" : "History";
}

public class FeedRow
{
    public string Time { get; set; } = "";
    public string ToolName { get; set; } = "";
    public string CommandName { get; set; } = "";
    public string StatusText { get; set; } = "";
    public Brush StatusColor { get; set; } = Brushes.Gray;
}

public partial class MainWindow : Window
{
    private readonly AethelHookClient _client = App.Client;
    private readonly DispatcherTimer _statusTimer;
    private readonly DispatcherTimer _feedTimer;
    private bool _suppressToggleEvent;

    public ObservableCollection<DeviceRow> Devices { get; } = new();
    public ObservableCollection<FeedRow> Feed { get; } = new();

    public MainWindow()
    {
        InitializeComponent();
        DeviceList.ItemsSource = Devices;
        FeedList.ItemsSource = Feed;

        // Slow poll for connectivity/gateway state; faster poll for the live feed,
        // both only while this window is actually open (started in Loaded, not ctor).
        _statusTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
        _statusTimer.Tick += async (_, _) => await RefreshStatusAsync();

        _feedTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        _feedTimer.Tick += async (_, _) => await RefreshFeedAsync();

        // IsVisibleChanged (not Loaded) - Loaded only fires the very first time the
        // window is shown. Show()-ing a previously-hidden window (tray icon click
        // after the X button hid it) does not re-fire Loaded, which would otherwise
        // leave the timers stopped and the UI frozen on stale data.
        IsVisibleChanged += async (_, e) =>
        {
            if ((bool)e.NewValue)
            {
                _statusTimer.Start();
                _feedTimer.Start();
                await RefreshStatusAsync();
                await RefreshDevicesAsync();
                await RefreshFeedAsync();
            }
            else
            {
                _statusTimer.Stop();
                _feedTimer.Stop();
            }
        };

        // Closing the window (X button) just hides it - the app keeps running in the
        // tray. Only the tray menu's "Exit" actually shuts down.
        Closing += (_, e) =>
        {
            e.Cancel = true;
            Hide();
        };
    }

    private async Task RefreshStatusAsync()
    {
        var status = await _client.GetStatusAsync();
        if (status == null)
        {
            ApiStatusText.Text = "API: unreachable";
            PhoneStatusText.Text = "Phone: unknown";
            GatewayToggle.IsEnabled = false;
            return;
        }

        GatewayToggle.IsEnabled = true;
        ApiStatusText.Text = "API: online";
        PhoneStatusText.Text = status.WsConnected ? "Phone: connected" : "Phone: not connected";

        _suppressToggleEvent = true;
        GatewayToggle.IsChecked = status.GatewayActive;
        GatewayToggle.Content = status.GatewayActive ? "ON" : "OFF";
        _suppressToggleEvent = false;
    }

    private async Task RefreshDevicesAsync()
    {
        var devices = await _client.GetDevicesAsync();
        Devices.Clear();
        foreach (var d in devices.OrderByDescending(d => d.PairedAt))
        {
            Devices.Add(new DeviceRow
            {
                Id = d.Id,
                Label = d.Label,
                MaskedToken = d.Token.Length > 6 ? $"...{d.Token[^6..]}" : d.Token,
                PairedAt = d.PairedAt.ToLocalTime().ToString("g"),
                IsActive = d.IsActive
            });
        }
    }

    private async Task RefreshFeedAsync()
    {
        var feed = await _client.GetFeedAsync();
        Feed.Clear();
        foreach (var f in feed)
        {
            Feed.Add(new FeedRow
            {
                Time = f.CreatedAt.ToLocalTime().ToString("T"),
                ToolName = f.ToolName,
                CommandName = f.CommandName,
                StatusText = f.Decision ?? "pending",
                StatusColor = f.Decision switch
                {
                    null => Brushes.Goldenrod,
                    "allow" or "allow_once" or "always_allow_project" or "always_allow_global" => Brushes.SeaGreen,
                    _ => Brushes.IndianRed
                }
            });
        }
    }

    private async void GatewayToggle_Click(object sender, RoutedEventArgs e)
    {
        if (_suppressToggleEvent) return;
        var wantActive = GatewayToggle.IsChecked == true;
        GatewayToggle.IsEnabled = false;
        GatewayToggle.Content = wantActive ? "ON" : "OFF"; // optimistic - RefreshStatusAsync below confirms/corrects it
        await _client.SetGatewayActiveAsync(wantActive);
        await RefreshStatusAsync();
    }

    private async void Revoke_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { DataContext: DeviceRow row }) return;
        if (MessageBox.Show($"Revoke device '{row.Label}'?", "AethelHook", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes)
            return;

        await _client.RevokeDeviceAsync(row.Id);
        await RefreshDevicesAsync();
    }

    private async void PairNewDevice_Click(object sender, RoutedEventArgs e)
    {
        var hwnd = new System.Windows.Interop.WindowInteropHelper(this).Handle;
        var result = await WindowsHello.RequestAsync(hwnd, "Authorize a new device to connect to AethelHook");
        if (result == WindowsHello.HelloResult.DeniedOrFailed)
        {
            MessageBox.Show("Not authorized - pairing cancelled.", "AethelHook", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var win = new PairingWindow { Owner = this };
        win.ShowDialog();
        _ = RefreshDevicesAsync();
    }
}
