using System.Windows.Input;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using StellarSend.Core.Companion;
using StellarSend.Core.Files;
using StellarSend.Core.Protocol;
using StellarSend.Core.Transfer;

namespace StellarSend.App;

public partial class MainWindow : Window
{
    private bool _allowExit;
    public ICommand ShowCommand { get; }
    public ICommand QuitCommand { get; }
    public MainViewModel Model { get; }

    public static bool SkipAutoStart { get; set; }

    public MainWindow()
    {
        InitializeComponent();
        Model = new MainViewModel(this);
        DataContext = Model;
        ShowCommand = new RelayCommand(ShowFromTray);
        QuitCommand = new RelayCommand(Quit);
        DragDrop.SetAllowDrop(this, true);
        AddHandler(DragDrop.DragOverEvent, OnDragOver);
        AddHandler(DragDrop.DropEvent, OnDrop);
        Opened += async (_, _) =>
        {
            if (!SkipAutoStart)
            {
                await Model.StartAsync();
            }
        };
    }

    public void ShowFromTray()
    {
        if (WindowState == WindowState.Minimized)
        {
            WindowState = WindowState.Normal;
        }

        Show();
        Topmost = true;
        Activate();
        Topmost = false;
    }

    public async Task ShowLargeQrAsync(Avalonia.Media.Imaging.Bitmap? image)
    {
        if (image is null)
        {
            return;
        }

        var dialog = NewDialog("Pairing code");
        dialog.Width = 520;
        dialog.Height = 640;
        dialog.SizeToContent = SizeToContent.Manual;
        dialog.CanResize = true;
        var close = new Button
        {
            Content = "Close",
            MinHeight = 44,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Margin = new Avalonia.Thickness(0, 12, 0, 0),
            FontSize = 16
        };
        close.Click += (_, _) => dialog.Close();
        dialog.Content = new StackPanel
        {
            Margin = new Avalonia.Thickness(20),
            Spacing = 8,
            Children =
            {
                new Border
                {
                    Background = Brush.Parse("#F6F1E7"),
                    Padding = new Avalonia.Thickness(16),
                    CornerRadius = new Avalonia.CornerRadius(16),
                    Child = new Image
                    {
                        Source = image,
                        Stretch = Stretch.Uniform,
                        Width = 440,
                        Height = 440
                    }
                },
                close
            }
        };
        await dialog.ShowDialog(this);
    }

    public void Quit()
    {
        _allowExit = true;
        Close();
        if (App.Current?.ApplicationLifetime is Avalonia.Controls.ApplicationLifetimes.IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.Shutdown();
        }
    }

    protected override void OnClosing(WindowClosingEventArgs e)
    {
        if (!_allowExit)
        {
            e.Cancel = true;
            Hide();
        }

        base.OnClosing(e);
    }

    private void OnDragOver(object? sender, DragEventArgs e)
    {
        e.DragEffects = e.Data.Contains(DataFormats.Files) ? DragDropEffects.Copy : DragDropEffects.None;
    }

    private async void OnDrop(object? sender, DragEventArgs e)
    {
        if (!e.Data.Contains(DataFormats.Files))
        {
            return;
        }

        var files = new List<string>();
        var folders = new List<string>();
        foreach (var file in e.Data.GetFiles() ?? [])
        {
            var path = file.TryGetLocalPath();
            if (string.IsNullOrWhiteSpace(path))
            {
                continue;
            }

            if (Directory.Exists(path))
            {
                folders.Add(path);
            }
            else if (File.Exists(path))
            {
                files.Add(path);
            }
        }

        files = ApkSendOrder.SelfLast(files).ToList();
        var count = files.Count > 1 ? files.Count : 0;
        for (var i = 0; i < files.Count; i++)
        {
            await Model.SendFileAsync(files[i], count > 1 ? i + 1 : 0, count);
        }

        foreach (var folder in folders)
        {
            await Model.SendFolderAsync(folder);
        }
    }

    public Task<bool> ConfirmAsync(string title, string body, string confirm) =>
        OnUiAsync(() => DialogAsync(title, body, confirm, null));

    public Task<RemoteDesktopConsent> ConfirmRemoteDesktopAsync(string fromName) =>
        OnUiAsync(() => RemoteDesktopDialogAsync(fromName));

    public Task<string?> AskForTextAsync(string title, string body) =>
        OnUiAsync(() => TextDialogAsync(title, body));

    public Task ShowTextAsync(string title, string body) =>
        OnUiAsync(async () =>
        {
            await ReceivedTextDialogAsync(title, body);
            return true;
        });

