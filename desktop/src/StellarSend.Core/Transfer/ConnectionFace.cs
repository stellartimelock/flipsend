using StellarSend.Core.Pairing;

namespace StellarSend.Core.Transfer;

public enum RouteKind
{
    Lan,
    Direct,
    Relay,
    Usb,
    Bluetooth,
    Offline
}

public sealed record ConnectionInput(
    bool Paired,
    bool Listening,
    bool PairingWindowOpen,
    bool OnLan,
    bool LanUp,
    bool DirectUp,
    bool DirectAvailable,
    bool RelayUp,
    bool RelayAvailable,
    bool UsbUp,
    bool UsbAvailable,
    string? LastError = null,
    bool DirectExpected = false,
    bool BluetoothUp = false,
    bool BluetoothAvailable = false,
    bool BluetoothNeedsSetup = false);

public sealed record RouteChoice(RouteKind Kind, string Label, bool Active, bool Available, bool Best);

public sealed record ConnectionFace(
    RouteKind Active,
    string Headline,
    string PathPill,
    string RoutesLine,
    string Hint,
    RouteKind? SwitchTo,
    IReadOnlyList<RouteChoice> Routes)
{
    public bool Connected => Active != RouteKind.Offline;

    public IReadOnlyList<string> Indicators()
    {
        var list = new List<string>();
        if (!string.IsNullOrWhiteSpace(Headline)) list.Add(Headline);
        if (!string.IsNullOrWhiteSpace(PathPill)) list.Add(PathPill);
        if (!string.IsNullOrWhiteSpace(RoutesLine)) list.Add(RoutesLine);
        if (!string.IsNullOrWhiteSpace(Hint)) list.Add(Hint);
        return list;
    }

    public bool Agrees()
    {
        if (Routes.Count(route => route.Best) > 1) return false;
        if (Routes.Count(route => route.Active) > 1) return false;
        if (Hint.Contains('\n') || RoutesLine.Contains('\n')) return false;
        var denies = Indicators().Any(text =>
            text.Contains("Not connected", StringComparison.OrdinalIgnoreCase)
            || text.Contains("Disconnected", StringComparison.OrdinalIgnoreCase));
        if (Headline == "Connected" && denies) return false;
        if (Active == RouteKind.Offline)
        {
            if (Headline == "Connected" || PathPill.Length > 0) return false;
            if (RoutesLine.Contains(" now", StringComparison.Ordinal)) return false;
        }
        else
        {
            if (Headline != "Connected") return false;
            if (!Routes.Any(route => route.Active && route.Kind == Active)) return false;
            if (PathPill.Length == 0) return false;
        }

        if (SwitchTo is { } target && (target == Active || !Routes.Any(route => route.Kind == target && route.Best)))
        {
            return false;
        }

        return true;
    }
}

public static class ConnectionState
{
    public const string Lan = "LAN";
    public const string Direct = "Direct";
    public const string Relay = "Relay";
    public const string Usb = "USB";
    public const string Bluetooth = "Bluetooth";
    public const string Switching = "Switching networks";

    private static readonly RouteKind[] Rank = [RouteKind.Lan, RouteKind.Bluetooth, RouteKind.Direct, RouteKind.Usb, RouteKind.Relay];

    public static ConnectionFace Face(ConnectionInput input)
    {
        var active = input.LanUp ? RouteKind.Lan
            : input.BluetoothUp ? RouteKind.Bluetooth
            : input.DirectUp ? RouteKind.Direct
            : input.UsbUp ? RouteKind.Usb
            : input.RelayUp ? RouteKind.Relay
            : RouteKind.Offline;
        var available = new Dictionary<RouteKind, bool>
        {
            [RouteKind.Lan] = input.OnLan,
            [RouteKind.Bluetooth] = input.BluetoothAvailable,
            [RouteKind.Direct] = input.DirectAvailable,
            [RouteKind.Usb] = input.UsbAvailable,
            [RouteKind.Relay] = input.RelayAvailable
        };
        RouteKind? bestKind = null;
        foreach (var kind in Rank)
        {
            if (available[kind])
            {
                bestKind = kind;
                break;
            }
        }

        var routes = new List<RouteChoice>();
        foreach (var kind in new[] { RouteKind.Lan, RouteKind.Bluetooth, RouteKind.Direct, RouteKind.Relay, RouteKind.Usb })
        {
            var up = kind == active;
            var ready = up || available[kind];
            if (!ready) continue;
            routes.Add(new RouteChoice(kind, Label(kind), up, true, kind == bestKind));
        }

        var switchTo = routes.FirstOrDefault(route => route.Best && !route.Active)?.Kind;
        var headline = active != RouteKind.Offline
            ? "Connected"
            : input.PairingWindowOpen && !input.Paired
                ? "Waiting to pair"
                : "Not connected";
        var switching = string.Equals(input.LastError?.Trim(), Switching, StringComparison.Ordinal);
        var blocked = TrustNewKey.Applies(input.Paired, null, input.LastError);
        var shown = blocked ? RouteKind.Offline : active;
        var routesLine = switching ? Switching : Line(routes);
        var hint = "";
        if (blocked)
        {
            hint = string.IsNullOrWhiteSpace(input.LastError)
                ? TrustNewKey.Message(null)
                : input.LastError.Trim();
            routesLine = "";
            headline = "Keys don't match";
        }
        else if (!switching && input.OnLan && !input.LanUp && (active == RouteKind.Relay || active == RouteKind.Bluetooth))
        {
            hint = "Same Wi-Fi did not answer.";
        }
        else if (!switching && input.DirectExpected && !input.DirectAvailable && !input.DirectUp && active == RouteKind.Relay)
        {
            hint = "Direct link did not open.";
        }

        return new ConnectionFace(
            shown,
            headline,
            shown == RouteKind.Offline ? "" : Label(shown),
            routesLine,
            hint,
            blocked ? null : switchTo,
            blocked ? Array.Empty<RouteChoice>() : routes);
    }

    public static string Label(RouteKind kind) => kind switch
    {
        RouteKind.Lan => Lan,
        RouteKind.Direct => Direct,
        RouteKind.Relay => Relay,
        RouteKind.Usb => Usb,
        RouteKind.Bluetooth => Bluetooth,
        _ => ""
    };

    private static string Line(IReadOnlyList<RouteChoice> routes)
    {
        if (routes.Count == 0) return "";
        var parts = new List<string>();
        var current = routes.FirstOrDefault(route => route.Active);
        if (current is not null) parts.Add(current.Label + " now");
        foreach (var route in routes)
        {
            if (!route.Available || route.Active) continue;
            parts.Add(route.Best ? route.Label + " available (best)" : route.Label + " available");
        }

        if (routes.Any(route => route.Active) && routes.Any(route => route.Best && !route.Active)) parts.Add("tap to switch");
        return string.Join(", ", parts);
    }
}
