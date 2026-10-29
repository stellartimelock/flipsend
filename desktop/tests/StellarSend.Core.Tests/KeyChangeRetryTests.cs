using StellarSend.Core.Protocol;
using StellarSend.Core.Transfer;

namespace StellarSend.Core.Tests;

public sealed class KeyChangeRetryTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 18, 16, 0, 0, TimeSpan.Zero);
    private static readonly DateTimeOffset Recent = Now.AddDays(-2);
    private static readonly DateTimeOffset Stale = Now.AddDays(-8);

    [Fact]
    public void FallbackKeyChangedIsRetryableWhenThePinWorkedThisWeek()
    {
        Assert.True(KeyChangeRetry.IsTransient(TransferPath.Relay, Recent, consecutive: 1, Now));
        Assert.True(KeyChangeRetry.IsTransient(TransferPath.Bluetooth, Recent, consecutive: 2, Now));
        Assert.True(KeyChangeRetry.ShouldRetry(TransferPath.Relay, Recent, 1, attempt: 1, Now));
        Assert.Equal("Retrying.", KeyChangeRetry.Retrying);
    }

    [Fact]
    public void LanKeyChangedStaysTerminal()
    {
        Assert.False(KeyChangeRetry.IsTransient(TransferPath.Direct, Recent, 1, Now));
        Assert.False(KeyChangeRetry.IsTransient(TransferPath.Cross, Recent, 1, Now));
        Assert.False(KeyChangeRetry.IsTransient("", Recent, 1, Now));
        Assert.False(KeyChangeRetry.ShouldRetry(TransferPath.Direct, Recent, 1, 1, Now));
    }

    [Fact]
    public void ThreeKeyChangedInARowStayTerminal()
    {
        Assert.False(KeyChangeRetry.IsTransient(TransferPath.Relay, Recent, KeyChangeRetry.MaxConsecutive, Now));
        Assert.False(KeyChangeRetry.ShouldRetry(TransferPath.Relay, Recent, 3, attempt: 3, Now));
    }

    [Fact]
    public void StalePinIsTerminalEvenOnRelay()
    {
        Assert.False(KeyChangeRetry.IsTransient(TransferPath.Relay, Stale, 1, Now));
        Assert.False(KeyChangeRetry.IsTransient(TransferPath.Relay, lastPinOk: null, 1, Now));
    }

    [Fact]
    public void RetryStillCountsTowardTheNormalFiveAttempts()
    {
        Assert.True(KeyChangeRetry.ShouldRetry(TransferPath.Relay, Recent, 1, attempt: 4, Now));
        Assert.False(KeyChangeRetry.ShouldRetry(TransferPath.Relay, Recent, 1, attempt: RetryPolicy.MaxAttempts, Now));
    }

    [Fact]
    public void LogLineNamesTheSideAndFirstEightHex()
    {
        var presented = "4c82ca75660bad0ec55d3c7f8a43e03a1f2e9014abcdabcd";
        var pinned = "a18342c2fe21cf20c716d7d590c9635eaa4f06c3abcdabcd";
        var device = "33333333-3333-3333-3333-333333333333";
        var local = KeyChangedLog.Format(KeyChangedLog.LocalCheck, presented, pinned, device);
        Assert.Contains("local check", local, StringComparison.Ordinal);
        Assert.Contains("presented=4c82ca75", local, StringComparison.Ordinal);
        Assert.Contains("pinned=a18342c2", local, StringComparison.Ordinal);
        Assert.Contains("device=33333333", local, StringComparison.Ordinal);

        var phone = KeyChangedLog.Format(KeyChangedLog.PhoneSent, null, pinned, device);
        Assert.Contains("phone-sent key_changed", phone, StringComparison.Ordinal);
        Assert.Contains("presented=none", phone, StringComparison.Ordinal);
        Assert.Equal(ErrorCodes.KeyChanged, PeerRejectedException.KeyChanged().Code);
        Assert.Contains("Unpair both sides", PeerRejectedException.KeyChanged().Message, StringComparison.Ordinal);
    }

    [Fact]
    public void OwnKeyKeyChangedIsASelfDial()
    {
        var own = new string('a', 64);
        var phone = new string('b', 64);
        KeyChangedLog.Write(KeyChangedLog.LocalCheck, own, phone, "33333333-3333-3333-3333-333333333333");
        var rejected = PeerRejectedException.KeyChanged();
        Assert.True(KeyChangeRetry.IsOwnKey(rejected, own));
        Assert.False(KeyChangeRetry.IsOwnKey(rejected, phone));
        Assert.False(KeyChangeRetry.IsOwnKey(new IOException("socket"), own));
        var mapped = KeyChangeRetry.MapOwnKey(rejected, own);
        Assert.IsType<SelfDialException>(mapped);
        Assert.Equal(DeliveryFault.SelfAnswered, mapped.Message);
        Assert.Same(rejected, KeyChangeRetry.MapOwnKey(rejected, phone));
    }

    [Fact]
    public void PinOkFileRoundTrips()
    {
        var directory = Directory.CreateTempSubdirectory("ss-pin-ok");
        var paired = Now.AddDays(-3);
        Assert.Equal(paired, PinOkStore.Load(directory.FullName, paired));
        PinOkStore.Save(directory.FullName, Recent);
        Assert.Equal(Recent, PinOkStore.Load(directory.FullName, paired));
    }
}
