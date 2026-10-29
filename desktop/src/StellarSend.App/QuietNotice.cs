using System.Reflection;
using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;

namespace StellarSend.App;

/// <summary>
/// A file transfer notice. It updates the tray tip and shows a balloon.
/// It does not call Activate and it does not show the main window.
/// </summary>
public static class QuietNotice
{
    private const string TrayTip = "FlipSend";
    private static DispatcherTimer? _tooltipTimer;
    private static Window? _toast;

    public static void Show(string text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return;
        }

        if (Dispatcher.UIThread.CheckAccess())
        {
            ShowCore(text.Trim());
            return;
        }

        var message = text.Trim();
        Dispatcher.UIThread.Post(() => ShowCore(message));
    }

    private static void ShowCore(string text)
    {
        if (text.Length > 200)
        {
            text = text[..197] + "...";
        }

        ArmTip(text);
        if (!TrayBalloon.TryShow(text))
        {
            ShowToast(text);
        }
    }

    private static void ArmTip(string text)
    {
        if (App.Tray is not null)
        {
            App.Tray.ToolTipText = text;
        }

        _tooltipTimer?.Stop();
        _tooltipTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(8) };
        _tooltipTimer.Tick += (_, _) =>
        {
            _tooltipTimer?.Stop();
            if (App.Tray is not null)
            {
                App.Tray.ToolTipText = TrayTip;
            }
        };
        _tooltipTimer.Start();
    }

    private static void ShowToast(string text)
    {
        _toast?.Close();
        var window = new Window
        {
            Width = 360,
            SizeToContent = SizeToContent.Height,
            CanResize = false,
            Focusable = false,
            ShowInTaskbar = false,
            SystemDecorations = SystemDecorations.None,
            Topmost = true,
            ShowActivated = false,
            Background = Brushes.Transparent,
            WindowStartupLocation = WindowStartupLocation.Manual,
            Content = new Border
            {
                Background = Brush.Parse("#16141F"),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(16),
                Child = new TextBlock
                {
                    Text = text,
                    TextWrapping = TextWrapping.Wrap,
                    Foreground = Brush.Parse("#F6F1E7"),
                    FontSize = 16
                }
            }
        };
        window.Opened += (_, _) => KeepInactive(window);
        var area = App.Window?.Screens?.Primary?.WorkingArea;
        if (area is { } work)
        {
            window.Position = new PixelPoint(work.X + work.Width - 380, work.Y + work.Height - 140);
        }

        _toast = window;
        window.Show();
        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            if (ReferenceEquals(_toast, window))
            {
                _toast = null;
            }

            window.Close();
        };
        timer.Start();
    }

    private static void KeepInactive(Window window)
    {
        if (!OperatingSystem.IsWindows())
        {
            return;
        }

        var handle = window.TryGetPlatformHandle()?.Handle ?? IntPtr.Zero;
        if (handle == IntPtr.Zero)
        {
            return;
        }

        TrayBalloon.KeepInactive(handle);
    }
}

internal static class TrayBalloon
{
    private const uint NimModify = 0x00000001;
    private const uint NifInfo = 0x00000010;
    private const uint NiifInfo = 0x00000001;
    private const uint NiifNoSound = 0x00000010;
    private const uint NiifRespectQuietTime = 0x00000080;
    private const int GwlExStyle = -20;
    private const int WsExNoActivate = 0x08000000;
    private const int WsExToolWindow = 0x00000080;
    private const int SwShowNoActivate = 4;

    public static bool TryShow(string text)
    {
        if (!OperatingSystem.IsWindows() || !TryTarget(out var hwnd, out var id))
        {
            return false;
        }

        try
        {
            var data = new NotifyIconData
            {
                hWnd = hwnd,
                uID = id,
                uFlags = NifInfo,
                szInfo = text,
                szInfoTitle = "FlipSend",
                uTimeoutOrVersion = 4000,
                dwInfoFlags = NiifInfo | NiifNoSound | NiifRespectQuietTime
            };
            return Shell_NotifyIcon(NimModify, data);
        }
        catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException)
        {
            return false;
        }
    }

    public static void KeepInactive(IntPtr handle)
    {
        if (!OperatingSystem.IsWindows())
        {
            return;
        }

        var extra = GetWindowLongPtr(handle, GwlExStyle);
        var updated = new IntPtr(extra.ToInt64() | WsExNoActivate | WsExToolWindow);
        SetWindowLongPtr(handle, GwlExStyle, updated);
        ShowWindow(handle, SwShowNoActivate);
    }

    private static bool TryTarget(out IntPtr hwnd, out int id)
    {
        hwnd = IntPtr.Zero;
        id = 0;
        var icon = App.Tray;
        if (icon is null)
        {
            return false;
        }

        var impl = typeof(TrayIcon).GetProperty("Impl", BindingFlags.Instance | BindingFlags.NonPublic)?.GetValue(icon);
        if (impl is null)
        {
            return false;
        }

        if (impl.GetType().GetField("_uniqueId", BindingFlags.Instance | BindingFlags.NonPublic)?.GetValue(impl) is not int parsed)
        {
            return false;
        }

        var platform = Type.GetType("Avalonia.Win32.Win32Platform, Avalonia.Win32");
        var instance = platform?.GetProperty("Instance", BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic)?.GetValue(null);
        if (platform is null || instance is null)
        {
            return false;
        }

        if (platform.GetProperty("Handle", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)?.GetValue(instance) is not IntPtr handle || handle == IntPtr.Zero)
        {
            return false;
        }

        hwnd = handle;
        id = parsed;
        return true;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool Shell_NotifyIcon(uint message, NotifyIconData data);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", SetLastError = true)]
    private static extern IntPtr GetWindowLongPtr(IntPtr hWnd, int index);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", SetLastError = true)]
    private static extern IntPtr SetWindowLongPtr(IntPtr hWnd, int index, IntPtr value);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool ShowWindow(IntPtr hWnd, int command);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private sealed class NotifyIconData
    {
        public int cbSize = Marshal.SizeOf<NotifyIconData>();
        public IntPtr hWnd;
        public int uID;
        public uint uFlags;
        public int uCallbackMessage;
        public IntPtr hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string? szTip;
        public int dwState;
        public int dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
        public string? szInfo;
        public int uTimeoutOrVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
        public string? szInfoTitle;
        public uint dwInfoFlags;
    }
}
