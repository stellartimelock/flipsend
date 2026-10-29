using System.Text.Json;
using System.Text.Json.Serialization;
using StellarSend.Core.Protocol;

namespace StellarSend.Core.Companion;

/// <summary>
/// Phone to PC remote desktop, and the separate phone-control direction,
/// on the paired TLS link. No new ports.
/// </summary>
public static class RemoteControl
{
    public const string Service = CompanionServices.RemoteControl;
    public const string PrefKey = "remote_control";
    public const int MaxWidth = 720;
    public const int MaxJpegBytes = CompanionServices.MaxFrameBytes;
    public const int IntervalMs = 200;
    public const int MaxTextChars = 256;
    public const int MaxInputsPerSecond = 30;
    public const int MinInputGapMs = 20;
    public const double MaxScroll = 8;
    public const string Label = "Remote desktop";
    public const string NeedPair = "Pair a computer before remote desktop.";
    public const string NeedToggle = "Turn on Control this phone in Settings first.";
    public const string NeedAllow = "The other device has to tap Allow first.";
    public const string Off = "Control this phone is off.";
    public const string AskPhone = "Let the paired computer control this phone?";
    public const string AskPc = "Let the paired phone control this computer?";
    public const string Allowed = "Remote desktop is on. Tap Disconnect to end it.";
    public const string Denied = "Remote desktop was not allowed.";
    public const string Stopped = "Remote desktop stopped.";
    public const string Banner = "Being controlled — Stop";
    public const string BannerDetail = "Your paired device can tap and type here until you tap Stop.";
    public const string ActiveLine = "Remote desktop active - Disconnect";
    public const string UpdatePhone = "Update the FlipSend app on this phone, then tap Remote desktop again.";
    public const string FromPc = "pc";
    public const string FromPhone = "phone";
    public const string OpStatus = "status";
    public const string OpRequest = "request";
    public const string OpDrive = "drive";
    public const string OpWatch = "watch";
    public const string OpStop = "stop";
    public const string IosLimit = "Remote desktop is not available on iPhone. You can still send files and watch a shared screen.";

    public static readonly HashSet<string> Kinds = new(StringComparer.Ordinal)
    {
        "tap", "swipe", "scroll", "move", "down", "up", "text", "key",
        "back", "home", "recents", "wheel", "longpress"
    };
}

public enum RemoteSessionState
{
    Idle,
    Pending,
    Active
}

public sealed record RemoteInput(
    [property: JsonPropertyName("kind")] string Kind,
    [property: JsonPropertyName("session")] string Session,
    [property: JsonPropertyName("x")] double X = 0,
    [property: JsonPropertyName("y")] double Y = 0,
    [property: JsonPropertyName("x2")] double X2 = 0,
    [property: JsonPropertyName("y2")] double Y2 = 0,
    [property: JsonPropertyName("dx")] double Dx = 0,
    [property: JsonPropertyName("dy")] double Dy = 0,
    [property: JsonPropertyName("text")] string? Text = null);

