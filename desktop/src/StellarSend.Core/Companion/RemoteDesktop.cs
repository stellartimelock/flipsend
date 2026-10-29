using StellarSend.Core.Protocol;

namespace StellarSend.Core.Companion;

/// <summary>
/// Phone → PC remote desktop. The phone does not ask for an Android permission.
/// The PC shows one Allow. Frames stay on the paired link: same Wi-Fi, then the
/// punched direct link, then the relay.
/// </summary>
public static class RemoteDesktop
{
    public const string Label = RemoteControl.Label;
    public const string Line = "See your computer's screen and control it from this phone.";
    public const string PhoneControlKey = "phone_control";
    public const string PhoneControlLabel = "Control this phone";
    public const string PhoneControlLine = "Let the paired computer tap and type on this phone after you tap Allow.";
    public const string Ready = "Remote desktop is ready.";
    public const string Waiting = "Waiting for Allow on the computer.";
    public const string Showing = "Showing the paired computer.";
    public const string SessionCode = "remote-session";
    public const string TryWatch = "try_watch";
    public const int WifiIntervalMs = 100;
    public const int WifiMaxWidth = 1280;
    public const int WifiJpegQuality = 60;
    public const int RelayIntervalMs = 250;
    public const int RelayMaxWidth = 960;
    public const int RelayJpegQuality = 45;

    public static int ActiveIntervalMs { get; set; } = WifiIntervalMs;

    public static DesktopProfile Profile(bool relay) =>
        relay
            ? new DesktopProfile(RelayIntervalMs, RelayMaxWidth, RelayJpegQuality)
            : new DesktopProfile(WifiIntervalMs, WifiMaxWidth, WifiJpegQuality);

    /// <summary>Same Wi-Fi, then the punched direct link, then the relay.</summary>
    public static string Route(bool lan, bool punched, bool relay)
    {
        if (lan)
        {
            return "lan";
        }

        if (punched)
        {
            return "direct";
        }

        return relay ? "relay" : "offline";
    }
}

public readonly record struct DesktopProfile(int IntervalMs, int MaxWidth, int JpegQuality);

/// <summary>
/// An allowed phone request is the live session. It is not a one-shot transfer,
/// and it does not draw a file progress bar.
/// </summary>
public static class RemoteDesktopSession
{
    public static bool KeepsLink(string? op, string? from, bool allowed) =>
        allowed &&
        from == RemoteControl.FromPhone &&
        op is RemoteControl.OpRequest or RemoteControl.OpWatch;

    public static bool ShowsTransferBar(string? code) =>
        !string.Equals(code, RemoteDesktop.SessionCode, StringComparison.Ordinal);
}

public readonly record struct RemoteDesktopConsent(bool Allowed, bool Always);

/// <summary>
/// What each direction is allowed to ask for.
/// Phone → PC never needs an Android permission or the phone-control setting.
/// </summary>
public static class RemoteGate
{
    public static SessionDecision PhoneToPc(bool paired) =>
        paired
            ? SessionDecision.Done(RemoteControl.AskPc)
            : SessionDecision.Fail(ErrorCodes.UnknownPeer, RemoteControl.NeedPair);

    public static bool PhoneNeedsSpecialPermission() => false;

    public static SessionDecision PcToPhone(bool paired, bool phoneControlOn)
    {
        if (!paired)
        {
            return SessionDecision.Fail(ErrorCodes.UnknownPeer, RemoteControl.NeedPair);
        }

        if (!phoneControlOn)
        {
            return SessionDecision.Fail(ErrorCodes.Declined, RemoteControl.NeedToggle);
        }

        return SessionDecision.Done(RemoteControl.AskPhone);
    }
}

/// <summary>
/// The 1.5.18 button opened the system Accessibility list. That is the wrong
/// page for remote desktop, and it is the page that loops on OnePlus.
/// Phone control opens the service details page only after a tap, and only
/// while the service is still off.
/// </summary>
public static class PhoneControlChecklist
{
    public const string GenericPage = "android.settings.ACCESSIBILITY_SETTINGS";
    public const string DetailsPage = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";
    public const string ComponentExtra = "android.intent.extra.COMPONENT_NAME";
    public const string AppInfoPage = "android.settings.APPLICATION_DETAILS_SETTINGS";
    public const string Manual =
        "Phone control settings did not open. In Settings, open Accessibility, then Downloaded apps, then FlipSend.";
    public const string RestrictedDetail =
        "If that switch will not stay on, open App info and allow restricted settings. Then open phone control settings again.";

