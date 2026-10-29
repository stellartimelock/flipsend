using System.Text;
using System.Text.Json;
using StellarSend.Core.Companion;
using StellarSend.Core.Memo;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;

namespace StellarSend.Core.Tests;

public sealed class MemoCommandTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.FromUnixTimeSeconds(1_700_000_000);

    [Fact]
    public void AnUnverifiedCommandDoesNotRun()
    {
        var secret = "https://example.com/hidden-memo";
        var executor = Executor(out var browser, out var locker, out var clip, out var notice);
        var decision = MemoCommandPolicy.Decide(Body("open_url", ("url", secret)), Now, senderVerified: false, _ => false);
        var rejected = Assert.IsType<MemoDecision.Rejected>(decision);
        Assert.Equal(MemoCodes.UnverifiedSender, rejected.Code);
        Assert.DoesNotContain("hidden-memo", rejected.Message);
        Assert.Equal(0, executor.Ran);
        Assert.Null(browser.Opened);
        Assert.Equal(0, locker.Locks);
        Assert.Null(clip.Text);
        Assert.Null(notice.Last);
    }

    [Fact]
    public void ExpiryReplayAndUnknownOpsAreRejected()
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var fresh = MemoCommandPolicy.Decide(Command("ping", Now.ToUnixTimeSeconds() + 60), Now, true, seen.Contains);
        var accepted = Assert.IsType<MemoDecision.Accepted>(fresh);
        seen.Add(accepted.Command.Id);
        Assert.Equal(MemoCodes.Replay, Rejected(Command("ping", Now.ToUnixTimeSeconds() + 60, accepted.Command.Id), true, seen.Contains).Code);
        Assert.Equal(MemoCodes.Expired, Rejected(Command("ping", Now.ToUnixTimeSeconds() - 121), true, _ => false).Code);
        Assert.Equal(ErrorCodes.BadRequest, Rejected(Command("ping", Now.ToUnixTimeSeconds() + MemoCommandLimits.MaxLifetimeSeconds + 1), true, _ => false).Code);
        var shell = Rejected(Command("shell", Now.ToUnixTimeSeconds() + 60), true, _ => false);
        Assert.Equal(ErrorCodes.NotImplemented, shell.Code);
        Assert.Contains("desktop", shell.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void OpenCopyLockAndPingStayInsideTheFourCommands()
    {
        var executor = Executor(out var browser, out var locker, out var clip, out var notice);
        var open = Accept("open_url", ("url", "https://example.com/private-path"));
        var opened = executor.Run(open, Now);
        Assert.IsType<MemoDecision.Accepted>(opened);
        Assert.Equal("https://example.com/private-path", browser.Opened);
        Assert.Equal("Opened on this PC.", notice.Last);
        Assert.DoesNotContain("private-path", notice.Last);

        Assert.Equal(ErrorCodes.BadRequest, Rejected(Body("open_url", ("url", "file:///etc/passwd")), true, _ => false).Code);
        Assert.Equal(ErrorCodes.BadRequest, Rejected(Body("open_url", ("url", "javascript:alert(1)")), true, _ => false).Code);
        Assert.Equal("https://example.com/private-path", browser.Opened);

        var copy = Accept("copy_text", ("text", "memo-secret"));
        executor.Run(copy, Now);
        Assert.Equal("memo-secret", clip.Text);
        Assert.Equal("Copied. It will clear in 30 seconds.", notice.Last);
        Assert.DoesNotContain("memo-secret", notice.Last);
        Assert.True(executor.Tick(Now.AddSeconds(30)));
        Assert.Equal("", clip.Text);

        locker.CanLock = true;
        executor.Run(Accept("lock_pc"), Now);
        Assert.Equal(1, locker.Locks);
        Assert.Equal("Locked this PC.", notice.Last);

        executor.Run(Accept("ping"), Now);
        Assert.Equal("Memo ping received.", notice.Last);
    }

    [Fact]
    public void LockIsNotCalledWhenThePcCannotLock()
    {
        var executor = Executor(out _, out var locker, out _, out _);
        var result = executor.Run(Accept("lock_pc"), Now);
        Assert.Equal(ErrorCodes.NotImplemented, Assert.IsType<MemoDecision.Rejected>(result).Code);
        Assert.Equal(0, locker.Locks);
    }

    [Fact]
    public async Task TheStubDoesNotWatchOrAcceptACiphertext()
    {
        var executor = Executor(out var browser, out var locker, out var clip, out _);
        var channel = new MemoCommandChannel(new DisconnectedMemoLedger(), new UnconfiguredMemoCrypto(), executor, () => Now);
        var snapshot = channel.Snapshot();
        Assert.False(snapshot.Watching);
        Assert.False(snapshot.ContactsNetwork);
        Assert.Contains("No account and no seed", snapshot.Status);
        Assert.Equal(0, await channel.PollOnceAsync(CancellationToken.None));

        var ledger = new ScriptedLedger(new MemoLedgerEntry(
            new string('a', 64),
            "G" + new string('A', 55),
            MemoCommandLimits.ChannelMemo,
            Encoding.UTF8.GetBytes("{\"seed\":\"SSECRET\"}"),
            Now.ToUnixTimeSeconds()));
        var armed = new MemoCommandChannel(ledger, new UnconfiguredMemoCrypto(), executor, () => Now);
        Assert.Equal(0, await armed.PollOnceAsync(CancellationToken.None));
        Assert.Equal(0, executor.Ran);
        Assert.Null(browser.Opened);
        Assert.Equal(0, locker.Locks);
        Assert.Null(clip.Text);
        Assert.Equal("[redacted]", RedactingFileLog.Redact("{\"seed\":\"SSECRET\"}"));
        Assert.Equal("[redacted]", RedactingFileLog.Redact("{\"mnemonic\":\"word word\"}"));
    }

    [Fact]
    public async Task AVerifiedPingRunsOnce()
    {
        var executor = Executor(out _, out _, out _, out var notice);
        var id = Guid.NewGuid().ToString("D");
        var plain = Command("ping", Now.ToUnixTimeSeconds() + 30, id);
        var entry = new MemoLedgerEntry(new string('b', 64), "G" + new string('A', 55), MemoCommandLimits.ChannelMemo, plain, Now.ToUnixTimeSeconds());
        var channel = new MemoCommandChannel(new ScriptedLedger(entry), new TrustingCrypto(plain), executor, () => Now);
        Assert.Equal(1, await channel.PollOnceAsync(CancellationToken.None));
        Assert.Equal("Memo ping received.", notice.Last);
        Assert.Equal(0, await channel.PollOnceAsync(CancellationToken.None));
        Assert.Equal(1, executor.Ran);
    }

    private static MemoDecision.Rejected Rejected(byte[] plaintext, bool senderVerified, Func<string, bool> seen) =>
        Assert.IsType<MemoDecision.Rejected>(MemoCommandPolicy.Decide(plaintext, Now, senderVerified, seen));

    private static VerifiedMemoCommand Accept(string op, params (string Name, string Value)[] fields)
    {
        var decision = MemoCommandPolicy.Decide(Body(op, fields), Now, true, _ => false);
        return Assert.IsType<MemoDecision.Accepted>(decision).Command;
    }

    private static byte[] Body(string op, params (string Name, string Value)[] fields)
    {
        var values = new Dictionary<string, object?>
        {
            ["v"] = 1,
            ["id"] = Guid.NewGuid().ToString("D"),
            ["exp"] = Now.ToUnixTimeSeconds() + 60,
            ["op"] = op
        };
        foreach (var (name, value) in fields)
        {
            values[name] = value;
        }

        return JsonSerializer.SerializeToUtf8Bytes(values);
    }

    private static byte[] Command(string op, long exp, string? id = null) =>
        JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object?>
        {
            ["v"] = 1,
            ["id"] = id ?? Guid.NewGuid().ToString("D"),
            ["exp"] = exp,
            ["op"] = op
        });

    private static MemoCommandExecutor Executor(out FakeBrowser browser, out FakeLock locker, out MemoryClipboard clip, out MemoryNotice notice)
    {
        browser = new FakeBrowser();
        locker = new FakeLock();
        clip = new MemoryClipboard();
        notice = new MemoryNotice();
        return new MemoCommandExecutor(clip, notice, browser, locker);
    }

    private sealed class ScriptedLedger(MemoLedgerEntry entry) : IMemoLedger
    {
        public bool ContactsNetwork => true;
        public Task<MemoPoll> PollAsync(string? cursor, CancellationToken ct) =>
            Task.FromResult(new MemoPoll(cursor, [entry]));
    }

    private sealed class TrustingCrypto(byte[] plaintext) : IMemoCrypto
    {
        public bool IsConfigured => true;

        public MemoDecision Open(MemoLedgerEntry entry, DateTimeOffset now, Func<string, bool> alreadySeen) =>
            MemoCommandPolicy.Decide(plaintext, now, true, alreadySeen);
    }

    private sealed class MemoryClipboard : ISecretClipboard
    {
        public string? Text { get; set; }
        public void SetText(string text) => Text = text;
        public string? GetText() => Text;
    }

    private sealed class MemoryNotice : IUserNotice
    {
        public string? Last { get; private set; }
        public void Show(string text) => Last = text;
    }

    private sealed class FakeBrowser : IBrowserSurface
    {
        public bool CanOpen => true;
        public bool CanView => false;
        public bool CanInput => false;
        public string? Opened { get; private set; }
        public void Open(string url) => Opened = url;
        public byte[]? Capture() => null;
        public void Input(string kind, double x, double y, string? text) => throw new InvalidOperationException("input");
    }

    private sealed class FakeLock : IPcLock
    {
        public bool CanLock { get; set; }
        public int Locks { get; private set; }
        public void Lock() => Locks++;
    }
}