public static class RemoteInputCodec
{
    public static RemoteInput? Parse(byte[] payload)
    {
        try
        {
            return Parse(JsonDocument.Parse(payload).RootElement);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public static RemoteInput? Parse(JsonElement json)
    {
        var kind = ServiceCodec.ReadString(json, "kind") ?? "";
        var session = ServiceCodec.ReadString(json, "session") ?? "";
        if (!RemoteControl.Kinds.Contains(kind) || !Guid.TryParse(session, out _))
        {
            return null;
        }

        var text = ServiceCodec.ReadString(json, "text");
        if (kind == "text" && (string.IsNullOrEmpty(text) || text.Length > RemoteControl.MaxTextChars))
        {
            return null;
        }

        if (kind == "key" && (string.IsNullOrEmpty(text) || text.Length > 32))
        {
            return null;
        }

        return new RemoteInput(
            kind,
            session,
            Clamp01(Number(json, "x")),
            Clamp01(Number(json, "y")),
            Clamp01(Number(json, "x2")),
            Clamp01(Number(json, "y2")),
            ClampScroll(Number(json, "dx")),
            ClampScroll(Number(json, "dy")),
            text);
    }

    public static byte[] Encode(RemoteInput input) => ProtocolJson.ToUtf8(input);

    private static double Number(JsonElement body, string name)
    {
        if (!body.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.Number)
        {
            return 0;
        }

        return value.GetDouble();
    }

    private static double Clamp01(double value)
    {
        if (double.IsNaN(value) || double.IsInfinity(value))
        {
            return 0;
        }

        return Math.Clamp(value, 0, 1);
    }

    private static double ClampScroll(double value)
    {
        if (double.IsNaN(value) || double.IsInfinity(value))
        {
            return 0;
        }

        return Math.Clamp(value, -RemoteControl.MaxScroll, RemoteControl.MaxScroll);
    }
}

public static class RemoteCoords
{
    public static (int Width, int Height) JpegSize(int deviceWidth, int deviceHeight, int maxWidth = RemoteControl.MaxWidth)
    {
        var width = Math.Max(1, deviceWidth);
        var height = Math.Max(1, deviceHeight);
        if (width <= maxWidth)
        {
            return (width, height);
        }

        var scale = maxWidth / (double)width;
        return (maxWidth, Math.Max(1, (int)(height * scale)));
    }

    /// <summary>
    /// Map a click on a uniformly scaled view (letterboxed) onto 0..1 of the JPEG.
    /// Frames are capped at <see cref="RemoteControl.MaxWidth"/>, then shown with Uniform stretch.
    /// </summary>
    public static (double X, double Y)? FromView(
        double viewX,
        double viewY,
        double viewWidth,
        double viewHeight,
        double imageWidth,
        double imageHeight)
    {
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0)
        {
            return null;
        }

        var scale = Math.Min(viewWidth / imageWidth, viewHeight / imageHeight);
        var drawnW = imageWidth * scale;
        var drawnH = imageHeight * scale;
        var left = (viewWidth - drawnW) / 2.0;
        var top = (viewHeight - drawnH) / 2.0;
        var ix = viewX - left;
        var iy = viewY - top;
        if (ix < 0 || iy < 0 || ix > drawnW || iy > drawnH)
        {
            return null;
        }

        return (Math.Clamp(ix / drawnW, 0, 1), Math.Clamp(iy / drawnH, 0, 1));
    }

    /// <summary>0..1 on the JPEG maps back to full device pixels (scale cancels).</summary>
    public static (int X, int Y) ToDevice(double normX, double normY, int deviceWidth, int deviceHeight)
    {
        var width = Math.Max(1, deviceWidth);
        var height = Math.Max(1, deviceHeight);
        var x = (int)(Math.Clamp(normX, 0, 1) * width);
        var y = (int)(Math.Clamp(normY, 0, 1) * height);
        return (Math.Clamp(x, 0, width - 1), Math.Clamp(y, 0, height - 1));
    }

    public static (int X, int Y) JpegToDevice(int jpegX, int jpegY, int jpegWidth, int jpegHeight, int deviceWidth, int deviceHeight)
    {
        var jw = Math.Max(1, jpegWidth);
        var jh = Math.Max(1, jpegHeight);
        return ToDevice(jpegX / (double)jw, jpegY / (double)jh, deviceWidth, deviceHeight);
    }
}

public sealed record SessionDecision(bool Ok, string Code, string Message)
{
    public static SessionDecision Done(string message) => new(true, "ok", message);

    public static SessionDecision Fail(string code, string message) => new(false, code, message);
}

/// <summary>
/// Allow / Stop state for one remote-control session. Pairing is checked by
/// the caller. The Settings toggle must be on before Allow.
/// </summary>
public sealed class RemoteSession
{
    private readonly Func<long> _clock;
    private readonly object _gate = new();
    private long _lastInputMs;
    private long _windowStartMs;
    private int _windowCount;