    public static bool IsWrongPage(string action) => action == GenericPage;

    public static bool ShouldOpen(bool serviceOn, bool userTapped) => userTapped && !serviceOn;

    public static string AccessibilityComponent(string packageName, string serviceClass) =>
        packageName + "/" + serviceClass;
}

public static class AlwaysAllow
{
    public static bool IsDeviceId(string? id) => Guid.TryParse(id, out _);

    /// <summary>A 64-character certificate pin is not a device id and must not skip Allow.</summary>
    public static bool Contains(IEnumerable<string> ids, string deviceId) =>
        IsDeviceId(deviceId) &&
        ids.Any(id => IsDeviceId(id) && string.Equals(id, deviceId, StringComparison.OrdinalIgnoreCase));

    public static List<string> Add(IEnumerable<string> ids, string deviceId)
    {
        var next = ids.Where(id => !string.Equals(id, deviceId, StringComparison.OrdinalIgnoreCase)).ToList();
        if (!string.IsNullOrWhiteSpace(deviceId))
        {
            next.Add(deviceId);
        }

        return next;
    }

    public static List<string> Remove(IEnumerable<string> ids, string deviceId) =>
        ids.Where(id => !string.Equals(id, deviceId, StringComparison.OrdinalIgnoreCase)).ToList();
}

/// <summary>Touch on the phone picture becomes a mouse or wheel event. Pinch stays on the phone.</summary>
public static class RemotePointer
{
    public const int LongPressMs = 450;
    public const double SlopPx = 28;
    private const double PixelsPerNotch = 64;

    /// <summary>
    /// One finger past the slop is a cursor move. It is a button drag only when
    /// <paramref name="selecting"/> (long-press or two-finger drag-select).
    /// Two fingers otherwise scroll.
    /// </summary>
    public static string Classify(int pointers, double movedPx, long heldMs, bool selecting = false)
    {
        if (pointers >= 2 && !selecting)
        {
            return "scroll";
        }

        if (selecting && movedPx > SlopPx)
        {
            return "drag";
        }

        if (heldMs >= LongPressMs && movedPx <= SlopPx)
        {
            return "longpress";
        }

        if (movedPx > SlopPx)
        {
            return "move";
        }

        return "tap";
    }

    public static double ScrollNotches(double dyPx)
    {
        if (double.IsNaN(dyPx) || double.IsInfinity(dyPx))
        {
            return 0;
        }

        return Math.Clamp(-dyPx / PixelsPerNotch, -RemoteControl.MaxScroll, RemoteControl.MaxScroll);
    }

    /// <summary>A move repositions the pointer. It does not press a button.</summary>
    public static bool PressesButton(string kind) =>
        kind is "down" or "tap" or "swipe" or "longpress";

    public static string Button(string kind) => kind switch
    {
        "longpress" => "right",
        "tap" or "down" or "up" or "swipe" => "left",
        "scroll" or "wheel" => "wheel",
        _ => "none"
    };
}

/// <summary>One touch sample. "move" never holds the mouse button. "pan" stays on the phone.</summary>
public readonly record struct TouchStep(string Kind, double X = 0, double Y = 0, double Dx = 0, double Dy = 0);

/// <summary>
/// Eases the cursor toward the finger. One sample per touch event, so a large
/// jump does not land in a single step.
/// </summary>
public sealed class CursorGlide
{
    public const double Alpha = 0.65;
    private readonly double _alpha;
    private bool _ready;
    private double _x;
    private double _y;

    public CursorGlide(double alpha = Alpha) => _alpha = alpha;

    public void Seed(double x, double y)
    {
        _ready = true;
        _x = x;
        _y = y;
    }

    public (double X, double Y) Follow(double targetX, double targetY)
    {
        if (!_ready)
        {
            Seed(targetX, targetY);
            return (_x, _y);
        }

        _x += (targetX - _x) * _alpha;
        _y += (targetY - _y) * _alpha;
        return (_x, _y);
    }

    public void Snap(double x, double y) => Seed(x, y);

    public void Reset() => _ready = false;
}

