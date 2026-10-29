using System.Diagnostics;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using StellarSend.Core.Companion;
using StellarSend.Core.Pairing;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;

namespace StellarSend.Core.Terminal;

/// <summary>
/// Terminal commands are signed with the pairing identity key.
/// That key is the pairing seed. It is not a device-bound keystore key.
/// </summary>
public static class TerminalAuth
{
    public const string Label = "flipsend-terminal-v1";

    public static byte[] Payload(string id, string command, bool confirmed)
    {
        var flag = confirmed ? "1" : "0";
        return Encoding.UTF8.GetBytes($"{Label}\n{id}\n{command}\n{flag}\n");
    }

    public static string Sign(X509Certificate2 certificate, string id, string command, bool confirmed)
    {
        using var key = certificate.GetECDsaPrivateKey() ?? throw new InvalidOperationException("Pairing key is missing.");
        var signature = key.SignData(Payload(id, command, confirmed), HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        return Base64Url.Encode(signature);
    }

    public static bool Verify(ReadOnlySpan<byte> spki, string id, string command, bool confirmed, string? signature)
    {
        if (string.IsNullOrWhiteSpace(signature))
        {
            return false;
        }

        try
        {
            using var key = ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(spki, out _);
            var bytes = Base64Url.Decode(signature);
            return key.VerifyData(Payload(id, command, confirmed), bytes, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch (Exception ex) when (ex is CryptographicException or FormatException or ArgumentException)
        {
            return false;
        }
    }
}

public static class TerminalPolicy
{
    public const int MaxCommandChars = 4000;
    public const int MaxOutputChars = 1_048_576;
    public const string Disabled = "terminal_off";
    public const string NotAllowed = "not_allowed";
    public const string NeedsConfirm = "needs_confirm";
    public const string Unauthenticated = "unauthenticated";

    private static readonly Regex Destructive = new(
        @"(?i)(?:^|[^a-z0-9_])(del|rm|rmdir|format|shutdown)(?:[^a-z0-9_]|$)",
        RegexOptions.CultureInvariant | RegexOptions.Compiled);

    public static bool IsDestructive(string command) => Destructive.IsMatch(command);

    public static string? RejectReason(string? command)
    {
        if (string.IsNullOrWhiteSpace(command))
        {
            return "Enter a command.";
        }

        if (command.Length > MaxCommandChars)
        {
            return "That command is too long.";
        }

        foreach (var ch in command)
        {
            if (ch == '\0' || (char.IsControl(ch) && ch is not ('\n' or '\r' or '\t')))
            {
                return "That command has unsupported characters.";
            }
        }

        return null;
    }

    public static bool IsAllowlisted(string command, IEnumerable<string> allowlist)
    {
        var trimmed = command.Trim();
        foreach (var item in allowlist)
        {
            if (string.Equals(item.Trim(), trimmed, StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
        }

        return false;
    }
}

public sealed class TerminalSettings
{
    public bool Enabled { get; set; }
    public bool AllowlistOnly { get; set; } = true;
    public List<string> Allowlist { get; set; } = [];
}

public sealed class TerminalChunk
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("seq")]
    public int Seq { get; set; }

    [JsonPropertyName("stream")]
    public string Stream { get; set; } = "out";

    [JsonPropertyName("text")]
    public string Text { get; set; } = "";
}

public sealed class TerminalLog
{
    private readonly string _path;
    private readonly object _gate = new();

    public TerminalLog(string path)
    {
        _path = path;
    }

    public string Path => _path;

    public void Write(string peerName, string peerId, string outcome, string code, string? command)
    {
        var safeCommand = command is null ? "" : OneLine(RedactingFileLog.Redact(command));
        var line = $"{DateTimeOffset.UtcNow:O}\t{OneLine(peerName)}\t{peerId}\t{outcome}\t{code}\t{safeCommand}{Environment.NewLine}";
        lock (_gate)
        {
            var directory = System.IO.Path.GetDirectoryName(_path);
            if (!string.IsNullOrEmpty(directory))
            {
                Directory.CreateDirectory(directory);
            }

            File.AppendAllText(_path, line);
        }
    }

    private static string OneLine(string value) =>
        value.Replace('\t', ' ').Replace('\r', ' ').Replace('\n', ' ');
}

public interface ITerminalProcess
{
    Task<int> RunAsync(string command, Func<string, string, CancellationToken, Task> onChunk, CancellationToken ct);
}

public sealed class WindowsCommandProcess : ITerminalProcess
{
    public static readonly TimeSpan Timeout = TimeSpan.FromMinutes(2);

    public async Task<int> RunAsync(string command, Func<string, string, CancellationToken, Task> onChunk, CancellationToken ct)
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("Terminal commands run on the Windows PC.");
        }

        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (string.IsNullOrWhiteSpace(home))
        {
            home = Environment.CurrentDirectory;
        }

        using var process = new Process();
        process.StartInfo = new ProcessStartInfo
        {
            FileName = Environment.GetEnvironmentVariable("ComSpec") is { Length: > 0 } shell ? shell : "cmd.exe",
            WorkingDirectory = home,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };
        process.StartInfo.ArgumentList.Add("/d");
        process.StartInfo.ArgumentList.Add("/s");
        process.StartInfo.ArgumentList.Add("/c");
        process.StartInfo.ArgumentList.Add(command);

        if (!process.Start())
        {
            throw new InvalidOperationException("The command could not be started.");
        }

        process.StandardInput.Close();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(Timeout);
        await using var kill = timeout.Token.Register(() => TryKill(process));
        var stdout = PumpAsync(process.StandardOutput, "out", onChunk, timeout.Token);
        var stderr = PumpAsync(process.StandardError, "err", onChunk, timeout.Token);
        try
        {
            await process.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            TryKill(process);
            throw new TimeoutException("The command ran longer than 2 minutes.");
        }

        await Task.WhenAll(stdout, stderr).ConfigureAwait(false);
        return process.ExitCode;
    }

    private static async Task PumpAsync(StreamReader reader, string stream, Func<string, string, CancellationToken, Task> onChunk, CancellationToken ct)
    {
        var buffer = new char[4096];
        while (true)
        {
            var read = await reader.ReadAsync(buffer.AsMemory(), ct).ConfigureAwait(false);
            if (read == 0)
            {
                return;
            }

            await onChunk(stream, new string(buffer, 0, read), ct).ConfigureAwait(false);
        }
    }

    private static void TryKill(Process process)
    {
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
            }
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
        }
    }
}