    public RemoteSession(Func<long>? clock = null)
    {
        _clock = clock ?? (() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    public bool Enabled { get; set; }
    public bool Paired { get; set; }
    public RemoteSessionState State { get; private set; } = RemoteSessionState.Idle;
    public string? SessionId { get; private set; }
    public string? From { get; private set; }
    public bool Watching { get; private set; }

    public SessionDecision Request(string session, string from)
    {
        lock (_gate)
        {
            if (!Paired)
            {
                return SessionDecision.Fail(ErrorCodes.UnknownPeer, RemoteControl.NeedPair);
            }

            if (!Guid.TryParse(session, out _))
            {
                return SessionDecision.Fail(ErrorCodes.BadRequest, "That session is not valid.");
            }

            if (from is not (RemoteControl.FromPc or RemoteControl.FromPhone))
            {
                return SessionDecision.Fail(ErrorCodes.BadRequest, "Who is controlling is missing.");
            }

            // Phone → PC is remote desktop. The Allow tap is the gate.
            // PC → phone still needs the Control this phone setting.
            if (from != RemoteControl.FromPhone && !Enabled)
            {
                return SessionDecision.Fail(ErrorCodes.Declined, RemoteControl.NeedToggle);
            }

            if (State != RemoteSessionState.Idle)
            {
                if (!Watching)
                {
                    Clear();
                }
                else if (State == RemoteSessionState.Active && SessionId == session)
                {
                    return SessionDecision.Done(RemoteControl.Allowed);
                }
                else
                {
                    return SessionDecision.Fail(ErrorCodes.Limit, "A remote control session is already open.");
                }
            }

            SessionId = session;
            From = from;
            State = RemoteSessionState.Pending;
            ResetLimiter();
            return SessionDecision.Done(from == RemoteControl.FromPc ? RemoteControl.AskPhone : RemoteControl.AskPc);
        }
    }

    public SessionDecision Allow(string session)
    {
        lock (_gate)
        {
            if (!Paired)
            {
                return SessionDecision.Fail(ErrorCodes.UnknownPeer, RemoteControl.NeedPair);
            }

            if (State != RemoteSessionState.Pending || SessionId != session)
            {
                return SessionDecision.Fail(ErrorCodes.Declined, RemoteControl.NeedAllow);
            }

            if (From != RemoteControl.FromPhone && !Enabled)
            {
                return SessionDecision.Fail(ErrorCodes.Declined, RemoteControl.NeedToggle);
            }

            State = RemoteSessionState.Active;
            ResetLimiter();
            return SessionDecision.Done(RemoteControl.Allowed);
        }
    }

    public SessionDecision Deny(string session)
    {
        lock (_gate)
        {
            if (State != RemoteSessionState.Pending || SessionId != session)
            {
                return SessionDecision.Fail(ErrorCodes.Declined, RemoteControl.Denied);
            }

            Clear();
            return SessionDecision.Fail(ErrorCodes.Declined, RemoteControl.Denied);
        }
    }

    public bool BeginWatch(string session)
    {
        lock (_gate)
        {
            if (State != RemoteSessionState.Active || SessionId != session)
            {
                return false;
            }

            Watching = true;
            return true;
        }
    }

    /// <summary>
    /// The request socket closed before any frame. A 1.5.34 phone then opens
    /// a separate watch connection, so the Allow stays in place.
    /// </summary>
    public void ParkWatch(string? session = null)
    {
        lock (_gate)
        {
            if (session is not null && SessionId is not null && session != SessionId)
            {
                return;
            }

            Watching = false;
        }
    }

    /// <summary>The watch socket closed. The next request can start clean.</summary>
    public SessionDecision EndWatch(string? session = null)
    {
        lock (_gate)
        {
            if (session is not null && SessionId is not null && session != SessionId)
            {
                return SessionDecision.Fail(ErrorCodes.BadRequest, "That remote control session is not open.");
            }

            Watching = false;
            Clear();
            return SessionDecision.Done(RemoteControl.Stopped);
        }
    }

    public SessionDecision Stop(string? session = null)
    {
        lock (_gate)
        {
            if (session is not null && SessionId is not null && session != SessionId)
            {
                return SessionDecision.Fail(ErrorCodes.BadRequest, "That remote control session is not open.");
            }

            Clear();
            return SessionDecision.Done(RemoteControl.Stopped);
        }
    }

    /// <summary>Controller-side bookkeeping after the other device already allowed.</summary>
    public SessionDecision OpenLocal(string session, string from)
    {
        lock (_gate)
        {
            if (!Paired)
            {
                return SessionDecision.Fail(ErrorCodes.UnknownPeer, RemoteControl.NeedPair);
            }

            if (!Guid.TryParse(session, out _))
            {
                return SessionDecision.Fail(ErrorCodes.BadRequest, "That session is not valid.");
            }

            if (from is not (RemoteControl.FromPc or RemoteControl.FromPhone))
            {
                return SessionDecision.Fail(ErrorCodes.BadRequest, "Who is controlling is missing.");
            }

            SessionId = session;
            From = from;
            State = RemoteSessionState.Active;
            ResetLimiter();
            return SessionDecision.Done(RemoteControl.Allowed);
        }
    }

    public bool AcceptInput(string session, long? nowMs = null)
    {
        lock (_gate)
        {
            if (!Paired)
            {
                return false;
            }

            if (State != RemoteSessionState.Active || SessionId != session)
            {
                return false;
            }

            if (From != RemoteControl.FromPhone && !Enabled)
            {
                return false;
            }

            var now = nowMs ?? _clock();
            if (now < _lastInputMs)
            {
                _lastInputMs = now;
                _windowStartMs = now;
                _windowCount = 0;
            }

            if (now - _lastInputMs < RemoteControl.MinInputGapMs)
            {
                return false;
            }

            if (now - _windowStartMs >= 1000)
            {
                _windowStartMs = now;
                _windowCount = 0;
            }

            if (_windowCount >= RemoteControl.MaxInputsPerSecond)
            {
                return false;
            }

            _lastInputMs = now;
            _windowCount++;
            return true;
        }
    }

    public bool IsActive(string? session = null)
    {
        lock (_gate)
        {
            return State == RemoteSessionState.Active && (session is null || session == SessionId);
        }
    }

    public void Reset()
    {
        lock (_gate)
        {
            Clear();
        }
    }

    private void Clear()
    {
        State = RemoteSessionState.Idle;
        SessionId = null;
        From = null;
        Watching = false;
        ResetLimiter();
    }

    private void ResetLimiter()
    {
        _lastInputMs = 0;
        _windowStartMs = 0;
        _windowCount = 0;
    }
}
