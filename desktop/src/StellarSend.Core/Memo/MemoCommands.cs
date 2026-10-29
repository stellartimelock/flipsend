using System.Text.Json;
using StellarSend.Core.Companion;
using StellarSend.Core.Protocol;

namespace StellarSend.Core.Memo;

public static class MemoCommandLimits
{
    public const int Version = 1;
    public const string ChannelMemo = "sscmd.1";
    public const int MaxCiphertextBytes = 512;
    public const int MaxPlaintextBytes = 512;
    public const int MaxCopyChars = 256;
    public const int MaxUrlChars = 256;
    public const int ClockSkewSeconds = 120;
    public const int MaxLifetimeSeconds = 86_400;
}

public static class MemoCodes
{
    public const string UnverifiedSender = "unverified_sender";
    public const string Expired = "expired";
    public const string Replay = "replay";
    public const string NotConfigured = "not_configured";
}

public sealed record MemoLedgerEntry(string TransactionId, string SourceAccount, string MemoText, byte[] Ciphertext, long LedgerTimeUnix);

public sealed record MemoPoll(string? Cursor, IReadOnlyList<MemoLedgerEntry> Entries);

public interface IMemoLedger
{
    bool ContactsNetwork { get; }
    Task<MemoPoll> PollAsync(string? cursor, CancellationToken ct);
}

public sealed class DisconnectedMemoLedger : IMemoLedger
{
    public bool ContactsNetwork => false;

    public Task<MemoPoll> PollAsync(string? cursor, CancellationToken ct) =>
        Task.FromResult(new MemoPoll(cursor, []));
}

public interface IMemoCrypto
{
    bool IsConfigured { get; }
    MemoDecision Open(MemoLedgerEntry entry, DateTimeOffset now, Func<string, bool> alreadySeen);
}

public sealed class UnconfiguredMemoCrypto : IMemoCrypto
{
    public bool IsConfigured => false;

    public MemoDecision Open(MemoLedgerEntry entry, DateTimeOffset now, Func<string, bool> alreadySeen)
    {
        if (entry.Ciphertext.Length == 0 || entry.Ciphertext.Length > MemoCommandLimits.MaxCiphertextBytes)
        {
            return MemoDecision.Reject(ErrorCodes.Limit, "That memo command is empty or too large.");
        }

        return MemoDecision.Reject(MemoCodes.NotConfigured, "Memo commands are not unlocked. No seed was read and no account was watched.");
    }
}

public abstract record MemoDecision
{
    public sealed record Accepted(VerifiedMemoCommand Command) : MemoDecision;
    public sealed record Rejected(string Code, string Message) : MemoDecision;

    public static MemoDecision Reject(string code, string message) => new Rejected(code, message);
}

public sealed class VerifiedMemoCommand
{
    private VerifiedMemoCommand(string id, string op, string? url, string? text)
    {
        Id = id;
        Op = op;
        Url = url;
        Text = text;
    }

    public string Id { get; }
    public string Op { get; }
    internal string? Url { get; }
    internal string? Text { get; }

    internal static VerifiedMemoCommand Create(string id, string op, string? url, string? text) =>
        new(id, op, url, text);
}

public static class MemoCommandPolicy
{
    public static MemoDecision Decide(ReadOnlySpan<byte> plaintext, DateTimeOffset now, bool senderVerified, Func<string, bool> alreadySeen)
    {
        if (plaintext.Length == 0 || plaintext.Length > MemoCommandLimits.MaxPlaintextBytes)
        {
            return MemoDecision.Reject(ErrorCodes.Limit, "That memo command is empty or too large.");
        }

        if (!senderVerified)
        {
            return MemoDecision.Reject(MemoCodes.UnverifiedSender, "The memo sender was not verified. Nothing was run.");
        }

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(plaintext.ToArray());
        }
        catch (JsonException)
        {
            return MemoDecision.Reject(ErrorCodes.BadRequest, "Unreadable memo command.");
        }

        using (document)
        {
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("v", out var version) || version.ValueKind != JsonValueKind.Number || !version.TryGetInt32(out var parsedVersion) || parsedVersion != MemoCommandLimits.Version)
            {
                return MemoDecision.Reject(ErrorCodes.BadRequest, "Unsupported memo command.");
            }