/// <summary>
/// Phone touch on the computer picture.
/// One finger past the slop moves the cursor, or pans when the picture is zoomed.
/// A short tap clicks. Pinch does not click.
/// The mouse button goes down only after a long-press, or a two-finger drag that
/// is not a vertical scroll.
/// </summary>
public sealed class RemoteTouch
{
    public string Phase { get; private set; } = "idle";
    private double _anchorX;
    private double _anchorY;
    private double _lastX;
    private double _lastY;
    private long _downAt;
    private bool _selectArmed;
    private bool _buttonDown;
    private string _multi = "";
    private double _scrollY;
    private double _sentX = double.NaN;
    private double _sentY = double.NaN;
    private readonly CursorGlide _glide = new();

    public List<TouchStep> Down(double x, double y, long timeMs)
    {
        Clear();
        _anchorX = x;
        _anchorY = y;
        _lastX = x;
        _lastY = y;
        _downAt = timeMs;
        Phase = "armed";
        return [];
    }

    public List<TouchStep> Hold(long timeMs)
    {
        if (Phase != "armed" || _selectArmed)
        {
            return [];
        }

        if (timeMs - _downAt < RemotePointer.LongPressMs)
        {
            return [];
        }

        if (Distance(_lastX - _anchorX, _lastY - _anchorY) > RemotePointer.SlopPx)
        {
            return [];
        }

        _selectArmed = true;
        return [];
    }

    public List<TouchStep> ExtraFinger(double x, double y)
    {
        if (Phase is "idle" or "pinch" or "select" || _buttonDown)
        {
            return [];
        }

        if (_multi is "scroll" or "select")
        {
            return [];
        }

        Phase = "multi";
        _multi = "";
        _anchorX = x;
        _anchorY = y;
        _lastX = x;
        _lastY = y;
        _scrollY = y;
        _selectArmed = false;
        return [];
    }

    public List<TouchStep> Pinch()
    {
        var steps = new List<TouchStep>();
        if (_buttonDown)
        {
            steps.Add(new TouchStep("up", _lastX, _lastY));
            _buttonDown = false;
        }

        Phase = "pinch";
        _multi = "pinch";
        _selectArmed = false;
        return steps;
    }

    public List<TouchStep> Move(double x, double y, int pointers, double zoom, long timeMs)
    {
        if (Phase is "idle" or "pinch")
        {
            _lastX = x;
            _lastY = y;
            return [];
        }

        var steps = new List<TouchStep>();
        if (pointers >= 2)
        {
            return MoveMulti(x, y, steps);
        }

        var dist = Distance(x - _anchorX, y - _anchorY);
        switch (Phase)
        {
            case "armed":
                if (dist <= RemotePointer.SlopPx)
                {
                    _lastX = x;
                    _lastY = y;
                    if (!_selectArmed && timeMs - _downAt >= RemotePointer.LongPressMs)
                    {
                        _selectArmed = true;
                    }

                    return steps;
                }

                if (_selectArmed || timeMs - _downAt >= RemotePointer.LongPressMs)
                {
                    BeginSelect(steps, _anchorX, _anchorY);
                    EmitCursor(steps, x, y);
                }
                else if (zoom > 1)
                {
                    Phase = "pan";
                    steps.Add(new TouchStep("pan", Dx: x - _lastX, Dy: y - _lastY));
                }
                else
                {
                    Phase = "hover";
                    _glide.Seed(_anchorX, _anchorY);
                    EmitCursor(steps, x, y);
                }

                break;
            case "hover":
                EmitCursor(steps, x, y);
                break;
            case "pan":
                steps.Add(new TouchStep("pan", Dx: x - _lastX, Dy: y - _lastY));
                break;
            case "select":
                EmitCursor(steps, x, y);
                break;
        }

        _lastX = x;
        _lastY = y;
        return steps;
    }

