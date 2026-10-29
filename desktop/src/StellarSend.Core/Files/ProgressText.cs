using StellarSend.Core.Transfer;

namespace StellarSend.Core.Files;

public static class ProgressText
{
    public static string FormatBytes(long bytes)
    {
        if (bytes < 0)
        {
            bytes = 0;
        }

        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double value = bytes;
        var unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }

        return unit == 0 ? $"{bytes} B" : $"{value:0.#} {units[unit]}";
    }

    public static string Format(long done, long total, double? bytesPerSecond, double? secondsRemaining, TransferState state, string? detail)
    {
        return state switch
        {
            TransferState.Succeeded => "Sent",
            TransferState.Cancelled => "Cancelled",
            TransferState.Failed => string.IsNullOrWhiteSpace(detail) ? "Failed" : detail!,
            TransferState.AwaitingConsent => "Waiting for approval",
            _ => BuildActive(done, total, bytesPerSecond, secondsRemaining)
        };
    }

    private static string BuildActive(long done, long total, double? bytesPerSecond, double? secondsRemaining)
    {
        var text = $"{FormatBytes(done)} / {FormatBytes(total)}";
        if (bytesPerSecond is > 1)
        {
            text += $" · {FormatBytes((long)bytesPerSecond.Value)}/s";
        }

        if (secondsRemaining is >= 0 and < 86400)
        {
            var seconds = (int)Math.Round(secondsRemaining.Value);
            text += seconds < 60 ? $" · {seconds} s left" : $" · {seconds / 60} min left";
        }

        return text;
    }
}
