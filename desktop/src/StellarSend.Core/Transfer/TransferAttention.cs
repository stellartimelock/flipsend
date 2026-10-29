namespace StellarSend.Core.Transfer;

/// <summary>
/// A file send or receive stays in the background unless
/// "Bring window to front on transfer" is on. Remote desktop is not a transfer.
/// </summary>
public static class TransferAttention
{
    public const bool BringToFrontByDefault = false;

    public static bool BringsWindowForward(bool settingOn) => settingOn;

    public static bool AsksBeforeReceive(bool settingOn, string? name = null) =>
        settingOn && !Files.FileNames.IsFortressBotResponse(name);

    public static bool OpensReceivedText(bool settingOn) => settingOn;

    public static string Sending(string? name) => "Sending " + Label(name);

    public static string SendingCount(int count) =>
        count == 1 ? "Sending 1 file" : "Sending " + count.ToString() + " files";

    public static string Sent(string? name) => "Sent " + Label(name);

    public static string CouldNotSend(string? name) => "Could not send " + Label(name);

    public static string Received(string? name) => "Received " + Label(name);

    public static string Cancelled(string? name) => "Cancelled " + Label(name);

    public static string Finished(string? name, string? detail)
    {
        if (string.Equals(detail, "Sent", StringComparison.Ordinal))
        {
            return Sent(name);
        }

        if (string.Equals(detail, "Cancelled", StringComparison.Ordinal))
        {
            return Cancelled(name);
        }

        return CouldNotSend(name);
    }

    public static string Label(string? name)
    {
        var text = name?.Trim() ?? "";
        if (text.Length == 0)
        {
            return "a file";
        }

        if (text.Length > 80)
        {
            return text[..77] + "...";
        }

        return text;
    }
}
