using System.Diagnostics;
using System.Text.RegularExpressions;
using StellarSend.Core.Files;
using StellarSend.Core.Transfer;

namespace StellarSend.Core.Usb;

public sealed class ConfirmationRequiredException : InvalidOperationException
{
    public ConfirmationRequiredException()
        : base("USB transfer needs confirmation before adb runs.")
    {
    }
}

public sealed record ProcessResult(int ExitCode, string StandardOutput, string StandardError);

public interface IProcessRunner
{
    Task<ProcessResult> RunAsync(string fileName, IReadOnlyList<string> args, CancellationToken ct);
}

public sealed class ProcessRunner : IProcessRunner
{
    public async Task<ProcessResult> RunAsync(string fileName, IReadOnlyList<string> args, CancellationToken ct)
    {
        var start = new ProcessStartInfo
        {
            FileName = fileName,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        foreach (var arg in args)
        {
            start.ArgumentList.Add(arg);
        }

        using var process = new Process { StartInfo = start };
        process.Start();
        var stdoutTask = process.StandardOutput.ReadToEndAsync(ct);
        var stderrTask = process.StandardError.ReadToEndAsync(ct);
        await process.WaitForExitAsync(ct).ConfigureAwait(false);
        return new ProcessResult(process.ExitCode, await stdoutTask.ConfigureAwait(false), await stderrTask.ConfigureAwait(false));
    }
}

public sealed record AdbDevice(string Serial, string State, string Detail);

public sealed record AdbStatus(bool Found, string? Path, string Message, IReadOnlyList<AdbDevice> Devices);

public static class AdbLocator
{
    public static bool IsSafeExecutable(string path)
    {
        var name = Path.GetFileName(path);
        return name.Equals("adb", StringComparison.OrdinalIgnoreCase)
            || name.Equals("adb.exe", StringComparison.OrdinalIgnoreCase);
    }

    public static IEnumerable<string> Candidates(IDictionary<string, string?> environment, string pathVariable)
    {
        foreach (var directory in pathVariable.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            yield return Path.Combine(directory, OperatingSystem.IsWindows() ? "adb.exe" : "adb");
        }

        foreach (var key in new[] { "ANDROID_HOME", "ANDROID_SDK_ROOT" })
        {
            if (environment.TryGetValue(key, out var home) && !string.IsNullOrWhiteSpace(home))
            {
                yield return Path.Combine(home, "platform-tools", OperatingSystem.IsWindows() ? "adb.exe" : "adb");
            }
        }
    }
}

public sealed class AdbTransfer
{
    private static readonly Regex SerialPattern = new("^[A-Za-z0-9._:-]{1,80}$", RegexOptions.CultureInvariant);
    private static readonly HashSet<string> KnownStates = new(StringComparer.Ordinal)
    {
        "device", "unauthorized", "offline", "recovery", "authorizing", "sideload"
    };
    private readonly IProcessRunner _runner;

    public AdbTransfer(IProcessRunner runner)
    {
        _runner = runner;
    }

    public static IReadOnlyList<AdbDevice> ParseDevices(string output)
    {
        var devices = new List<AdbDevice>();
        var lines = output.Replace("\r\n", "\n").Split('\n');
        foreach (var raw in lines)
        {
            var line = raw.Trim();
            if (line.Length == 0 || line.StartsWith("List of devices", StringComparison.Ordinal) || line.StartsWith('*'))
            {
                continue;
            }

            var parts = line.Split(' ', 3, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length < 2 || !SerialPattern.IsMatch(parts[0]) || !KnownStates.Contains(parts[1]))
            {
                continue;
            }

            devices.Add(new AdbDevice(parts[0], parts[1], parts.Length > 2 ? parts[2] : ""));
        }

        return devices;
    }

    public async Task<AdbStatus> DetectAsync(string? adbPath, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(adbPath) || !AdbLocator.IsSafeExecutable(adbPath) || !File.Exists(adbPath))
        {
            return new AdbStatus(false, null, "adb was not found. Install Android platform-tools and add adb to PATH. FlipSend does not download it.", []);
        }

        var version = await _runner.RunAsync(adbPath, ["version"], ct).ConfigureAwait(false);
        if (version.ExitCode != 0)
        {
            return new AdbStatus(false, adbPath, "adb did not run. " + Trim(version.StandardError), []);
        }