public sealed class TerminalSession
{
    private readonly TerminalLog _log;
    private readonly object _gate = new();

    public TerminalSession(string logPath, ITerminalProcess? process = null)
    {
        _log = new TerminalLog(logPath);
        Process = process ?? new WindowsCommandProcess();
        Settings = new TerminalSettings();
    }

    public TerminalSettings Settings { get; }

    public ITerminalProcess Process { get; set; }

    public string LogPath => _log.Path;

    public void Update(bool enabled, bool allowlistOnly, IReadOnlyList<string> allowlist)
    {
        lock (_gate)
        {
            Settings.Enabled = enabled;
            Settings.AllowlistOnly = allowlistOnly;
            Settings.Allowlist = allowlist.ToList();
        }
    }

    public TerminalSettings Snapshot()
    {
        lock (_gate)
        {
            return new TerminalSettings
            {
                Enabled = Settings.Enabled,
                AllowlistOnly = Settings.AllowlistOnly,
                Allowlist = Settings.Allowlist.ToList()
            };
        }
    }

    public Task<ServiceResult> HandleAsync(
        ServiceRequest request,
        PeerRecord? peer,
        Func<TerminalChunk, CancellationToken, Task> emit,
        CancellationToken ct) =>
        TerminalHost.RunAsync(request, peer, Snapshot(), _log, Process, emit, ct);
}

