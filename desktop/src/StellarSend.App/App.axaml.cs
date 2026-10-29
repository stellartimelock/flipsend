using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;

namespace StellarSend.App;

public sealed class App : Application
{
    public static MainWindow? Window { get; private set; }
    public static TrayIcon? Tray { get; private set; }

    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            AppAssets.RequireAll();
            Window = new MainWindow();
            desktop.MainWindow = Window;
            Window.Icon = new WindowIcon(AppAssets.Open(AppAssets.IconPng));
            Tray = new TrayIcon
            {
                Icon = new WindowIcon(AppAssets.Open(AppAssets.IconPng)),
                ToolTipText = "FlipSend",
                IsVisible = true,
                Command = Window.ShowCommand,
                Menu = new NativeMenu
                {
                    new NativeMenuItem("Open") { Command = Window.ShowCommand },
                    new NativeMenuItem("Quit") { Command = Window.QuitCommand }
                }
            };
            TrayIcon.SetIcons(this, new TrayIcons { Tray });
        }

        base.OnFrameworkInitializationCompleted();
    }
}