    public List<TouchStep> Up(double x, double y, long timeMs, double zoom = 1)
    {
        if (Phase == "idle")
        {
            return [];
        }

        var steps = new List<TouchStep>();
        var dist = Distance(x - _anchorX, y - _anchorY);
        var held = timeMs - _downAt;
        if (Phase == "pinch" || _multi == "pinch")
        {
            // Pinch stays on the phone.
        }
        else if (_buttonDown)
        {
            steps.Add(new TouchStep("up", x, y));
        }
        else if (_multi == "scroll" || Phase == "scroll" || (Phase == "multi" && _multi.Length == 0))
        {
            // A scroll or an unfinished second finger is not a click.
        }
        else if (Phase == "hover")
        {
            steps.Add(new TouchStep("move", x, y));
        }
        else if (Phase == "pan")
        {
            // The picture already moved.
        }
        else if (dist <= RemotePointer.SlopPx && (_selectArmed || held >= RemotePointer.LongPressMs))
        {
            steps.Add(new TouchStep("longpress", _anchorX, _anchorY));
        }
        else if (dist <= RemotePointer.SlopPx)
        {
            steps.Add(new TouchStep("tap", _anchorX, _anchorY));
        }
        else if (_selectArmed || held >= RemotePointer.LongPressMs)
        {
            steps.Add(new TouchStep("down", _anchorX, _anchorY));
            steps.Add(new TouchStep("up", x, y));
        }
        else if (zoom > 1)
        {
            // A zoomed flick with no samples does not click.
        }
        else
        {
            steps.Add(new TouchStep("move", x, y));
        }

        Clear();
        return steps;
    }

    public List<TouchStep> PointerUp(int pointersLeft, double x, double y)
    {
        var steps = new List<TouchStep>();
        if (pointersLeft > 1)
        {
            return steps;
        }

        if (_buttonDown)
        {
            steps.Add(new TouchStep("up", x, y));
        }

        Clear();
        return steps;
    }

    public List<TouchStep> Cancel()
    {
        var steps = new List<TouchStep>();
        if (_buttonDown)
        {
            steps.Add(new TouchStep("up", _lastX, _lastY));
        }

        Clear();
        return steps;
    }

    private List<TouchStep> MoveMulti(double x, double y, List<TouchStep> steps)
    {
        if (_multi.Length == 0)
        {
            if (Phase != "multi")
            {
                _anchorX = _lastX;
                _anchorY = _lastY;
                _scrollY = _lastY;
                Phase = "multi";
            }

            var dx = x - _anchorX;
            var dy = y - _anchorY;
            if (Distance(dx, dy) <= RemotePointer.SlopPx)
            {
                _lastX = x;
                _lastY = y;
                return steps;
            }

            _multi = Math.Abs(dy) > Math.Abs(dx) * 1.5 ? "scroll" : "select";
            Phase = _multi;
            if (_multi == "select")
            {
                BeginSelect(steps, _anchorX, _anchorY);
            }
        }

        if (_multi == "scroll")
        {
            var delta = y - _scrollY;
            _scrollY = y;
            if (Math.Abs(delta) >= 8)
            {
                var notches = RemotePointer.ScrollNotches(delta);
                if (notches != 0)
                {
                    steps.Add(new TouchStep("scroll", x, y, Dy: notches));
                }
            }
        }
        else if (_multi == "select")
        {
            EmitCursor(steps, x, y);
        }

        _lastX = x;
        _lastY = y;
        return steps;
    }

    private void BeginSelect(List<TouchStep> steps, double x, double y)
    {
        if (_buttonDown)
        {
            return;
        }

        _buttonDown = true;
        Phase = "select";
        _glide.Seed(x, y);
        steps.Add(new TouchStep("down", x, y));
    }

    private void EmitCursor(List<TouchStep> steps, double x, double y)
    {
        var (sx, sy) = _glide.Follow(x, y);
        if (!double.IsNaN(_sentX) && Distance(sx - _sentX, sy - _sentY) < 1)
        {
            return;
        }

        _sentX = sx;
        _sentY = sy;
        steps.Add(new TouchStep("move", sx, sy));
    }

    private static double Distance(double x, double y) => Math.Sqrt(x * x + y * y);

    private void Clear()
    {
        Phase = "idle";
        _selectArmed = false;
        _buttonDown = false;
        _multi = "";
        _sentX = double.NaN;
        _sentY = double.NaN;
        _glide.Reset();
    }
}

/// <summary>
/// Mouse buttons for a remote-input kind. A move never includes a button-down flag.
/// </summary>
public static class MouseChord
{
    public const uint Move = 0x0001;
    public const uint LeftDown = 0x0002;
    public const uint LeftUp = 0x0004;
    public const uint RightDown = 0x0008;
    public const uint RightUp = 0x0010;
    public const uint Wheel = 0x0800;
    public const uint Absolute = 0x8000;

