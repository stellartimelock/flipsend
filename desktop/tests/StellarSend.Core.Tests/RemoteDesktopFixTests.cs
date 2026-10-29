using StellarSend.Core.Companion;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;
using StellarSend.Core.Shell;

namespace StellarSend.Core.Tests;

public sealed class RemoteDesktopFixTests
{
    [Fact]
    public void AnAllowedPhoneRequestKeepsTheLinkAndSkipsTheTransferBar()
    {
        Assert.True(RemoteDesktopSession.KeepsLink(RemoteControl.OpRequest, RemoteControl.FromPhone, true));
        Assert.True(RemoteDesktopSession.KeepsLink(RemoteControl.OpWatch, RemoteControl.FromPhone, true));
        Assert.False(RemoteDesktopSession.KeepsLink(RemoteControl.OpRequest, RemoteControl.FromPhone, false));
        Assert.False(RemoteDesktopSession.KeepsLink(RemoteControl.OpRequest, RemoteControl.FromPc, true));
        Assert.False(RemoteDesktopSession.KeepsLink(RemoteControl.OpStatus, RemoteControl.FromPhone, true));
        Assert.False(RemoteDesktopSession.ShowsTransferBar(RemoteDesktop.SessionCode));
        Assert.True(RemoteDesktopSession.ShowsTransferBar("ok"));
        Assert.Equal("Remote desktop active - Disconnect", RemoteControl.ActiveLine);
        Assert.Equal("Update the FlipSend app on this phone, then tap Remote desktop again.", RemoteControl.UpdatePhone);
    }

    [Fact]
    public void AClosedRequestLeavesTheAllowInPlaceForWatch()
    {
        var session = new RemoteSession { Paired = true };
        var id = Guid.NewGuid().ToString("D");
        Assert.True(session.Request(id, RemoteControl.FromPhone).Ok);
        Assert.True(session.Allow(id).Ok);
        Assert.True(session.BeginWatch(id));
        session.ParkWatch(id);
        Assert.True(session.IsActive(id));
        Assert.False(session.Watching);
        Assert.True(session.BeginWatch(id));
    }

    [Fact]
    public void ADroppedSessionAcceptsTheNextRequest()
    {
        var session = new RemoteSession { Paired = true };
        var id = Guid.NewGuid().ToString("D");
        Assert.True(session.Request(id, RemoteControl.FromPhone).Ok);
        Assert.True(session.Allow(id).Ok);
        Assert.False(session.Watching);
        var next = Guid.NewGuid().ToString("D");
        Assert.True(session.Request(next, RemoteControl.FromPhone).Ok);
        Assert.Equal(next, session.SessionId);
    }

    [Fact]
    public void ALiveWatchRejectsASecondSession()
    {
        var session = new RemoteSession { Paired = true };
        var id = Guid.NewGuid().ToString("D");
        session.Request(id, RemoteControl.FromPhone);
        session.Allow(id);
        Assert.True(session.BeginWatch(id));
        var busy = session.Request(Guid.NewGuid().ToString("D"), RemoteControl.FromPhone);
        Assert.False(busy.Ok);
        Assert.Equal(ErrorCodes.Limit, busy.Code);
        session.EndWatch(id);
        Assert.True(session.Request(Guid.NewGuid().ToString("D"), RemoteControl.FromPhone).Ok);
    }

    [Fact]
    public void FramesSplitUnderTheRelayCapAndThePumpDropsStaleOnes()
    {
        var payload = new byte[100_000];
        for (var i = 0; i < payload.Length; i++)
        {
            payload[i] = (byte)(i % 251);
        }

        var parts = FrameSlices.Split(payload);
        Assert.True(parts.Count > 1);
        Assert.All(parts, part => Assert.InRange(part.Length, 1, FrameSlices.MaxSlice));
        Assert.Equal(payload, FrameSlices.Join(parts));

        var pump = new FramePump();
        Assert.True(pump.Offer([1]));
        Assert.False(pump.Offer([2, 3]));
        Assert.Equal(1, pump.Dropped);
        Assert.Equal(new byte[] { 2, 3 }, pump.Take());
        Assert.Null(pump.Take());
    }

    [Fact]
    public void DialingTheRelayStopsTheLocalListen()
    {
        var gate = new RelayDialGate();
        Assert.True(gate.ShouldListen());
        gate.Begin();
        Assert.False(gate.ShouldListen());
        gate.End();
        Assert.True(gate.ShouldListen());
        Assert.False(PairingEnlarge.MovesRelay(paired: true));
        Assert.True(PairingEnlarge.MovesRelay(paired: false));
    }

    [Fact]
    public void AlwaysAllowUsesTheDeviceIdNotTheCertificatePin()
    {
        var id = Guid.NewGuid().ToString("D");
        var pin = new string('a', 64);
        Assert.True(AlwaysAllow.IsDeviceId(id));
        Assert.False(AlwaysAllow.IsDeviceId(pin));
        Assert.Null(PeerPin.OrNull(id));
        Assert.Equal(pin, PeerPin.OrNull(pin));
        var saved = AlwaysAllow.Add([], id);
        Assert.True(AlwaysAllow.Contains(saved, id.ToUpperInvariant()));
        Assert.False(AlwaysAllow.Contains([pin], id));
        Assert.False(AlwaysAllow.Contains(saved, pin));
    }

    [Fact]
    public void VirtualScreenKeepsANegativeOrigin()
    {
        var wide = DesktopCapturePlan.Choose(-1920, 0, 3840, 1080, 1920, 1080);
        Assert.Equal(-1920, wide.X);
        Assert.True(wide.Virtual);
        var huge = DesktopCapturePlan.Choose(0, 0, 10_000, 10_000, 1920, 1080);
        Assert.False(huge.Virtual);
        Assert.Equal(0, huge.X);
        Assert.Equal(1920, huge.Width);
    }

    [Fact]
    public void StartupStaysOnWhenTheRegistryIsOn()
    {
        Assert.True(StartupPreference.Resolve(saved: false, registryOn: true));
        Assert.True(StartupPreference.Resolve(saved: true, registryOn: false));
        Assert.False(StartupPreference.Resolve(saved: false, registryOn: false));
    }

    [Fact]
    public void ShowWindowHandoffIsNotSentAsAFile()
    {
        var directory = Directory.CreateTempSubdirectory("ss-show");
        var log = new ListLog();
        var clock = new ManualClock();
        var showed = false;
        var decision = SendQueue.OfferShow(directory.FullName, TimeSpan.FromSeconds(5), log, clock, delay =>
        {
            var files = SendQueue.Drain(directory.FullName, log, () => showed = true);
            Assert.Empty(files);
            clock.Now += delay;
        });

        Assert.Equal(HandoffDecision.Accepted, decision);
        Assert.True(showed);
        Assert.Contains(log.Lines, line => line.Contains("show its window", StringComparison.Ordinal));
        Assert.Empty(SendQueue.Drain(directory.FullName));
    }

    private sealed class ListLog : ILog
    {
        public List<string> Lines { get; } = [];
        public void Info(string message) => Lines.Add(message);
        public void Warn(string message) => Lines.Add(message);
        public void Error(string message) => Lines.Add(message);
    }

    private sealed class ManualClock : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = DateTimeOffset.UnixEpoch;
        public override DateTimeOffset GetUtcNow() => Now;
    }
}
