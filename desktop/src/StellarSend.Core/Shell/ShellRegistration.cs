using StellarSend.Core.Security;

namespace StellarSend.Core.Shell;

public interface IRegistry
{
    void SetString(string subKey, string name, string value);
    bool TryGetString(string subKey, string name, out string? value);
    void DeleteValue(string subKey, string name);
    void DeleteTree(string subKey);
}

public static class ShellRegistration
{
    public const string Key = @"Software\Classes\*\shell\StellarSend";
    public const string CommandKey = @"Software\Classes\*\shell\StellarSend\command";

    public static void Register(IRegistry hive, string exePath)
    {
        ValidateExe(exePath);
        hive.SetString(Key, "", "Send to phone");
        hive.SetString(Key, "Icon", exePath);
        hive.SetString(CommandKey, "", $"\"{exePath}\" --send-file \"%1\"");
    }

    public static void Unregister(IRegistry hive) => hive.DeleteTree(Key);

    public static void ValidateExe(string exePath)
    {
        if (string.IsNullOrWhiteSpace(exePath) || exePath.Contains('"'))
        {
            throw new ArgumentException("Executable path must not contain quotes.", nameof(exePath));
        }

        if (!exePath.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
        {
            throw new ArgumentException("Expected the FlipSend .exe.", nameof(exePath));
        }
    }
}

public static class StartupRegistration
{
    public const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    public const string ValueName = "StellarSend";

    public static void Enable(IRegistry hive, string exePath)
    {
        ShellRegistration.ValidateExe(exePath);
        hive.SetString(RunKey, ValueName, $"\"{exePath}\"");
    }

    public static void Disable(IRegistry hive) => hive.DeleteValue(RunKey, ValueName);

    public static bool IsEnabled(IRegistry hive) =>
        hive.TryGetString(RunKey, ValueName, out var value) && !string.IsNullOrWhiteSpace(value);
}

public enum HandoffDecision
{
    Accepted,
    NoAnswer
}

public enum LaunchAction
{
    /// <summary>This launch is the only FlipSend: start the window, node and tray.</summary>
    StartApp,
    /// <summary>The running FlipSend took the work: exit without touching anything.</summary>
    Exit,
    /// <summary>The running FlipSend did not answer: exit with an error. Never start a second FlipSend.</summary>
    ExitFailed
}

public static class LaunchPlan
{
    /// <summary>
    /// One FlipSend per Windows session. Only the first launch starts the app. A later launch
    /// hands its file (or a show request) to the running copy and exits. When the running copy
    /// does not answer, the later launch still exits so the window, the relay connection and any
    /// open Remote desktop Allow prompt in the running copy are left alone.
    /// </summary>
    public static LaunchAction Decide(bool isFirst, string? sendFile, HandoffDecision? handoff)
    {
        if (isFirst)
        {
            return LaunchAction.StartApp;
        }

        if (sendFile is null)
        {
            return LaunchAction.Exit;
        }

        return handoff == HandoffDecision.Accepted ? LaunchAction.Exit : LaunchAction.ExitFailed;
    }
}

public static class SendQueue
{
    public const string ShowToken = "show-window";
    public static readonly TimeSpan AckTimeout = TimeSpan.FromSeconds(5);

    /// <summary>
    /// How long a file launch waits for the running FlipSend to pick the file up. Long enough
    /// for a copy that is still starting (window, node, relay) to begin draining its queue.
    /// </summary>
    public static readonly TimeSpan HandoffTimeout = TimeSpan.FromSeconds(30);

    public static HandoffDecision OfferShow(
        string directory,
        TimeSpan timeout,
        ILog log,
        TimeProvider? clock = null,
        Action<TimeSpan>? sleep = null)
    {
        log.Info("Asking the running FlipSend to show its window.");
        var id = Submit(directory, ShowToken);
        return WaitForAcceptance(directory, id, "the main window", timeout, log, clock, sleep);
    }

    public static void Enqueue(string directory, string filePath) => Submit(directory, filePath);

    public static string Submit(string directory, string filePath)
    {
        if (string.IsNullOrWhiteSpace(filePath) || filePath.Length > 4096)
        {
            throw new ArgumentException("File path is invalid.", nameof(filePath));
        }

        Directory.CreateDirectory(directory);
        var id = Guid.NewGuid().ToString("N");
        File.WriteAllText(RequestPath(directory, id), filePath);
        return id;
    }

