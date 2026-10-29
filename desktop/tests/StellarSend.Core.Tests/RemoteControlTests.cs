using System.Text;
using System.Text.Json;
using StellarSend.Core.Companion;
using StellarSend.Core.Protocol;

namespace StellarSend.Core.Tests;

public sealed class RemoteControlTests
{
    [Fact]
    public void RemoteInputIsTypeFourteenAndUsesThePairedService()
    {
        var frame = FrameCodec.Encode(MessageType.RemoteInput, Encoding.UTF8.GetBytes("{}"));
        Assert.Equal(14, frame[5]);
        Assert.Equal(14, (byte)MessageType.RemoteInput);
        Assert.Equal("remote.control", CompanionServices.RemoteControl);
        Assert.Equal("remote.control", RemoteControl.Service);
        Assert.Equal("remote_control", RemoteControl.PrefKey);
        Assert.Equal(720, RemoteControl.MaxWidth);
        Assert.Equal("Remote desktop", RemoteControl.Label);
        Assert.Equal(RemoteDesktop.Label, RemoteControl.Label);
        var held = RemoteInputCodec.Parse(Encoding.UTF8.GetBytes($"{{\"kind\":\"longpress\",\"session\":\"{Guid.NewGuid():D}\",\"x\":0.4,\"y\":0.6}}"));
        Assert.Equal("longpress", held!.Kind);
        Assert.Equal("right", RemotePointer.Button(held.Kind));
    }

    [Fact]
    public void JpegScaleKeepsTheSameAspectAndMapsBackToTheDevice()
    {
        var (jw, jh) = RemoteCoords.JpegSize(1440, 3200);
        Assert.Equal(720, jw);
        Assert.Equal(1600, jh);
        var (x, y) = RemoteCoords.ToDevice(0.25, 0.5, 1440, 3200);
        Assert.Equal(360, x);
        Assert.Equal(1600, y);
        var (dx, dy) = RemoteCoords.JpegToDevice(180, 800, 720, 1600, 1440, 3200);
        Assert.Equal(360, dx);
        Assert.Equal(1600, dy);
        var (fullW, fullH) = RemoteCoords.JpegSize(720, 1280);
        Assert.Equal(720, fullW);
        Assert.Equal(1280, fullH);
    }

    [Fact]
    public void ViewClicksIgnoreLetterboxAndStayOnTheImage()
    {
        var hit = RemoteCoords.FromView(200, 50, 400, 200, 100, 200);
        Assert.NotNull(hit);
        Assert.Equal(0.5, hit.Value.X, 3);
        Assert.Equal(0.25, hit.Value.Y, 3);
        Assert.Null(RemoteCoords.FromView(10, 10, 400, 200, 100, 200));
        Assert.Null(RemoteCoords.FromView(0, 0, 0, 400, 100, 200));
    }

    [Fact]
    public void InputJsonRejectsUnknownKindsAndOverlongText()
    {
        var session = Guid.NewGuid().ToString("D");
        using var tapDoc = JsonDocument.Parse($"{{\"kind\":\"tap\",\"session\":\"{session}\",\"x\":0.2,\"y\":1.5}}");
        var tap = RemoteInputCodec.Parse(tapDoc.RootElement);
        Assert.NotNull(tap);
        Assert.Equal("tap", tap!.Kind);
        Assert.Equal(0.2, tap.X, 4);
        Assert.Equal(1.0, tap.Y, 4);
        using var badKind = JsonDocument.Parse($"{{\"kind\":\"explode\",\"session\":\"{session}\"}}");
        Assert.Null(RemoteInputCodec.Parse(badKind.RootElement));
        using var badSession = JsonDocument.Parse("{\"kind\":\"tap\",\"session\":\"nope\"}");
        Assert.Null(RemoteInputCodec.Parse(badSession.RootElement));
        using var longText = JsonDocument.Parse($"{{\"kind\":\"text\",\"session\":\"{session}\",\"text\":\"{new string('x', RemoteControl.MaxTextChars + 1)}\"}}");
        Assert.Null(RemoteInputCodec.Parse(longText.RootElement));
        var text = new RemoteInput("text", session, Text: "hi");
        var round = RemoteInputCodec.Parse(RemoteInputCodec.Encode(text));
        Assert.Equal("text", round!.Kind);
        Assert.Equal("hi", round.Text);
    }

    [Fact]
    public void SessionNeedsPairToggleAllowThenStop()
    {
        var session = new RemoteSession();
        var id = Guid.NewGuid().ToString("D");
        var unpaired = session.Request(id, RemoteControl.FromPc);
        Assert.False(unpaired.Ok);
        Assert.Equal(ErrorCodes.UnknownPeer, unpaired.Code);
        session.Paired = true;
        var off = session.Request(id, RemoteControl.FromPc);
        Assert.False(off.Ok);
        Assert.Equal(RemoteControl.NeedToggle, off.Message);
        session.Enabled = true;
        var asked = session.Request(id, RemoteControl.FromPc);
        Assert.True(asked.Ok);
        Assert.Equal(RemoteSessionState.Pending, session.State);
        Assert.Equal(RemoteControl.AskPhone, asked.Message);
        Assert.False(session.AcceptInput(id));
        var allowed = session.Allow(id);
        Assert.True(allowed.Ok);
        Assert.Equal(RemoteSessionState.Active, session.State);
        Assert.True(session.AcceptInput(id, 1_000));
        Assert.False(session.AcceptInput(id, 1_010));
        Assert.True(session.AcceptInput(id, 1_025));
        Assert.False(session.AcceptInput(Guid.NewGuid().ToString("D"), 1_050));
        var stopped = session.Stop(id);
        Assert.True(stopped.Ok);
        Assert.Equal(RemoteSessionState.Idle, session.State);
        Assert.False(session.AcceptInput(id, 2_000));
    }

