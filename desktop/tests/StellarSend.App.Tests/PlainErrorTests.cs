using Xunit;

namespace StellarSend.App.Tests;

public sealed class PlainErrorTests
{
    [Fact]
    public void AnOfflinePhoneNeverSaysRepair()
    {
        var offline = StellarSend.Core.Transfer.RelayWake.PhoneOffline;
        Assert.Equal(PlainError.PhoneOfflineRow, PlainError.ShortTransfer(offline));
        Assert.False(PlainError.ShouldBanner(offline));
        Assert.Equal(HomeStatus.PhoneOffline, HomeStatus.Line(true, false, false, null, offline));
        Assert.Equal(HomeStatus.PhoneOffline, HomeStatus.Connection(true, true, StellarSend.Core.Transfer.RouteKind.Relay, false, repairNeeded: false, phoneOffline: true));
        Assert.Equal(HomeStatus.RepairNeeded, HomeStatus.Connection(true, true, StellarSend.Core.Transfer.RouteKind.Relay, false, repairNeeded: true, phoneOffline: true));
        Assert.DoesNotContain("pair", PlainError.PhoneOfflineRow, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("pair", HomeStatus.PhoneOffline, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void PortInUseIsARetryNotSomethingWentWrong()
    {
        const string raw = "Only one usage of each socket address (protocol/network address/port) is normally permitted.";
        Assert.True(PlainError.IsPortBusy(raw));
        Assert.True(PlainError.IsPortBusy(new System.Net.Sockets.SocketException(10048)));
        var shown = PlainError.Describe(raw);
        Assert.Equal(PlainError.PortBusyTitle, shown.Title);
        Assert.Equal(PlainError.PortBusyDetail, shown.Detail);
        Assert.DoesNotContain("Something went wrong", shown.Title, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain(raw, shown.Title, StringComparison.Ordinal);
    }

    [Fact]
    public void OtherErrorsStayInPlainWords()
    {
        var shown = PlainError.Describe("System.IO.IOException: HRESULT 0x80004005");
        Assert.Equal(PlainError.GenericTitle, shown.Title);
        Assert.Contains("Try again", shown.Detail, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("Something went wrong", shown.Title, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void OffWifiDoesNotReadLikePairingBroke()
    {
        const string raw = "Could not find the paired device. Checked mDNS, UDP broadcast, last-known IP 192.168.1.77.";
        Assert.True(PlainError.IsOffWifi(raw));
        Assert.False(PlainError.ShouldBanner(raw));
        Assert.False(PlainError.IsKeyChanged(raw));
        var shown = PlainError.Describe(raw);
        Assert.Equal(PlainError.OffWifi, shown.Title);
        Assert.DoesNotContain("mDNS", shown.Title, StringComparison.Ordinal);
        Assert.DoesNotContain("paired device", shown.Title, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(PlainError.OffWifi, PlainError.ShortTransfer(raw));
    }

    [Fact]
    public void RelayDropSaysRetryingNotUnpair()
    {
        const string raw = "The relay closed the connection (EndpointUnavailable).";
        Assert.True(PlainError.IsRelayDrop(raw));
        Assert.False(PlainError.ShouldBanner(raw));
        var shown = PlainError.Describe(raw);
        Assert.Equal(PlainError.RelayDropped, shown.Title);
        Assert.DoesNotContain("Unpair", shown.Title, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void ARealKeyChangeStillSaysUnpair()
    {
        const string raw = "The paired device key changed. Unpair both sides and pair again.";
        Assert.True(PlainError.IsKeyChanged(raw));
        Assert.True(PlainError.ShouldBanner(raw));
        Assert.False(PlainError.IsOffWifi(raw));
        Assert.Contains("Unpair both sides", PlainError.Describe(raw).Title, StringComparison.Ordinal);
    }
}