        var devices = await _runner.RunAsync(adbPath, ["devices", "-l"], ct).ConfigureAwait(false);
        if (devices.ExitCode != 0)
        {
            return new AdbStatus(true, adbPath, "adb is installed, but listing devices failed. " + Trim(devices.StandardError), []);
        }

        var parsed = ParseDevices(devices.StandardOutput);
        var ready = parsed.Count(d => d.State == "device");
        var message = ready == 0
            ? "adb is installed. No authorized device is connected."
            : $"adb sees {ready} authorized device{(ready == 1 ? "" : "s")}.";
        return new AdbStatus(true, adbPath, message, parsed);
    }

    public async Task<TransferResult> PushAsync(string adbPath, string serial, string localPath, bool userConfirmed, IProgress<TransferProgress>? progress, CancellationToken ct)
    {
        if (!userConfirmed)
        {
            throw new ConfirmationRequiredException();
        }

        Validate(adbPath, serial, localPath);
        var remoteName = FileNames.Sanitize(Path.GetFileName(localPath));
        var remote = "/sdcard/Download/StellarSend/" + remoteName;
        return await RunTransferAsync(adbPath, ["-s", serial, "push", localPath, remote], remoteName, new FileInfo(localPath).Length, progress, ct).ConfigureAwait(false);
    }

    public async Task<TransferResult> PullAsync(string adbPath, string serial, string remotePath, string localDirectory, bool userConfirmed, IProgress<TransferProgress>? progress, CancellationToken ct)
    {
        if (!userConfirmed)
        {
            throw new ConfirmationRequiredException();
        }

        Validate(adbPath, serial, remotePath);
        if (!IsSafeRemote(remotePath))
        {
            throw new ArgumentException("Refusing an adb path outside the expected phone folders.", nameof(remotePath));
        }

        Directory.CreateDirectory(localDirectory);
        var name = FileNames.Sanitize(Path.GetFileName(remotePath.Replace('\\', '/')));
        return await RunTransferAsync(adbPath, ["-s", serial, "pull", remotePath, localDirectory], name, 0, progress, ct).ConfigureAwait(false);
    }

    public static bool IsSafeRemote(string remotePath)
    {
        if (remotePath.Contains("..", StringComparison.Ordinal) || remotePath.Contains('\n') || remotePath.Contains('"'))
        {
            return false;
        }

        return remotePath.StartsWith("/sdcard/", StringComparison.Ordinal)
            || remotePath.StartsWith("/storage/", StringComparison.Ordinal);
    }

    private async Task<TransferResult> RunTransferAsync(
        string adbPath,
        IReadOnlyList<string> args,
        string name,
        long size,
        IProgress<TransferProgress>? progress,
        CancellationToken ct)
    {
        var id = Guid.NewGuid();
        progress?.Report(new TransferProgress(id, name, TransferState.InProgress, 0, size, null, null, "Waiting for adb"));
        var result = await _runner.RunAsync(adbPath, args, ct).ConfigureAwait(false);
        if (result.ExitCode != 0)
        {
            var message = Trim(string.IsNullOrWhiteSpace(result.StandardError) ? result.StandardOutput : result.StandardError);
            progress?.Report(new TransferProgress(id, name, TransferState.Failed, 0, size, null, null, message));
            return new TransferResult(TransferState.Failed, "adb", message, null, null, 0, false);
        }

        progress?.Report(new TransferProgress(id, name, TransferState.Succeeded, size, size, null, null, "USB transfer finished"));
        return new TransferResult(TransferState.Succeeded, null, "USB transfer finished", null, null, 0, false);
    }

    private static void Validate(string adbPath, string serial, string path)
    {
        if (!AdbLocator.IsSafeExecutable(adbPath))
        {
            throw new ArgumentException("adb path is not an adb executable.", nameof(adbPath));
        }

        if (!SerialPattern.IsMatch(serial))
        {
            throw new ArgumentException("Device serial looks invalid.", nameof(serial));
        }

        if (string.IsNullOrWhiteSpace(path))
        {
            throw new ArgumentException("Path is empty.", nameof(path));
        }
    }

    private static string Trim(string text)
    {
        var line = text.Replace("\r", " ").Replace("\n", " ").Trim();
        return line.Length > 240 ? line[..240] : line;
    }
}