    public static uint[] Buttons(string? kind) => kind switch
    {
        "down" => [LeftDown],
        "up" => [LeftUp],
        "tap" or "swipe" => [LeftDown, LeftUp],
        "longpress" => [RightDown, RightUp],
        _ => []
    };

    public static bool PressesOnMove(string? kind) =>
        string.Equals(kind, "move", StringComparison.Ordinal) &&
        Buttons(kind).Any(flag => (flag & (LeftDown | RightDown)) != 0);
}

public readonly record struct DesktopPlace(double Left, double Top, double DrawnW, double DrawnH);

/// <summary>Letterboxed picture with pinch zoom. Zoom 1 matches <see cref="RemoteCoords.FromView"/>.</summary>
public sealed class RemoteViewport
{
    public double Zoom { get; private set; } = 1;
    public double PanX { get; private set; }
    public double PanY { get; private set; }

    public void Reset()
    {
        Zoom = 1;
        PanX = 0;
        PanY = 0;
    }

    public void Pinch(double factor, double focusX, double focusY, double viewW, double viewH, double imageW, double imageH)
    {
        if (double.IsNaN(factor) || double.IsInfinity(factor) || factor <= 0)
        {
            return;
        }

        var before = Point(focusX, focusY, viewW, viewH, imageW, imageH);
        Zoom = Math.Clamp(Zoom * factor, 1, 4);
        if (Zoom <= 1)
        {
            Reset();
            return;
        }

        if (before is null || viewW <= 0 || imageW <= 0)
        {
            return;
        }

        var fit = Math.Min(viewW / imageW, viewH / imageH);
        var drawnW = imageW * fit * Zoom;
        var drawnH = imageH * fit * Zoom;
        PanX = focusX - before.Value.X * drawnW - (viewW - drawnW) / 2.0;
        PanY = focusY - before.Value.Y * drawnH - (viewH - drawnH) / 2.0;
        Clamp(viewW, viewH, imageW, imageH);
    }

    /// <summary>One-finger drag while zoomed. Zoom 1 has nowhere to pan.</summary>
    public void PanBy(double dx, double dy, double viewW, double viewH, double imageW, double imageH)
    {
        if (Zoom <= 1)
        {
            return;
        }

        if (viewW <= 0 || viewH <= 0 || imageW <= 0 || imageH <= 0)
        {
            return;
        }

        if (double.IsNaN(dx) || double.IsNaN(dy) || double.IsInfinity(dx) || double.IsInfinity(dy))
        {
            return;
        }

        PanX += dx;
        PanY += dy;
        Clamp(viewW, viewH, imageW, imageH);
    }

    public DesktopPlace? Placement(double viewW, double viewH, double imageW, double imageH)
    {
        if (viewW <= 0 || viewH <= 0 || imageW <= 0 || imageH <= 0)
        {
            return null;
        }

        var fit = Math.Min(viewW / imageW, viewH / imageH);
        var drawnW = imageW * fit * Zoom;
        var drawnH = imageH * fit * Zoom;
        var left = (viewW - drawnW) / 2.0 + PanX;
        var top = (viewH - drawnH) / 2.0 + PanY;
        return new DesktopPlace(left, top, drawnW, drawnH);
    }

    public (double X, double Y)? Point(double viewX, double viewY, double viewW, double viewH, double imageW, double imageH)
    {
        var place = Placement(viewW, viewH, imageW, imageH);
        if (place is null)
        {
            return null;
        }

        var ix = viewX - place.Value.Left;
        var iy = viewY - place.Value.Top;
        if (ix < 0 || iy < 0 || ix > place.Value.DrawnW || iy > place.Value.DrawnH)
        {
            return null;
        }

        return (Math.Clamp(ix / place.Value.DrawnW, 0, 1), Math.Clamp(iy / place.Value.DrawnH, 0, 1));
    }

    private void Clamp(double viewW, double viewH, double imageW, double imageH)
    {
        var fit = Math.Min(viewW / imageW, viewH / imageH);
        var drawnW = imageW * fit * Zoom;
        var drawnH = imageH * fit * Zoom;
        var maxX = Math.Abs(drawnW - viewW) / 2.0;
        var maxY = Math.Abs(drawnH - viewH) / 2.0;
        PanX = Math.Clamp(PanX, -maxX, maxX);
        PanY = Math.Clamp(PanY, -maxY, maxY);
    }
}

