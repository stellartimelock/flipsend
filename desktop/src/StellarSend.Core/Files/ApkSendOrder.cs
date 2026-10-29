namespace StellarSend.Core.Files;

/// <summary>
/// FlipSend's own APK is sent last so the phone can finish the rest of the
/// batch before that install restarts the app.
/// </summary>
public static class ApkSendOrder
{
    public static bool IsFlipSendApk(string? path)
    {
        var name = Path.GetFileName(path ?? "");
        if (string.IsNullOrWhiteSpace(name) || !name.EndsWith(".apk", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        return name.Contains("FlipSend", StringComparison.OrdinalIgnoreCase)
            || name.Contains("STELLAR_SEND", StringComparison.OrdinalIgnoreCase)
            || name.Contains("stellarsend", StringComparison.OrdinalIgnoreCase);
    }

    public static IReadOnlyList<string> SelfLast(IEnumerable<string> paths)
    {
        var list = paths.Where(path => !string.IsNullOrWhiteSpace(path)).ToList();
        return list.Where(path => !IsFlipSendApk(path)).Concat(list.Where(IsFlipSendApk)).ToList();
    }
}
