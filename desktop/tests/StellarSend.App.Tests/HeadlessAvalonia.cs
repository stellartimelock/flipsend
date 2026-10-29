using Avalonia;
using Avalonia.Headless;

namespace StellarSend.App.Tests;

internal static class HeadlessAvalonia
{
    private static readonly object Gate = new();
    private static bool _ready;

    public static void Ensure()
    {
        lock (Gate)
        {
            if (_ready)
            {
                return;
            }

            AppBuilder.Configure<App>()
                .UseSkia()
                .UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false })
                .SetupWithoutStarting();
            _ready = true;
        }
    }
}
