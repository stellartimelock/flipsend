using System.Text;
using System.Text.Json;
using StellarSend.Core.Companion;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;

namespace StellarSend.Core.Tests;

public sealed class CompanionTests
{
    [Fact]
    public void ServiceFramesUseTypesTenAndEleven()
    {
        var service = FrameCodec.Encode(MessageType.Service, Encoding.UTF8.GetBytes("{}"));
        var result = FrameCodec.Encode(MessageType.ServiceResult, Encoding.UTF8.GetBytes("{}"));
        Assert.Equal(10, service[5]);
        Assert.Equal(11, result[5]);
    }

    [Fact]
    public void PasswordIsRejectedWhenEmptyOrTooLongAndNeverReturned()
    {
        var clip = new MemoryClipboard();
        var handoff = new ClipboardHandoff(clip, new MemoryNotice());
        var empty = handoff.Put(Guid.NewGuid().ToString("D"), "", DateTimeOffset.UnixEpoch);
        Assert.False(empty.Ok);
        Assert.Equal(ErrorCodes.BadRequest, empty.Code);
        Assert.Null(clip.Text);

        var secret = new string('p', CompanionServices.MaxPasswordChars + 1);
        var longOne = handoff.Put(Guid.NewGuid().ToString("D"), secret, DateTimeOffset.UnixEpoch);
        Assert.False(longOne.Ok);
        Assert.DoesNotContain(secret, longOne.Message);
        Assert.Null(clip.Text);
    }

    [Fact]
    public void ClipboardClearsAfterFortyFiveSecondsOnlyWhenUnchanged()
    {
        var clip = new MemoryClipboard();
        var notices = new MemoryNotice();
        var handoff = new ClipboardHandoff(clip, notices);
        var start = DateTimeOffset.UnixEpoch;
        var secret = "vault-secret";
        var placed = handoff.Put(Guid.NewGuid().ToString("D"), secret, start);
        Assert.Equal("Copied to the PC clipboard. It will clear in 45 seconds.", placed.Message);
        Assert.DoesNotContain(secret, placed.Message);
        Assert.Equal(secret, clip.Text);
        Assert.Equal("Copied to the PC clipboard. It will clear in 45 seconds.", notices.Last);

        Assert.False(handoff.Tick(start.AddSeconds(44)));
        Assert.Equal(secret, clip.Text);

        Assert.True(handoff.Tick(start.AddSeconds(45)));
        Assert.Equal("", clip.Text);
    }

    [Fact]
    public void ClipboardHonorsAThirtySecondClearFromFortress()
    {
        var clip = new MemoryClipboard();
        var handoff = new ClipboardHandoff(clip, new MemoryNotice());
        var start = DateTimeOffset.UnixEpoch;
        var secret = "alex\nhunter2";
        var placed = handoff.Put(Guid.NewGuid().ToString("D"), secret, start, 30);
        Assert.Equal("Copied to the PC clipboard. It will clear in 30 seconds.", placed.Message);
        Assert.DoesNotContain("hunter2", placed.Message);
        Assert.False(handoff.Tick(start.AddSeconds(29)));
        Assert.Equal(secret, clip.Text);
        Assert.True(handoff.Tick(start.AddSeconds(30)));
        Assert.Equal("", clip.Text);
    }

    [Fact]
    public void AReplacedClipboardIsLeftAlone()
    {
        var clip = new MemoryClipboard();
        var handoff = new ClipboardHandoff(clip, new MemoryNotice());
        var start = DateTimeOffset.UnixEpoch;
        handoff.Put(Guid.NewGuid().ToString("D"), "vault-secret", start);
        clip.Text = "user typed this";
        Assert.False(handoff.Tick(start.AddSeconds(45)));
        Assert.Equal("user typed this", clip.Text);
        Assert.False(handoff.Tick(start.AddSeconds(90)));
        Assert.Equal("user typed this", clip.Text);
    }

    [Fact]
    public void ASecondCopyResetsTheDeadline()
    {
        var clip = new MemoryClipboard();
        var handoff = new ClipboardHandoff(clip, new MemoryNotice());
        var start = DateTimeOffset.UnixEpoch;
        handoff.Put(Guid.NewGuid().ToString("D"), "one", start);
        handoff.Put(Guid.NewGuid().ToString("D"), "two", start.AddSeconds(20));
        Assert.False(handoff.Tick(start.AddSeconds(60)));
        Assert.Equal("two", clip.Text);
        Assert.True(handoff.Tick(start.AddSeconds(65)));
        Assert.Equal("", clip.Text);
    }