public static class TerminalHost
{
    public static async Task<ServiceResult> RunAsync(
        ServiceRequest request,
        PeerRecord? peer,
        TerminalSettings settings,
        TerminalLog log,
        ITerminalProcess process,
        Func<TerminalChunk, CancellationToken, Task> emit,
        CancellationToken ct)
    {
        if (request.Op == "status")
        {
            if (peer is null)
            {
                return ServiceResult.Fail(request.Id, ErrorCodes.UnknownPeer, "Pair a device before using Terminal.");
            }

            var message = !settings.Enabled
                ? "Terminal is off on this PC."
                : settings.AllowlistOnly
                    ? "Terminal is on. Allowlist is on."
                    : "Terminal is on. Allowlist is off.";
            return ServiceResult.Done(request.Id, message);
        }

        if (request.Op != "run")
        {
            return ServiceResult.Fail(request.Id, ErrorCodes.NotImplemented, "That terminal action is not available.");
        }

        var command = ServiceCodec.ReadString(request.Body, "command") ?? "";
        var confirmed = request.Body.TryGetProperty("confirmed", out var flag) && flag.ValueKind == JsonValueKind.True;
        var signature = ServiceCodec.ReadString(request.Body, "sig");
        if (peer is null)
        {
            return ServiceResult.Fail(request.Id, ErrorCodes.UnknownPeer, "Pair a device before using Terminal.");
        }

        byte[] spki;
        try
        {
            spki = Base64Url.Decode(peer.PublicKeySpkiBase64Url);
        }
        catch (FormatException)
        {
            log.Write(peer.Name, peer.DeviceId.ToString("D"), "rejected", TerminalPolicy.Unauthenticated, null);
            return ServiceResult.Fail(request.Id, TerminalPolicy.Unauthenticated, "Command was not signed by the paired device.");
        }

        if (!TerminalAuth.Verify(spki, request.Id, command, confirmed, signature))
        {
            log.Write(peer.Name, peer.DeviceId.ToString("D"), "rejected", TerminalPolicy.Unauthenticated, null);
            return ServiceResult.Fail(request.Id, TerminalPolicy.Unauthenticated, "Command was not signed by the paired device.");
        }

        var invalid = TerminalPolicy.RejectReason(command);
        if (invalid is not null)
        {
            log.Write(peer.Name, peer.DeviceId.ToString("D"), "denied", ErrorCodes.BadRequest, command);
            return ServiceResult.Fail(request.Id, ErrorCodes.BadRequest, invalid);
        }

        if (!settings.Enabled)
        {
            log.Write(peer.Name, peer.DeviceId.ToString("D"), "denied", TerminalPolicy.Disabled, command);
            return ServiceResult.Fail(request.Id, TerminalPolicy.Disabled, "Terminal is off on this PC.");
        }

        if (TerminalPolicy.IsDestructive(command) && !confirmed)
        {
            log.Write(peer.Name, peer.DeviceId.ToString("D"), "denied", TerminalPolicy.NeedsConfirm, command);
            return ServiceResult.Fail(request.Id, TerminalPolicy.NeedsConfirm, "This command needs confirmation on the phone.");
        }

        if (settings.AllowlistOnly && !TerminalPolicy.IsAllowlisted(command, settings.Allowlist))
        {
            log.Write(peer.Name, peer.DeviceId.ToString("D"), "denied", TerminalPolicy.NotAllowed, command);
            return ServiceResult.Fail(request.Id, TerminalPolicy.NotAllowed, "That command is not on the allowlist.");
        }

        var sent = 0;
        var sequence = 0;
        var truncated = false;
        var gate = new object();
        try
        {
            var code = await process.RunAsync(command, async (stream, text, token) =>
            {
                if (string.IsNullOrEmpty(text) || stream is not ("out" or "err"))
                {
                    return;
                }

                string piece;
                int seq;
                lock (gate)
                {
                    if (sent >= TerminalPolicy.MaxOutputChars)
                    {
                        truncated = true;
                        return;
                    }

                    var room = TerminalPolicy.MaxOutputChars - sent;
                    piece = text.Length > room ? text[..room] : text;
                    sent += piece.Length;
                    if (text.Length > piece.Length)
                    {
                        truncated = true;
                    }

                    seq = ++sequence;
                }

                await emit(new TerminalChunk { Id = request.Id, Seq = seq, Stream = stream, Text = piece }, token).ConfigureAwait(false);
            }, ct).ConfigureAwait(false);

            var suffix = truncated ? " Output was truncated." : "";
            log.Write(peer.Name, peer.DeviceId.ToString("D"), "finished", code.ToString(), command);
            return code == 0
                ? ServiceResult.Done(request.Id, "Finished. Exit code 0." + suffix)
                : ServiceResult.Fail(request.Id, "exit", $"Finished. Exit code {code}.{suffix}");
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            log.Write(peer.Name, peer.DeviceId.ToString("D"), "cancelled", ErrorCodes.Cancelled, command);
            return ServiceResult.Fail(request.Id, ErrorCodes.Cancelled, "Cancelled.");
        }
        catch (TimeoutException)
        {
            log.Write(peer.Name, peer.DeviceId.ToString("D"), "denied", ErrorCodes.Limit, command);
            return ServiceResult.Fail(request.Id, ErrorCodes.Limit, "The command ran longer than 2 minutes.");
        }
        catch (Exception ex) when (ex is InvalidOperationException or PlatformNotSupportedException or IOException)
        {
            log.Write(peer.Name, peer.DeviceId.ToString("D"), "failed", ErrorCodes.Io, command);
            return ServiceResult.Fail(request.Id, ErrorCodes.Io, "The command could not be started.");
        }
    }
}