    public static HandoffDecision Offer(
        string directory,
        string filePath,
        TimeSpan timeout,
        ILog log,
        TimeProvider? clock = null,
        Action<TimeSpan>? sleep = null)
    {
        var name = FileNameOf(filePath);
        log.Info($"Handing {name} to the running FlipSend.");
        var id = Submit(directory, filePath);
        return WaitForAcceptance(directory, id, name, timeout, log, clock, sleep);
    }

    public static HandoffDecision WaitForAcceptance(
        string directory,
        string id,
        string displayName,
        TimeSpan timeout,
        ILog log,
        TimeProvider? clock = null,
        Action<TimeSpan>? sleep = null)
    {
        clock ??= TimeProvider.System;
        sleep ??= Thread.Sleep;
        var start = clock.GetUtcNow();
        while (true)
        {
            if (AckExists(directory, id))
            {
                TryDelete(AckPath(directory, id));
                log.Info($"The running FlipSend accepted {displayName}.");
                return HandoffDecision.Accepted;
            }

            var elapsed = clock.GetUtcNow() - start;
            if (elapsed >= timeout)
            {
                break;
            }

            var slice = TimeSpan.FromMilliseconds(50);
            var remaining = timeout - elapsed;
            sleep(remaining < slice ? remaining : slice);
        }

        if (AckExists(directory, id))
        {
            TryDelete(AckPath(directory, id));
            log.Info($"The running FlipSend accepted {displayName}.");
            return HandoffDecision.Accepted;
        }

        var request = RequestPath(directory, id);
        if (File.Exists(request))
        {
            TryDelete(request);
        }

        if (AckExists(directory, id) || File.Exists(ClaimPath(directory, id)))
        {
            TryDelete(AckPath(directory, id));
            log.Info($"The running FlipSend accepted {displayName}.");
            return HandoffDecision.Accepted;
        }

        var seconds = Math.Max(1, (int)timeout.TotalSeconds);
        log.Warn($"The running FlipSend did not acknowledge {displayName} within {seconds} seconds. Not starting a second FlipSend.");
        return HandoffDecision.NoAnswer;
    }

    public static List<string> Drain(string directory, ILog? log = null, Action? onShow = null)
    {
        var found = new List<string>();
        if (!Directory.Exists(directory))
        {
            return found;
        }

        foreach (var file in Directory.GetFiles(directory, "*.path"))
        {
            var id = Path.GetFileNameWithoutExtension(file);
            var claimed = ClaimPath(directory, id);
            try
            {
                File.Move(file, claimed);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                continue;
            }

            string text;
            try
            {
                text = File.ReadAllText(claimed).Trim();
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                TryMove(claimed, file);
                log?.Warn("Could not read a file handed off by another launch.");
                continue;
            }

            try
            {
                File.WriteAllText(AckPath(directory, id), "ok");
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                TryMove(claimed, file);
                log?.Warn("Could not acknowledge a file handed off by another launch.");
                continue;
            }

            TryDelete(claimed);
            if (text == ShowToken)
            {
                log?.Info("Another launch asked to show the window.");
                onShow?.Invoke();
                continue;
            }

            log?.Info("Accepted a file from another FlipSend launch.");
            if (text.Length is > 0 and < 4096)
            {
                found.Add(text);
            }
        }

        return found;
    }

    private static string FileNameOf(string filePath)
    {
        var trimmed = filePath.TrimEnd('\\', '/');
        var slash = trimmed.LastIndexOfAny(['\\', '/']);
        return slash >= 0 && slash < trimmed.Length - 1 ? trimmed[(slash + 1)..] : trimmed;
    }

    private static string RequestPath(string directory, string id) => Path.Combine(directory, id + ".path");

    private static string ClaimPath(string directory, string id) => Path.Combine(directory, id + ".claimed");

    private static string AckPath(string directory, string id) => Path.Combine(directory, id + ".ack");

    private static bool AckExists(string directory, string id) => File.Exists(AckPath(directory, id));

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
        }
    }

    private static void TryMove(string source, string destination)
    {
        try
        {
            if (File.Exists(source) && !File.Exists(destination))
            {
                File.Move(source, destination);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
        }
    }
}
