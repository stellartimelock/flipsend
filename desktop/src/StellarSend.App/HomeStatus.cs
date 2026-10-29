using StellarSend.Core.Transfer;

namespace StellarSend.App;

/// <summary>
/// One short line for the PC window. Technical text stays in Details or the log.
/// </summary>
public static class HomeStatus
{
    public const string Connected = "Connected";
    public const string PhoneAway = "Phone away, using relay";
    public const string CantReach = "Can't reach phone";
    public const string NotConnected = "Not connected";
    public const string WaitingToPair = "Waiting to pair";
    public const string VpnOn = "A VPN is on. FlipSend may not find your phone over Wi-Fi.";

    public static string Line(bool paired, bool linked, bool pairingWindow, string? pathPill, string? lastError, bool vpnUp = false)
    {
        var error = lastError?.Trim() ?? "";
        if (PlainError.IsRetrying(error))
        {
            return KeyChangeRetry.Retrying;
        }

        if (PlainError.IsRelayDrop(error))
        {
            return PlainError.RelayDropped;
        }

        if (paired && RelayWake.IsPhoneOffline(error))
        {
            return PhoneOffline;
        }

        var onLan = linked && !LooksLikeRelay(pathPill);
        if (vpnUp && paired && !onLan)
        {
            return VpnOn;
        }

        if (linked)
        {
            return LooksLikeRelay(pathPill) ? PhoneAway : Connected;
        }

        if (!paired)
        {
            return pairingWindow ? WaitingToPair : NotConnected;
        }

        if (PlainError.IsOffWifi(error))
        {
            return PlainError.OffWifi;
        }

        return CantReach;
    }

    public const string ConnectedWifi = "Connected on Wi-Fi";
    public const string ConnectedRelay = "Connected through relay";
    public const string ConnectedBluetooth = "Connected over Bluetooth";
    public const string ConnectedUsb = "Connected over USB";
    public const string NotPaired = "Not paired. Scan the code below.";
    public const string RepairWaiting = "Re-pair: scan the code below.";
    /// <summary>Only after the phone really rejected this PC's key or pin.</summary>
    public const string RepairNeeded = "Re-pair needed";
    /// <summary>Relay up, phone not in the room. Pairing is fine; sends wait for the phone.</summary>
    public const string PhoneOffline = "Phone is offline";

    /// <summary>The status line near the top of the window.</summary>
    public static string Connection(bool paired, bool listening, RouteKind active, bool pairingOpen, bool repairNeeded = false, bool phoneOffline = false)
    {
        if (!paired)
        {
            return NotPaired;
        }

        if (pairingOpen)
        {
            return RepairWaiting;
        }

        if (repairNeeded)
        {
            return RepairNeeded;
        }

        if (phoneOffline)
        {
            return PhoneOffline;
        }

        if (!listening)
        {
            return NotConnected;
        }

        return active switch
        {
            RouteKind.Lan or RouteKind.Direct => ConnectedWifi,
            RouteKind.Relay => ConnectedRelay,
            RouteKind.Bluetooth => ConnectedBluetooth,
            RouteKind.Usb => ConnectedUsb,
            _ => NotConnected
        };
    }

    public static string Meta(bool paired, bool sending, string? sendingLine)
    {
        if (sending && !string.IsNullOrWhiteSpace(sendingLine))
        {
            return sendingLine!;
        }

        return paired ? "Nothing sending" : "Scan a code to pair";
    }

    private static bool LooksLikeRelay(string? path) =>
        !string.IsNullOrWhiteSpace(path)
        && path.Contains("relay", StringComparison.OrdinalIgnoreCase);
}
