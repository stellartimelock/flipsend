using StellarSend.Core.Pairing;

namespace StellarSend.Core.Tests;

public sealed class PeerTrustTests
{
    [Fact]
    public void SamePhoneKeepsTheStoredPinAndMergesHosts()
    {
        var phone = Guid.Parse("11111111-1111-1111-1111-111111111111");
        var stored = new PeerRecord(phone, "Phone", new string('a', 64), "old-key", "192.168.1.20", 53321, DateTimeOffset.UnixEpoch, new[] { "192.168.1.20" });
        var incoming = stored with
        {
            Name = "Alex's phone",
            Fingerprint = new string('b', 64),
            PublicKeySpkiBase64Url = "new-key",
            LastHost = "10.0.0.8",
            Hosts = new[] { "10.0.0.8" },
            PairedAt = DateTimeOffset.UtcNow
        };

        var merged = PeerTrust.Merge(stored, incoming);
        Assert.Equal(stored.Fingerprint, merged.Fingerprint);
        Assert.Equal(stored.PublicKeySpkiBase64Url, merged.PublicKeySpkiBase64Url);
        Assert.Equal(stored.PairedAt, merged.PairedAt);
        Assert.Equal("Alex's phone", merged.Name);
        Assert.Equal("10.0.0.8", merged.LastHost);
        Assert.Contains("192.168.1.20", merged.Hosts!);
        Assert.Contains("10.0.0.8", merged.Hosts!);
        Assert.True(PeerTrust.ShouldWrite(stored, merged));
        Assert.False(PeerTrust.ShouldWrite(stored, stored));
    }

    [Fact]
    public void ADifferentPhoneReplacesTrust()
    {
        var stored = new PeerRecord(Guid.NewGuid(), "Phone", new string('a', 64), "old-key", "192.168.1.20", 53321, DateTimeOffset.UnixEpoch);
        var other = stored with { DeviceId = Guid.NewGuid(), Fingerprint = new string('c', 64) };
        var merged = PeerTrust.Merge(stored, other);
        Assert.Equal(other.DeviceId, merged.DeviceId);
        Assert.Equal(other.Fingerprint, merged.Fingerprint);
        Assert.True(PeerTrust.ShouldWrite(stored, merged));
    }
}