/// <summary>Splits a frame so one relay message stays under the 64 KB websocket cap.</summary>
public static class FrameSlices
{
    public const int MaxSlice = 32 * 1024;

    public static List<byte[]> Split(byte[] payload, int maxSlice = MaxSlice)
    {
        if (maxSlice is < 1 or > 65_535)
        {
            throw new ArgumentOutOfRangeException(nameof(maxSlice));
        }

        var parts = new List<byte[]>();
        if (payload.Length == 0)
        {
            return parts;
        }

        for (var index = 0; index < payload.Length; index += maxSlice)
        {
            var count = Math.Min(maxSlice, payload.Length - index);
            var slice = new byte[count];
            Buffer.BlockCopy(payload, index, slice, 0, count);
            parts.Add(slice);
        }

        return parts;
    }

    public static byte[] Join(IReadOnlyList<byte[]> parts)
    {
        var total = 0;
        foreach (var part in parts)
        {
            total += part.Length;
        }

        var joined = new byte[total];
        var offset = 0;
        foreach (var part in parts)
        {
            Buffer.BlockCopy(part, 0, joined, offset, part.Length);
            offset += part.Length;
        }

        return joined;
    }
}

/// <summary>Keeps only the newest frame. A slow link drops the stale ones.</summary>
public sealed class FramePump
{
    private readonly object _gate = new();
    private byte[]? _pending;

    public long Dropped { get; private set; }
    public long SentBytes { get; private set; }
    public int SentFrames { get; private set; }

    public bool Offer(byte[] frame)
    {
        lock (_gate)
        {
            var replaced = _pending is not null;
            if (replaced)
            {
                Dropped++;
            }

            _pending = frame;
            return !replaced;
        }
    }

    public byte[]? Take()
    {
        lock (_gate)
        {
            var frame = _pending;
            _pending = null;
            return frame;
        }
    }

    public void NoteSent(int bytes)
    {
        SentBytes += Math.Max(0, bytes);
        SentFrames++;
    }
}

/// <summary>While this device is dialing the relay it must not also be the listener in that room.</summary>
public sealed class RelayDialGate
{
    private int _dials;

    public void Begin() => Interlocked.Increment(ref _dials);

    public void End()
    {
        Interlocked.Decrement(ref _dials);
        if (Volatile.Read(ref _dials) < 0)
        {
            Interlocked.Exchange(ref _dials, 0);
        }
    }

    public bool ShouldListen() => Volatile.Read(ref _dials) == 0;
}

/// <summary>Enlarge shows a big code. It does not move the paired relay room.</summary>
public static class PairingEnlarge
{
    public static bool MovesRelay(bool paired) => !paired;
}

/// <summary>Saved startup stays on when the Run key is already set. A missing file starts on.</summary>
public static class StartupPreference
{
    public static bool Resolve(bool saved, bool registryOn) => registryOn || saved;
}

/// <summary>Only a 64-hex pin may be checked against a certificate. A device id must not.</summary>
public static class PeerPin
{
    public static string? OrNull(string? value) =>
        value is not null && StellarSend.Core.Protocol.PinFormat.IsWellFormed(value) ? value : null;
}

/// <summary>
/// Virtual-screen capture. A negative origin is a monitor to the left of the primary.
/// Huge virtual desktops fall back to the primary screen.
/// </summary>
public static class DesktopCapturePlan
{
    public const int PrimaryWidth = 0;
    public const int PrimaryHeight = 1;
    public const int VirtualX = 76;
    public const int VirtualY = 77;
    public const int VirtualWidth = 78;
    public const int VirtualHeight = 79;
    public const int MaxPixels = 8_000_000;
    public const uint VirtualDesk = 0x4000;

    public static CaptureRect Choose(int virtualX, int virtualY, int virtualWidth, int virtualHeight, int primaryWidth, int primaryHeight)
    {
        if (virtualWidth > 0 && virtualHeight > 0 && (long)virtualWidth * virtualHeight <= MaxPixels)
        {
            return new CaptureRect(virtualX, virtualY, virtualWidth, virtualHeight, true);
        }

        return new CaptureRect(0, 0, Math.Max(1, primaryWidth), Math.Max(1, primaryHeight), false);
    }
}

public readonly record struct CaptureRect(int X, int Y, int Width, int Height, bool Virtual);