    [Fact]
    public void DenyAndBusySessionsDoNotOpenInput()
    {
        var session = new RemoteSession { Paired = true, Enabled = true };
        var id = Guid.NewGuid().ToString("D");
        session.Request(id, RemoteControl.FromPhone);
        var denied = session.Deny(id);
        Assert.False(denied.Ok);
        Assert.Equal(ErrorCodes.Declined, denied.Code);
        Assert.Equal(RemoteSessionState.Idle, session.State);
        session.Request(id, RemoteControl.FromPhone);
        session.Allow(id);
        Assert.True(session.BeginWatch(id));
        var busy = session.Request(Guid.NewGuid().ToString("D"), RemoteControl.FromPc);
        Assert.False(busy.Ok);
        Assert.Equal(ErrorCodes.Limit, busy.Code);
        session.EndWatch(id);
        Assert.Equal(RemoteSessionState.Idle, session.State);
        Assert.True(session.Request(Guid.NewGuid().ToString("D"), RemoteControl.FromPhone).Ok);
    }

    [Fact]
    public void RateLimitCapsABurstToThirtyPerSecond()
    {
        var session = new RemoteSession { Paired = true, Enabled = true };
        var id = Guid.NewGuid().ToString("D");
        session.Request(id, RemoteControl.FromPc);
        session.Allow(id);
        var accepted = 0;
        for (var i = 0; i < 40; i++)
        {
            if (session.AcceptInput(id, 10_000 + i * RemoteControl.MinInputGapMs))
            {
                accepted++;
            }
        }

        Assert.Equal(RemoteControl.MaxInputsPerSecond, accepted);
        Assert.True(session.AcceptInput(id, 11_000 + RemoteControl.MinInputGapMs));
    }

    [Fact]
    public void PhoneToPcNeedsNoToggleAndMapsInput()
    {
        Assert.False(RemoteGate.PhoneNeedsSpecialPermission());
        var ready = RemoteGate.PhoneToPc(true);
        Assert.True(ready.Ok);
        var blocked = RemoteGate.PcToPhone(true, false);
        Assert.False(blocked.Ok);
        Assert.Equal(RemoteControl.NeedToggle, blocked.Message);

        var session = new RemoteSession { Paired = true, Enabled = false };
        var id = Guid.NewGuid().ToString("D");
        var asked = session.Request(id, RemoteControl.FromPhone);
        Assert.True(asked.Ok);
        Assert.True(session.Allow(id).Ok);
        Assert.True(session.AcceptInput(id, 5_000));

        Assert.Equal("tap", RemotePointer.Classify(1, 4, 80));
        Assert.Equal("longpress", RemotePointer.Classify(1, 4, RemotePointer.LongPressMs));
        Assert.Equal("move", RemotePointer.Classify(1, RemotePointer.SlopPx + 1, 100));
        Assert.Equal("drag", RemotePointer.Classify(1, RemotePointer.SlopPx + 1, 100, selecting: true));
        Assert.Equal("scroll", RemotePointer.Classify(2, 40, 100));
        Assert.Equal("none", RemotePointer.Button("move"));
        Assert.False(RemotePointer.PressesButton("move"));
        Assert.True(RemotePointer.PressesButton("down"));
        Assert.Empty(MouseChord.Buttons("move"));
        Assert.DoesNotContain(MouseChord.LeftDown, MouseChord.Buttons("move"));
        Assert.Contains(MouseChord.LeftDown, MouseChord.Buttons("down"));
        Assert.False(MouseChord.PressesOnMove("move"));
        Assert.Equal(1, RemotePointer.ScrollNotches(-64), 3);
        Assert.False(PhoneControlChecklist.ShouldOpen(true, true));
        Assert.False(PhoneControlChecklist.ShouldOpen(false, false));
        Assert.True(PhoneControlChecklist.ShouldOpen(false, true));
        Assert.True(PhoneControlChecklist.IsWrongPage(PhoneControlChecklist.GenericPage));
        Assert.Equal(
            "org.stellartimelock.stellarsend/org.stellartimelock.send.RemoteControlService",
            PhoneControlChecklist.AccessibilityComponent("org.stellartimelock.stellarsend", "org.stellartimelock.send.RemoteControlService"));
        Assert.Equal("lan", RemoteDesktop.Route(true, true, true));
        Assert.Equal("direct", RemoteDesktop.Route(false, true, true));
        Assert.Equal("relay", RemoteDesktop.Route(false, false, true));
        var wifi = RemoteDesktop.Profile(false);
        var relay = RemoteDesktop.Profile(true);
        Assert.Equal(100, wifi.IntervalMs);
        Assert.Equal(1280, wifi.MaxWidth);
        Assert.Equal(60, wifi.JpegQuality);
        Assert.True(relay.IntervalMs > wifi.IntervalMs);
        var view = new RemoteViewport();
        var hit = view.Point(200, 50, 400, 200, 100, 200);
        Assert.Equal(0.5, hit!.Value.X, 3);
        view.Pinch(2, 200, 100, 400, 200, 100, 200);
        var held = view.Point(200, 100, 400, 200, 100, 200);
        Assert.Equal(0.5, held!.Value.X, 2);
        var saved = AlwaysAllow.Add([], id);
        Assert.True(AlwaysAllow.Contains(saved, id.ToUpperInvariant()));
        Assert.False(AlwaysAllow.Contains(AlwaysAllow.Remove(saved, id), id));
    }
}
