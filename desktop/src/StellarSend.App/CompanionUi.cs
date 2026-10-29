using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using StellarSend.Core.Companion;

namespace StellarSend.App;

public sealed class WindowClipboard : ISecretClipboard
{
    private readonly Window _window;

    public WindowClipboard(Window window) => _window = window;

    public void SetText(string text) => Run(() =>
    {
        var clipboard = _window.Clipboard ?? throw new InvalidOperationException("Clipboard is not available.");
        return clipboard.SetTextAsync(text);
    });

    public string? GetText() => Run(() =>
    {
        var clipboard = _window.Clipboard;
        return clipboard is null ? Task.FromResult<string?>(null) : clipboard.GetTextAsync();
    });

    private static void Run(Func<Task> action) => Run(async () =>
    {
        await action().ConfigureAwait(true);
        return true;
    });

    private static T Run<T>(Func<Task<T>> action)
    {
        if (Dispatcher.UIThread.CheckAccess())
        {
            return action().GetAwaiter().GetResult();
        }

        var done = new TaskCompletionSource<T>();
        Dispatcher.UIThread.Post(async () =>
        {
            try
            {
                done.TrySetResult(await action().ConfigureAwait(true));
            }
            catch (Exception ex)
            {
                done.TrySetException(ex);
            }
        });
        return done.Task.GetAwaiter().GetResult();
    }
}

public sealed class ToastNotice : IUserNotice
{
    public void Show(string text)
    {
        if (Dispatcher.UIThread.CheckAccess())
        {
            ToastWindow.Show(text);
            return;
        }

        Dispatcher.UIThread.Post(() => ToastWindow.Show(text));
    }
}

public static class ToastWindow
{
    public static void Show(string text)
    {
        var window = new Window
        {
            Width = 380,
            SizeToContent = SizeToContent.Height,
            CanResize = false,
            ShowInTaskbar = false,
            SystemDecorations = SystemDecorations.None,
            Topmost = true,
            ShowActivated = false,
            Background = Brushes.Transparent,
            WindowStartupLocation = WindowStartupLocation.CenterScreen,
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
        window.Show();
        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            window.Close();
        };
        timer.Start();
    }
}