    [Fact]
    public void OnlyHttpLinksOpenAndTheResultDoesNotEchoTheUrl()
    {
        var surface = new FakeBrowser();
        var plugin = new RemoteBrowserPlugin(surface);
        var url = "https://example.com/private-path";
        var opened = plugin.Handle(Request("open", new { url }));
        Assert.True(opened.Ok);
        Assert.Equal("Opened on this PC.", opened.Message);
        Assert.DoesNotContain("private-path", opened.Message);
        Assert.Equal("https://example.com/private-path", surface.Opened);

        Assert.False(plugin.Handle(Request("open", new { url = "file:///etc/passwd" })).Ok);
        Assert.False(plugin.Handle(Request("open", new { url = "javascript:alert(1)" })).Ok);
        Assert.False(plugin.Handle(Request("open", new { url = "https://user:pass@example.com/a" })).Ok);
        Assert.False(plugin.Handle(Request("open", new { url = "https://example.com/\u0001" })).Ok);
        Assert.Equal("https://example.com/private-path", surface.Opened);
    }

    [Fact]
    public void UrlOnlySurfaceDoesNotCaptureOrAcceptInput()
    {
        var surface = new FakeBrowser { CanView = false, CanInput = false };
        var plugin = new RemoteBrowserPlugin(surface);
        var frame = plugin.Handle(Request("frame"));
        var input = plugin.Handle(Request("input", new { kind = "tap", x = 0.2, y = 0.4 }));
        Assert.Equal(RemoteBrowserPlugin.ViewUnavailable, frame.Code);
        Assert.Equal(RemoteBrowserPlugin.InputUnavailable, input.Code);
        Assert.False(surface.Captured);
        Assert.Null(surface.LastInput);
        Assert.Contains("desktop", frame.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void AnAttachedSurfaceReceivesTapAndText()
    {
        var surface = new FakeBrowser { CanView = true, CanInput = true, Frame = [1, 2, 3] };
        var plugin = new RemoteBrowserPlugin(surface);
        var tap = plugin.Handle(Request("input", new { kind = "tap", x = 0.25, y = 0.5 }));
        Assert.True(tap.Ok);
        Assert.Equal("tap", surface.LastInput);
        Assert.Equal(0.25, surface.X);
        Assert.Equal(0.5, surface.Y);
        var text = plugin.Handle(Request("input", new { kind = "text", text = "hi", x = 2.0, y = -1 }));
        Assert.True(text.Ok);
        Assert.Equal("text", surface.LastInput);
        Assert.Equal("hi", surface.Text);
        Assert.Equal(0, surface.X);
        Assert.Equal(0, surface.Y);
        Assert.Equal("Browser view updated.", plugin.Handle(Request("frame")).Message);
    }

    [Fact]
    public void AnOversizedViewIsRefusedWithoutEchoingBytes()
    {
        var marker = new string('A', 32);
        var surface = new FakeBrowser { CanView = true, Frame = Encoding.ASCII.GetBytes(new string('A', CompanionServices.MaxFrameBytes + 1)) };
        var result = new RemoteBrowserPlugin(surface).Handle(Request("frame"));
        Assert.Equal(ErrorCodes.Limit, result.Code);
        Assert.DoesNotContain(marker, result.Message);
    }

    [Fact]
    public void UnknownCompanionCallsAreNotImplemented()
    {
        var host = new CompanionHost(new MemoryClipboard(), new MemoryNotice(), new UrlOnlyBrowserSurface(), () => DateTimeOffset.UnixEpoch);
        var result = host.Handle(Call("desktop.control", "move"));
        Assert.Equal(ErrorCodes.NotImplemented, result.Code);
        var status = host.Handle(Call(CompanionServices.RemoteBrowser, "status"));
        Assert.Contains("Live view: off", status.Message);
        Assert.Contains("Typing: off", status.Message);
    }

    [Fact]
    public void LogsRedactPasswordFieldsAndKeepThePublicNotice()
    {
        Assert.Equal("[redacted]", RedactingFileLog.Redact("{\"password\":\"secret\"}"));
        Assert.Equal("Copied to the PC clipboard. It will clear in 45 seconds.", RedactingFileLog.Redact("Copied to the PC clipboard. It will clear in 45 seconds."));
    }

    private static ServiceRequest Request(string op, object? body = null) =>
        Call(CompanionServices.RemoteBrowser, op, body);

    private static ServiceRequest Call(string service, string op, object? body = null)
    {
        using var document = JsonDocument.Parse(body is null ? "{}" : JsonSerializer.Serialize(body));
        return new ServiceRequest(Guid.NewGuid().ToString("D"), service, op, document.RootElement.Clone());
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
        public bool CanOpen { get; set; } = true;
        public bool CanView { get; set; }
        public bool CanInput { get; set; }
        public byte[]? Frame { get; set; }
        public string? Opened { get; private set; }
        public bool Captured { get; private set; }
        public string? LastInput { get; private set; }
        public double X { get; private set; }
        public double Y { get; private set; }
        public string? Text { get; private set; }

        public void Open(string url) => Opened = url;

        public byte[]? Capture()
        {
            Captured = true;
            if (!CanView)
            {
                throw new InvalidOperationException("capture");
            }

            return Frame;
        }

        public void Input(string kind, double x, double y, string? text)
        {
            if (!CanInput)
            {
                throw new InvalidOperationException("input");
            }

            LastInput = kind;
            X = x;
            Y = y;
            Text = text;
        }
    }
}
