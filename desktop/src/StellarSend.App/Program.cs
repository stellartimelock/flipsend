using Avalonia;
using StellarSend.Core.Security;
using StellarSend.Core.Shell;

namespace StellarSend.App;

public static class Program
{
    public static string? StartupFile { get; private set; }

    private static SingleInstanceClaim? _instance;

    [STAThread]
    public static void Main(string[] args)
    {
        if (args.Any(value => value == "--check-assets"))
        {
            BuildAvaloniaApp().SetupWithoutStarting();
            AppAssets.RequireAll();
            return;
        }

        var sendFile = Parse(args);
        var log = new RedactingFileLog(AppPaths.LogFile);
        var claim = SingleInstanceClaim.Acquire(SingleInstanceClaim.DefaultName, log);
        if (!claim.IsFirst)
        {
            HandoffDecision? handoff = null;
            if (sendFile is null)
            {
                log.Info("FlipSend is already running. This launch will exit.");
                SendQueue.OfferShow(AppPaths.QueueDirectory, SendQueue.AckTimeout, log);
            }
            else
            {
                handoff = SendQueue.Offer(AppPaths.QueueDirectory, sendFile, SendQueue.HandoffTimeout, log);
            }

            claim.Dispose();
            if (LaunchPlan.Decide(false, sendFile, handoff) == LaunchAction.ExitFailed)
            {
                log.Error($"Could not hand {Path.GetFileName(sendFile)} to the running FlipSend. Try the send again.");
                Environment.ExitCode = 1;
            }

            return;
        }

        _instance = claim;
        StartupFile = sendFile;
        BuildAvaloniaApp().StartWithClassicDesktopLifetime(args, Avalonia.Controls.ShutdownMode.OnExplicitShutdown);
    }

    public static AppBuilder BuildAvaloniaApp() =>
        AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .WithInterFont()
            .LogToTrace();

    private static string? Parse(string[] args)
    {
        for (var i = 0; i < args.Length; i++)
        {
            if (args[i] == "--register-shell")
            {
                WindowsIntegration.RegisterShell();
                Environment.Exit(0);
            }

            if (args[i] == "--unregister-shell")
            {
                WindowsIntegration.UnregisterShell();
                Environment.Exit(0);
            }

            if (args[i] == "--enable-startup")
            {
                WindowsIntegration.EnableStartup();
                Environment.Exit(0);
            }

            if (args[i] == "--disable-startup")
            {
                WindowsIntegration.DisableStartup();
                Environment.Exit(0);
            }

            if (args[i] == "--send-file" && i + 1 < args.Length)
            {
                return args[i + 1];
            }
        }

        return null;
    }
}