            if (!root.TryGetProperty("id", out var idValue) || idValue.ValueKind != JsonValueKind.String || !Guid.TryParse(idValue.GetString(), out var id))
            {
                return MemoDecision.Reject(ErrorCodes.BadRequest, "Unreadable memo command.");
            }

            if (!root.TryGetProperty("exp", out var expValue) || expValue.ValueKind != JsonValueKind.Number || !expValue.TryGetInt64(out var exp))
            {
                return MemoDecision.Reject(ErrorCodes.BadRequest, "Unreadable memo command.");
            }

            var nowSeconds = now.ToUnixTimeSeconds();
            if (nowSeconds > exp + MemoCommandLimits.ClockSkewSeconds)
            {
                return MemoDecision.Reject(MemoCodes.Expired, "That memo command has expired.");
            }

            if (exp > nowSeconds + MemoCommandLimits.MaxLifetimeSeconds)
            {
                return MemoDecision.Reject(ErrorCodes.BadRequest, "That memo command expires too far ahead.");
            }

            var commandId = id.ToString("D");
            if (alreadySeen(commandId))
            {
                return MemoDecision.Reject(MemoCodes.Replay, "That memo command was already used.");
            }

            var op = root.TryGetProperty("op", out var opValue) && opValue.ValueKind == JsonValueKind.String ? opValue.GetString() : null;
            return op switch
            {
                "ping" => Accept(commandId, "ping", null, null),
                "lock_pc" => Accept(commandId, "lock_pc", null, null),
                "open_url" => OpenUrl(root, commandId),
                "copy_text" => CopyText(root, commandId),
                _ => MemoDecision.Reject(ErrorCodes.NotImplemented, "That memo command is not available. The desktop is not controlled.")
            };
        }
    }

    private static MemoDecision OpenUrl(JsonElement root, string id)
    {
        var url = ReadString(root, "url");
        if (url is null || url.Length > MemoCommandLimits.MaxUrlChars)
        {
            return MemoDecision.Reject(ErrorCodes.BadRequest, url is null ? "Enter an http or https link." : "That link is too long for a memo command.");
        }

        if (!BrowserUrls.TryNormalize(url, out var absolute, out var error))
        {
            return MemoDecision.Reject(ErrorCodes.BadRequest, error);
        }

        return Accept(id, "open_url", absolute, null);
    }

    private static MemoDecision CopyText(JsonElement root, string id)
    {
        var text = ReadString(root, "text");
        if (string.IsNullOrEmpty(text))
        {
            return MemoDecision.Reject(ErrorCodes.BadRequest, "Text is empty.");
        }

        if (text.Length > MemoCommandLimits.MaxCopyChars)
        {
            return MemoDecision.Reject(ErrorCodes.BadRequest, "Text is too long for a memo command.");
        }

        return Accept(id, "copy_text", null, text);
    }

    private static string? ReadString(JsonElement root, string name)
    {
        if (!root.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.String)
        {
            return null;
        }

        return value.GetString();
    }

    private static MemoDecision Accept(string id, string op, string? url, string? text) =>
        new MemoDecision.Accepted(VerifiedMemoCommand.Create(id, op, url, text));
}

public interface IPcLock
{
    bool CanLock { get; }
    void Lock();
}

public sealed class UnavailablePcLock : IPcLock
{
    public bool CanLock => false;
    public void Lock() => throw new InvalidOperationException("Lock is not available.");
}

public sealed class MemoCommandExecutor
{
    private readonly ISecretClipboard _clipboard;
    private readonly IUserNotice _notice;
    private readonly IBrowserSurface _browser;
    private readonly IPcLock _lock;
    private string? _secret;
    private DateTimeOffset _placedAt;

    public MemoCommandExecutor(ISecretClipboard clipboard, IUserNotice notice, IBrowserSurface browser, IPcLock pcLock)
    {
        _clipboard = clipboard;
        _notice = notice;
        _browser = browser;
        _lock = pcLock;
    }

    public int Ran { get; private set; }

