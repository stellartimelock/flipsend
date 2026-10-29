namespace StellarSend.Core.Companion;

public static class ScreenShare
{
    public const int MaxJpegBytes = CompanionServices.MaxFrameBytes;

    public static bool TryJpeg(byte[]? payload, out byte[] jpeg)
    {
        jpeg = [];
        if (payload is null || payload.Length < 2 || payload.Length > MaxJpegBytes)
        {
            return false;
        }

        if (payload[0] != 0xFF || payload[1] != 0xD8)
        {
            return false;
        }

        jpeg = payload;
        return true;
    }
}

/// <summary>
/// PC side of Cast to TV: Windows Project / Miracast sheet.
/// </summary>
public static class TvCast
{
    public const string Button = "Cast to TV";
    public const string ProjectUri = "ms-settings:project";
    public const string Started =
        "Pick a TV in the Windows Project sheet. If it drops, Windows will say the wireless display disconnected.";

    public static string OpenFailed(string reason) => "Could not open Cast to TV. " + reason;
}
