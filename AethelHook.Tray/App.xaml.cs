using System.Windows;
using System.Windows.Controls;
using H.NotifyIcon;

namespace AethelHook.Tray;

public partial class App : System.Windows.Application
{
    // Shared across the app so windows don't each carry their own token/HttpClient.
    public static AethelHookClient Client { get; } = new();

    private Mutex? _singleInstanceMutex;
    private TaskbarIcon? _trayIcon;
    private MainWindow? _mainWindow;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // Only one tray icon per machine — a second launch (e.g. manual double-click
        // after the Startup-folder shortcut already ran) should just no-op.
        _singleInstanceMutex = new Mutex(initiallyOwned: true, "Global\\AethelHook.Tray.SingleInstance", out var isNew);
        if (!isNew)
        {
            Shutdown();
            return;
        }

        _trayIcon = (TaskbarIcon)FindResource("TrayIcon");
        // Loaded via GDI+ from the exe's own embedded icon (ApplicationIcon), not
        // WPF's pack-URI BitmapDecoder — WPF's built-in ICO decoder chokes on some
        // frames of this particular multi-size .ico; GDI+ handles it fine.
        _trayIcon.Icon = System.Drawing.Icon.ExtractAssociatedIcon(System.Reflection.Assembly.GetExecutingAssembly().Location);
        _trayIcon.ForceCreate();
        _trayIcon.TrayLeftMouseUp += (_, _) => ShowMainWindow();

        var menu = new ContextMenu();
        var openItem = new MenuItem { Header = "Open AethelHook" };
        openItem.Click += (_, _) => ShowMainWindow();
        var exitItem = new MenuItem { Header = "Exit" };
        exitItem.Click += (_, _) => Shutdown();
        menu.Items.Add(openItem);
        menu.Items.Add(new Separator());
        menu.Items.Add(exitItem);
        _trayIcon.ContextMenu = menu;
    }

    public void ShowMainWindow()
    {
        if (_mainWindow == null)
            _mainWindow = new MainWindow();

        _mainWindow.Show();
        _mainWindow.WindowState = WindowState.Normal;
        _mainWindow.Activate();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _trayIcon?.Dispose();
        if (_singleInstanceMutex != null)
        {
            try { _singleInstanceMutex.ReleaseMutex(); } catch (ApplicationException) { /* not owned — already released */ }
            _singleInstanceMutex.Dispose();
        }
        base.OnExit(e);
    }
}