    public MemoDecision Run(VerifiedMemoCommand command, DateTimeOffset now)
    {
        switch (command.Op)
        {
            case "ping":
                Ran++;
                _notice.Show("Memo ping received.");
                return new MemoDecision.Accepted(command);
            case "open_url":
                if (!_browser.CanOpen || string.IsNullOrEmpty(command.Url))
                {
                    return MemoDecision.Reject(ErrorCodes.NotImplemented, "Opening links is not available.");
                }

                _browser.Open(command.Url);
                Ran++;
                _notice.Show("Opened on this PC.");
                return new MemoDecision.Accepted(command);
            case "copy_text":
                if (string.IsNullOrEmpty(command.Text))
                {
                    return MemoDecision.Reject(ErrorCodes.BadRequest, "Text is empty.");
                }

                _clipboard.SetText(command.Text);
                _secret = command.Text;
                _placedAt = now;
                Ran++;
                _notice.Show("Copied. It will clear in 30 seconds.");
                return new MemoDecision.Accepted(command);
            case "lock_pc":
                if (!_lock.CanLock)
                {
                    return MemoDecision.Reject(ErrorCodes.NotImplemented, "Locking this PC is not available.");
                }

                try
                {
                    _lock.Lock();
                }
                catch (Exception)
                {
                    return MemoDecision.Reject(ErrorCodes.Io, "This PC could not be locked.");
                }

                Ran++;
                _notice.Show("Locked this PC.");
                return new MemoDecision.Accepted(command);
            default:
                return MemoDecision.Reject(ErrorCodes.NotImplemented, "That memo command is not available. The desktop is not controlled.");
        }
    }

    public bool Tick(DateTimeOffset now)
    {
        var secret = _secret;
        if (secret is null)
        {
            return false;
        }

        if (!ClipboardPolicy.ShouldClear(_placedAt, now, _clipboard.GetText(), secret, TimeSpan.FromSeconds(CompanionServices.MemoClearAfterSeconds)))
        {
            if (now - _placedAt >= TimeSpan.FromSeconds(CompanionServices.MemoClearAfterSeconds))
            {
                _secret = null;
            }

            return false;
        }

        _clipboard.SetText("");
        _secret = null;
        return true;
    }
}

public sealed record MemoChannelSnapshot(string Title, string Summary, string Status, bool Watching, bool ContactsNetwork);

public sealed class MemoCommandChannel
{
    private readonly IMemoLedger _ledger;
    private readonly IMemoCrypto _crypto;
    private readonly MemoCommandExecutor _executor;
    private readonly MemoReplayLog _replay = new();
    private string? _cursor;

    public MemoCommandChannel(IMemoLedger ledger, IMemoCrypto crypto, MemoCommandExecutor executor, Func<DateTimeOffset>? clock = null)
    {
        _ledger = ledger;
        _crypto = crypto;
        _executor = executor;
        Clock = clock ?? (() => DateTimeOffset.UtcNow);
    }

    public Func<DateTimeOffset> Clock { get; }

    public MemoChannelSnapshot Snapshot() => new(
        "Stellar memo commands",
        "Small commands can arrive from your Stellar account without a live link to the phone. This PC runs one only after the sender is verified. Remote Browser stays on the local network for a live view. The desktop is not controlled.",
        _crypto.IsConfigured && _ledger.ContactsNetwork
            ? "Watching the configured account."
            : "Not watching. No account and no seed are stored in this version.",
        _crypto.IsConfigured && _ledger.ContactsNetwork,
        _ledger.ContactsNetwork);

    public async Task<int> PollOnceAsync(CancellationToken ct)
    {
        if (!_crypto.IsConfigured || !_ledger.ContactsNetwork)
        {
            return 0;
        }

        var poll = await _ledger.PollAsync(_cursor, ct).ConfigureAwait(false);
        _cursor = poll.Cursor;
        var acted = 0;
        foreach (var entry in poll.Entries)
        {
            if (!string.Equals(entry.MemoText, MemoCommandLimits.ChannelMemo, StringComparison.Ordinal))
            {
                continue;
            }

            var decision = _crypto.Open(entry, Clock(), _replay.Seen);
            if (decision is not MemoDecision.Accepted accepted)
            {
                continue;
            }

            var run = _executor.Run(accepted.Command, Clock());
            if (run is MemoDecision.Accepted)
            {
                _replay.Remember(accepted.Command.Id);
                acted++;
            }
        }

        return acted;
    }

    public bool Tick() => _executor.Tick(Clock());
}

public sealed class MemoReplayLog
{
    private readonly HashSet<string> _ids = new(StringComparer.Ordinal);

    public bool Seen(string id) => _ids.Contains(id);

    public void Remember(string id) => _ids.Add(id);
}
