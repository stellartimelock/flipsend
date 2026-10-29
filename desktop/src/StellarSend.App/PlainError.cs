using System.Net.Sockets;
using StellarSend.Core.Protocol;
using StellarSend.Core.Transfer;

namespace StellarSend.App;

/// <summary>
/// Turns raw exceptions into one short banner: what failed and what to do.
/// A busy port after a restart is a retry, not a stuck error.
/// Off-Wi-Fi and relay drops are status, not a broken pairing.
/// </summary>
public static class PlainError
{
    public const string PortBusyTitle = "That network port is already in use";
    public const string PortBusyDetail = "FlipSend will try again. You can keep sending. If this stays up, quit FlipSend and open it once.";
    public const string GenericTitle = "Could not finish that step";
    public const string OffWifi = "Phone isn't on this Wi-Fi. Sending through the relay.";
    public const string RelayDropped = "Relay connection dropped. Retrying.";
    public const string Retrying = "Retrying.";
    /// <summary>Transfer row text when the relay is up but the phone is not. Not a pairing problem.</summary>
    public const string PhoneOfflineRow = "Phone is offline. Will send when it's back.";

    public static bool IsPortBusy(Exception ex) => IsPortBusy(ex.Message) || ex is SocketException socket && socket.SocketErrorCode == SocketError.AddressAlreadyInUse;

    public static bool IsPortBusy(string? message)
    {
        var text = message?.Trim() ?? "";
        if (text.Length == 0)
        {
            return false;
        }

        return text.Contains("Only one usage of each socket address", StringComparison.OrdinalIgnoreCase)
            || text.Contains("Address already in use", StringComparison.OrdinalIgnoreCase)
            || text.Contains("address already in use", StringComparison.OrdinalIgnoreCase)
            || text.Contains("(10048)", StringComparison.Ordinal)
            || text.Contains("WSAEADDRINUSE", StringComparison.OrdinalIgnoreCase);
    }

    public static bool IsOffWifi(string? message)
    {
        var text = message ?? "";
        if (text.Length == 0 || IsKeyChanged(text))
        {
            return false;
        }

        return text.Contains("Could not find the paired device", StringComparison.OrdinalIgnoreCase)
            || text.Contains("mDNS", StringComparison.Ordinal)
            || text.Contains("Couldn't reach your phone", StringComparison.OrdinalIgnoreCase)
            || text.Contains("Waking relay", StringComparison.OrdinalIgnoreCase)
            || text.Contains("Phone not on this Wi-Fi", StringComparison.OrdinalIgnoreCase);
    }

    public static bool IsRelayDrop(string? message)
    {
        var text = message ?? "";
        if (text.Length == 0 || IsKeyChanged(text) || IsOffWifi(text))
        {
            return false;
        }

        return text.Contains("The relay closed", StringComparison.OrdinalIgnoreCase)
            || text.Contains("relay connection dropped", StringComparison.OrdinalIgnoreCase)
            || text.Contains("Relay listen paused", StringComparison.OrdinalIgnoreCase)
            || (text.Contains("relay", StringComparison.OrdinalIgnoreCase)
                && (text.Contains("WebSocket", StringComparison.OrdinalIgnoreCase)
                    || text.Contains("IOException", StringComparison.OrdinalIgnoreCase)));
    }

    public static bool IsKeyChanged(string? message)
    {
        var text = message ?? "";
        return text.Contains("key changed", StringComparison.OrdinalIgnoreCase)
            || text.Contains("Unpair both sides", StringComparison.OrdinalIgnoreCase)
            || text.Contains(ErrorCodes.KeyChanged, StringComparison.OrdinalIgnoreCase);
    }

    public static bool IsRetrying(string? message)
    {
        var text = message?.Trim() ?? "";
        return text.Equals(Retrying, StringComparison.Ordinal)
            || text.Equals(KeyChangeRetry.Retrying, StringComparison.Ordinal);
    }

    public static bool ShouldBanner(string? message)
    {
        if (string.IsNullOrWhiteSpace(message))
        {
            return false;
        }

        if (IsPortBusy(message) || IsKeyChanged(message))
        {
            return true;
        }

        if (IsOffWifi(message) || IsRelayDrop(message) || IsRetrying(message) || RelayWake.IsPhoneOffline(message))
        {
            return false;
        }

        return true;
    }

    public static string ShortTransfer(string? message)
    {
        if (RelayWake.IsPhoneOffline(message))
        {
            return PhoneOfflineRow;
        }

        var shown = Describe(message);
        if (IsKeyChanged(message) && !IsRetrying(message))
        {
            return shown.Title;
        }

        if (IsOffWifi(message) || IsRelayDrop(message) || IsRetrying(message) || IsPortBusy(message))
        {
            return shown.Title;
        }

        if (LooksLikeRawDump(message ?? "") || (message ?? "").Contains("attempt ", StringComparison.OrdinalIgnoreCase))
        {
            return GenericTitle;
        }

        return string.IsNullOrWhiteSpace(shown.Title) ? GenericTitle : shown.Title;
    }

    public static (string Title, string Detail) Describe(string? message)
    {
        var text = message?.Trim() ?? "";
        if (IsPortBusy(text))
        {
            return (PortBusyTitle, PortBusyDetail);
        }

        if (IsRetrying(text))
        {
            return (Retrying, "");
        }

        if (IsRelayDrop(text))
        {
            return (RelayDropped, "");
        }

        if (IsOffWifi(text))
        {
            return (OffWifi, "");
        }

        if (text.Length == 0)
        {
            return (GenericTitle, "Try again. If it still fails, quit FlipSend and open it once.");
        }

        if (LooksLikeRawDump(text))
        {
            return (GenericTitle, "FlipSend hit a network error. Try again in a moment. If it still fails, quit FlipSend and open it once.");
        }

        return (text, "Try again. If it still fails, quit FlipSend and open it once.");
    }

    private static bool LooksLikeRawDump(string text) =>
        text.Contains("Exception", StringComparison.Ordinal)
        || text.Contains(" at ", StringComparison.Ordinal)
        || text.Contains("HRESULT", StringComparison.OrdinalIgnoreCase);
}