    private async Task<T> OnUiAsync<T>(Func<Task<T>> action)
    {
        ShowFromTray();
        if (Dispatcher.UIThread.CheckAccess())
        {
            return await action();
        }

        return await Dispatcher.UIThread.InvokeAsync(action);
    }

    private async Task<bool> DialogAsync(string title, string body, string confirm, string? _)
    {
        var result = false;
        var dialog = NewDialog(title);
        var yes = new Button { Content = confirm, MinHeight = 36, IsDefault = true, Margin = new Avalonia.Thickness(0, 0, 8, 8) };
        var no = new Button { Content = "Cancel", MinHeight = 36, Margin = new Avalonia.Thickness(0, 0, 8, 8) };
        yes.Click += (_, _) =>
        {
            result = true;
            dialog.Close();
        };
        no.Click += (_, _) => dialog.Close();
        dialog.Content = new StackPanel
        {
            Margin = new Avalonia.Thickness(20),
            Spacing = 12,
            Children =
            {
                new TextBlock { Text = body, TextWrapping = TextWrapping.Wrap, FontSize = 16, Foreground = Brush.Parse("#F6F1E7") },
                new WrapPanel { Children = { yes, no } }
            }
        };
        await dialog.ShowDialog(this);
        return result;
    }

    private async Task<RemoteDesktopConsent> RemoteDesktopDialogAsync(string fromName)
    {
        var allowed = false;
        var always = false;
        var dialog = NewDialog("Allow remote desktop?");
        var box = new CheckBox
        {
            Content = "Always allow this phone",
            FontSize = 16,
            Foreground = Brush.Parse("#F6F1E7"),
            IsChecked = false
        };
        var yes = new Button { Content = "Allow", MinHeight = 36, IsDefault = true, Margin = new Avalonia.Thickness(0, 0, 8, 8) };
        var no = new Button { Content = "Cancel", MinHeight = 36, Margin = new Avalonia.Thickness(0, 0, 8, 8) };
        yes.Click += (_, _) =>
        {
            allowed = true;
            always = box.IsChecked == true;
            dialog.Close();
        };
        no.Click += (_, _) => dialog.Close();
        dialog.Content = new StackPanel
        {
            Margin = new Avalonia.Thickness(20),
            Spacing = 12,
            Children =
            {
                new TextBlock
                {
                    Text = $"Let {fromName} see and control this computer? You can tap Stop at any time.",
                    TextWrapping = TextWrapping.Wrap,
                    FontSize = 16,
                    Foreground = Brush.Parse("#F6F1E7")
                },
                box,
                new WrapPanel { Children = { yes, no } }
            }
        };
        await dialog.ShowDialog(this);
        return new RemoteDesktopConsent(allowed, always);
    }

    private async Task<string?> TextDialogAsync(string title, string body)
    {
        string? value = null;
        var dialog = NewDialog(title);
        var box = new TextBox { MinHeight = 72, AcceptsReturn = true, Watermark = "stellarsend://pair?..." };
        var yes = new Button { Content = "Pair", MinHeight = 36, IsDefault = true, Margin = new Avalonia.Thickness(0, 0, 8, 8) };
        var no = new Button { Content = "Cancel", MinHeight = 36, Margin = new Avalonia.Thickness(0, 0, 8, 8) };
        yes.Click += (_, _) =>
        {
            value = box.Text;
            dialog.Close();
        };
        no.Click += (_, _) => dialog.Close();
        dialog.Content = new StackPanel
        {
            Margin = new Avalonia.Thickness(20),
            Spacing = 12,
            Children =
            {
                new TextBlock { Text = body, TextWrapping = TextWrapping.Wrap, FontSize = 16, Foreground = Brush.Parse("#F6F1E7") },
                box,
                new WrapPanel { Children = { yes, no } }
            }
        };
        await dialog.ShowDialog(this);
        return value;
    }

    private static Window NewDialog(string title) => new()
    {
        Title = title,
        Width = 420,
        MinWidth = 320,
        MaxWidth = 520,
        SizeToContent = SizeToContent.Height,
        MaxHeight = 640,
        Background = Brush.Parse("#07060F"),
        WindowStartupLocation = WindowStartupLocation.CenterOwner,
        CanResize = false
    };

