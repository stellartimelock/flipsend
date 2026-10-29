using Xunit;

namespace StellarSend.App.Tests;

public sealed class PhoneDisconnectTests
{
    [Fact]
    public async Task UnpairDoesNothingUntilConfirm()
    {
        var ran = false;
        var ok = await PhoneDisconnect.TryAsync(() => Task.FromResult(false), () => ran = true);
        Assert.False(ok);
        Assert.False(ran);
        Assert.Equal("Disconnect phone", PhoneDisconnect.Title);
        Assert.Equal("This removes the pairing. You'll need to scan the code again.", PhoneDisconnect.Body);
        Assert.Equal("Disconnect phone", PhoneDisconnect.Action);
    }

    [Fact]
    public async Task UnpairRunsOnlyAfterConfirm()
    {
        var ran = false;
        var ok = await PhoneDisconnect.TryAsync(() => Task.FromResult(true), () => ran = true);
        Assert.True(ok);
        Assert.True(ran);
    }

    [Fact]
    public void BackupCopiesPeerFilesToATimestampedBakAndKeepsTheOriginal()
    {
        var directory = Directory.CreateTempSubdirectory("ss-peer-bak");
        var peer = Path.Combine(directory.FullName, "peer.bin");
        var rotated = Path.Combine(directory.FullName, "peer.bin.bak.1");
        File.WriteAllText(peer, "live-peer");
        File.WriteAllText(rotated, "older-peer");
        var when = new DateTimeOffset(2026, 10, 18, 15, 4, 5, TimeSpan.Zero);

        var copies = PhoneDisconnect.BackupPeerFiles(directory.FullName, when);

        Assert.Equal(2, copies.Count);
        var stamped = Path.Combine(directory.FullName, "peer.bin.20261018-150405.bak");
        var stampedRotated = Path.Combine(directory.FullName, "peer.bin.bak.1.20261018-150405.bak");
        Assert.Contains(stamped, copies);
        Assert.Contains(stampedRotated, copies);
        Assert.Equal("live-peer", File.ReadAllText(peer));
        Assert.Equal("live-peer", File.ReadAllText(stamped));
        Assert.Equal("older-peer", File.ReadAllText(stampedRotated));
    }
}
