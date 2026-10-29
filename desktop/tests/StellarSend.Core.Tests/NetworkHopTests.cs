using StellarSend.Core.Transfer;

namespace StellarSend.Core.Tests;

public sealed class NetworkHopTests
{
    [Fact]
    public void TheFirstSnapshotIsNotAChange()
    {
        Assert.False(NetworkHop.Changed(null, new[] { "192.168.1.77" }));
    }

    [Fact]
    public void TheSameAddressesInAnotherOrderAreNotAChange()
    {
        Assert.False(NetworkHop.Changed(
            new[] { "192.168.1.77", "10.0.0.4" },
            new[] { "10.0.0.4", "192.168.1.77", " " }));
    }

    [Fact]
    public void ANewOrMissingAddressIsAChange()
    {
        Assert.True(NetworkHop.Changed(new[] { "192.168.1.77" }, new[] { "10.0.0.8" }));
        Assert.True(NetworkHop.Changed(new[] { "192.168.1.77" }, Array.Empty<string>()));
    }
}

public sealed class ConnectionFaceTests
{
    [Fact]
    public void EveryIndicatorAgrees()
    {
        var relay = ConnectionState.Face(Sample(directAvailable: true, relayUp: true, relayAvailable: true));
        Assert.True(relay.Agrees());
        Assert.Equal("Connected", relay.Headline);
        Assert.Equal("Relay", relay.PathPill);
        Assert.Equal("Relay now, Direct available (best), tap to switch", relay.RoutesLine);
        Assert.Equal(RouteKind.Direct, relay.SwitchTo);

        var missed = ConnectionState.Face(Sample(onLan: true, relayUp: true, relayAvailable: true));
        Assert.True(missed.Agrees());
        Assert.Equal("Same Wi-Fi did not answer.", missed.Hint);
        Assert.Contains("LAN available (best)", missed.RoutesLine);

        var offline = ConnectionState.Face(Sample(onLan: true, relayAvailable: true));
        Assert.True(offline.Agrees());
        Assert.Equal("Not connected", offline.Headline);
        Assert.Equal("", offline.PathPill);
        Assert.DoesNotContain("Connected", string.Join(" ", offline.Indicators()));

        var usb = ConnectionState.Face(Sample(usbUp: true, usbAvailable: true));
        Assert.Equal("USB", usb.PathPill);
        Assert.True(usb.Agrees());
    }

    private static ConnectionInput Sample(
        bool onLan = false,
        bool lanUp = false,
        bool directUp = false,
        bool directAvailable = false,
        bool relayUp = false,
        bool relayAvailable = false,
        bool usbUp = false,
        bool usbAvailable = false) =>
        new(true, true, false, onLan, lanUp, directUp, directAvailable, relayUp, relayAvailable, usbUp, usbAvailable);
}
