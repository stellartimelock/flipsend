using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using StellarSend.Core.Companion;

namespace StellarSend.App;

/// <summary>
/// GDI screen capture and SendInput. Same JPEG size cap as the phone-to-PC share.
/// </summary>
public static class WindowsDesktop
{
    public static Action<string>? CaptureFault { get; set; }
    private static bool _virtualDesk;

    public static byte[]? CaptureJpeg() => CaptureJpeg(RemoteDesktop.WifiMaxWidth, RemoteDesktop.WifiJpegQuality);

    public static byte[]? CaptureJpeg(int maxWidth, int quality)
    {
        try
        {
            var plan = DesktopCapturePlan.Choose(
                GetSystemMetrics(DesktopCapturePlan.VirtualX),
                GetSystemMetrics(DesktopCapturePlan.VirtualY),
                GetSystemMetrics(DesktopCapturePlan.VirtualWidth),
                GetSystemMetrics(DesktopCapturePlan.VirtualHeight),
                GetSystemMetrics(DesktopCapturePlan.PrimaryWidth),
                GetSystemMetrics(DesktopCapturePlan.PrimaryHeight));
            _virtualDesk = plan.Virtual;
            var width = Math.Max(1, plan.Width);
            var height = Math.Max(1, plan.Height);
            var (jpegW, jpegH) = RemoteCoords.JpegSize(width, height, Math.Max(1, maxWidth));
            using var source = new Bitmap(width, height);
            using (var graphics = Graphics.FromImage(source))
            {
                graphics.CopyFromScreen(plan.X, plan.Y, 0, 0, source.Size);
            }

            Bitmap? copy = null;
            try
            {
                var frame = source;
                if (jpegW != width || jpegH != height)
                {
                    copy = new Bitmap(jpegW, jpegH);
                    using var draw = Graphics.FromImage(copy);
                    draw.InterpolationMode = InterpolationMode.HighQualityBilinear;
                    draw.DrawImage(source, 0, 0, jpegW, jpegH);
                    frame = copy;
                }

            var q = Math.Clamp(quality, 25, 90);
            byte[] bytes;
            do
            {
                using var stream = new MemoryStream();
                using var encoderParams = new EncoderParameters(1);
                encoderParams.Param[0] = new EncoderParameter(Encoder.Quality, (long)q);
                var codec = ImageCodecInfo.GetImageEncoders().First(item => item.FormatID == ImageFormat.Jpeg.Guid);
                frame.Save(stream, codec, encoderParams);
                bytes = stream.ToArray();
                q -= 15;
            }
            while (bytes.Length > RemoteControl.MaxJpegBytes && q >= 25);

            return ScreenShare.TryJpeg(bytes, out var jpeg) ? jpeg : null;
            }
            finally
            {
                copy?.Dispose();
            }
        }
        catch (Exception ex)
        {
            CaptureFault?.Invoke($"Remote desktop capture failed. {ex.GetType().Name}: {ex.Message}\n{ex}");
            return null;
        }
    }

    public static void Apply(RemoteInput input)
    {
        var width = Math.Max(1, GetSystemMetrics(0));
        var height = Math.Max(1, GetSystemMetrics(1));
        var (x, y) = RemoteCoords.ToDevice(input.X, input.Y, width, height);
        switch (input.Kind)
        {
            case "move":
            case "down":
            case "up":
            case "tap":
            case "longpress":
                Move(x, y, width, height);
                // Buttons("move") is empty, so a move never presses the mouse.
                foreach (var flag in MouseChord.Buttons(input.Kind))
                {
                    Mouse(flag);
                }

                break;
            case "swipe":
                Move(x, y, width, height);
                Mouse(MouseChord.LeftDown);
                var (x2, y2) = RemoteCoords.ToDevice(input.X2, input.Y2, width, height);
                Move(x2, y2, width, height);
                Mouse(MouseChord.LeftUp);
                break;
            case "scroll":
            case "wheel":
                Move(x, y, width, height);
                Wheel((int)(input.Dy * 120));
                break;
            case "text":
                if (!string.IsNullOrEmpty(input.Text)) TypeText(input.Text);
                break;
            case "key":
                PressKey(input.Text);
                break;
        }
    }

    private static void Move(int x, int y, int width, int height)
    {
        var absX = (int)Math.Round(x * 65535.0 / Math.Max(1, width - 1));
        var absY = (int)Math.Round(y * 65535.0 / Math.Max(1, height - 1));
        var input = new INPUT
        {
            Type = INPUT_MOUSE,
            U = new InputUnion
            {
                mi = new MOUSEINPUT
                {
                    dx = absX,
                    dy = absY,
                    dwFlags = MouseChord.Move | MouseChord.Absolute | (_virtualDesk ? DesktopCapturePlan.VirtualDesk : 0)
                }
            }
        };
        SendInput(1, [input], Marshal.SizeOf<INPUT>());
    }

    private static void Mouse(uint flags)
    {
        var input = new INPUT
        {
            Type = INPUT_MOUSE,
            U = new InputUnion { mi = new MOUSEINPUT { dwFlags = flags } }
        };
        SendInput(1, [input], Marshal.SizeOf<INPUT>());
    }

    private static void Wheel(int delta)
    {
        var input = new INPUT
        {
            Type = INPUT_MOUSE,
            U = new InputUnion { mi = new MOUSEINPUT { mouseData = delta, dwFlags = MouseChord.Wheel } }
        };
        SendInput(1, [input], Marshal.SizeOf<INPUT>());
    }

    private static void TypeText(string text)
    {
        foreach (var ch in text.Take(RemoteControl.MaxTextChars))
        {
            Unicode(ch, false);
            Unicode(ch, true);
        }
    }

    private static void PressKey(string? name)
    {
        var vk = name?.ToLowerInvariant() switch
        {
            "enter" => (ushort)0x0D,
            "escape" or "esc" => (ushort)0x1B,
            "tab" => (ushort)0x09,
            "backspace" => (ushort)0x08,
            _ => (ushort)0
        };
        if (vk == 0)
        {
            return;
        }

        VirtualKey(vk, false);
        VirtualKey(vk, true);
    }

    private static void Unicode(char ch, bool up)
    {
        var input = new INPUT
        {
            Type = INPUT_KEYBOARD,
            U = new InputUnion
            {
                ki = new KEYBDINPUT
                {
                    wScan = ch,
                    dwFlags = KEYEVENTF_UNICODE | (up ? KEYEVENTF_KEYUP : 0)
                }
            }
        };
        SendInput(1, [input], Marshal.SizeOf<INPUT>());
    }

    private static void VirtualKey(ushort vk, bool up)
    {
        var input = new INPUT
        {
            Type = INPUT_KEYBOARD,
            U = new InputUnion
            {
                ki = new KEYBDINPUT
                {
                    wVk = vk,
                    dwFlags = up ? KEYEVENTF_KEYUP : 0
                }
            }
        };
        SendInput(1, [input], Marshal.SizeOf<INPUT>());
    }

    private const uint INPUT_MOUSE = 0;
    private const uint INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_UNICODE = 0x0004;

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int index);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint Type;
        public InputUnion U;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public int mouseData;
        public uint dwFlags;
        public uint time;
        public nint dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public nint dwExtraInfo;
    }
}