    public async Task<string?> PickFileAsync()
    {
        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "Choose a file to send",
            AllowMultiple = false
        });
        return files.Count == 0 ? null : files[0].TryGetLocalPath();
    }

    public async Task<string?> PickApkAsync()
    {
        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "Choose an APK to send to the phone",
            AllowMultiple = false,
            FileTypeFilter =
            [
                new FilePickerFileType("Android package") { Patterns = ["*.apk"] }
            ]
        });
        return files.Count == 0 ? null : files[0].TryGetLocalPath();
    }

    public async Task<string?> PickFolderAsync(string title)
    {
        var folders = await StorageProvider.OpenFolderPickerAsync(new FolderPickerOpenOptions
        {
            Title = title,
            AllowMultiple = false
        });
        return folders.Count == 0 ? null : folders[0].TryGetLocalPath();
    }

    public async Task<string?> ReadClipboardAsync()
    {
        if (Clipboard is null)
        {
            return null;
        }

        return await Clipboard.GetTextAsync();
    }

    public Task WriteClipboardAsync(string text)
    {
        if (Clipboard is not null)
        {
            return Clipboard.SetTextAsync(text);
        }

        return Task.CompletedTask;
    }

    private async Task ReceivedTextDialogAsync(string title, string body)
    {
        var dialog = NewDialog(title);
        dialog.Width = 460;
        dialog.MinHeight = 280;
        dialog.MaxHeight = 720;
        dialog.CanResize = true;
        var box = new TextBox
        {
            Text = body,
            IsReadOnly = true,
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            MinHeight = 160,
            FontSize = 16,
            CaretIndex = 0
        };
        var copy = new Button
        {
            Content = "Copy",
            MinHeight = 36,
            Margin = new Avalonia.Thickness(0, 0, 8, 8)
        };
        copy.Classes.Add("accent");
        copy.Click += async (_, _) =>
        {
            var selected = box.SelectedText;
            await WriteClipboardAsync(string.IsNullOrEmpty(selected) ? body : selected);
        };
        var close = new Button
        {
            Content = "Close",
            MinHeight = 36,
            IsDefault = true,
            Margin = new Avalonia.Thickness(0, 0, 8, 8)
        };
        close.Classes.Add("quiet");
        close.Click += (_, _) => dialog.Close();
        dialog.Content = new StackPanel
        {
            Margin = new Avalonia.Thickness(20),
            Spacing = 12,
            Children =
            {
                new TextBlock
                {
                    Text = "Select the text, or tap Copy.",
                    FontSize = 16,
                    Foreground = Brush.Parse("#C9C0B2"),
                    TextWrapping = TextWrapping.Wrap
                },
                box,
                new WrapPanel { Children = { copy, close } }
            }
        };
        await dialog.ShowDialog(this);
    }
}

public sealed class WindowConsent : IConsent
{
    private readonly MainWindow _window;

    public WindowConsent(MainWindow window) => _window = window;

    public Task<bool> AcceptPairingAsync(string remoteName, string fingerprint, CancellationToken ct)
    {
        var grouped = string.Join(' ', Enumerable.Range(0, fingerprint.Length / 4).Select(index => fingerprint.Substring(index * 4, 4)));
        return _window.ConfirmAsync("Pair this device?", remoteName + Environment.NewLine + grouped, "Pair");
    }

    public Task<bool> AcceptIncomingAsync(OfferBody offer, string remoteName, CancellationToken ct)
    {
        if (!TransferAttention.AsksBeforeReceive(_window.Model.BringWindowToFrontOnTransfer, offer.Name))
        {
            return Task.FromResult(true);
        }

        return _window.ConfirmAsync("Receive this?", $"{offer.Name} ({offer.Size} bytes) from {remoteName}", "Accept");
    }

    // Remote desktop still opens this window. The transfer setting does not apply.
    public Task<RemoteDesktopConsent> AcceptRemoteControlAsync(string fromName, CancellationToken ct) =>
        _window.ConfirmRemoteDesktopAsync(fromName);
}

public sealed class RelayCommand : ICommand
{
    private readonly Func<Task>? _async;
    private readonly Action? _sync;

    public RelayCommand(Action action) => _sync = action;
    public RelayCommand(Func<Task> action) => _async = action;

    public event EventHandler? CanExecuteChanged
    {
        add { }
        remove { }
    }

    public bool CanExecute(object? parameter) => true;

    public async void Execute(object? parameter)
    {
        try
        {
            if (_sync is not null)
            {
                _sync();
            }

            if (_async is not null)
            {
                await _async();
            }
        }
        catch (Exception ex)
        {
            if (App.Window?.Model is { } model)
            {
                model.ReportError(ex.Message);
            }
        }
    }
}
