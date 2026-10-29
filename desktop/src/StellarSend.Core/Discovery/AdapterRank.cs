using System.Net;
using System.Net.NetworkInformation;

namespace StellarSend.Core.Discovery;

public enum AdapterMedium
{
    Wifi,
    Ethernet,
    Vpn,
    Other
}

public readonly record struct AdapterSnapshot(
    string Name,
    string Address,
    bool IsUp,
    bool HasGateway,
    AdapterMedium Medium,
    bool IsVirtual);

public static class AdapterRank
{
    public const int MaxCandidates = 8;

    private static readonly string[] VpnNeedles =
    [
        "surfshark", "openvpn", "wireguard", "wintun", "nordlynx", "proton", "mullvad", "tailscale", "vpn"
    ];

    public static bool IsVirtual(string name)
    {
        var text = name.ToLowerInvariant();
        return text.Contains("vethernet", StringComparison.Ordinal)
            || text.Contains("hyper-v", StringComparison.Ordinal)
            || text.Contains("hyperv", StringComparison.Ordinal)
            || text.Contains("wsl", StringComparison.Ordinal);
    }

    public static bool IsVpnName(string name)
    {
        var text = name.ToLowerInvariant();
        foreach (var needle in VpnNeedles)
        {
            if (text.Contains(needle, StringComparison.Ordinal))
            {
                return true;
            }
        }

        var tokens = text.Split([' ', '-', '_', '(', ')', '.', '/', '\\'], StringSplitOptions.RemoveEmptyEntries);
        foreach (var token in tokens)
        {
            if (token == "tap" || IsTunToken(token))
            {
                return true;
            }
        }

        return false;
    }

    public static bool IsTunName(string name)
    {
        var text = name.ToLowerInvariant();
        if (IsTunToken(text))
        {
            return true;
        }

        var tokens = text.Split([' ', '-', '_', '(', ')', '.', '/', '\\'], StringSplitOptions.RemoveEmptyEntries);
        return tokens.Any(IsTunToken);
    }

    private static bool IsTunToken(string token)
    {
        if (token == "tun")
        {
            return true;
        }

        return token.StartsWith("tun", StringComparison.Ordinal)
            && token.Length <= 6
            && token.Skip(3).All(char.IsDigit);
    }

    public static AdapterMedium Classify(string name, NetworkInterfaceType type)
    {
        var tunnel = type is NetworkInterfaceType.Tunnel or NetworkInterfaceType.Ppp;
        if (IsVpnName(name) || tunnel)
        {
            return AdapterMedium.Vpn;
        }

        if (type == NetworkInterfaceType.Wireless80211 || Contains(name, "wi-fi") || Contains(name, "wifi") || Contains(name, "wlan"))
        {
            return AdapterMedium.Wifi;
        }

        if (type is NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet || Contains(name, "ethernet"))
        {
            return AdapterMedium.Ethernet;
        }

        return AdapterMedium.Other;
    }

    public static bool IsCandidateAddress(string address)
    {
        if (!IPAddress.TryParse(address, out var ip))
        {
            return false;
        }

        if (!LocalAddressPolicy.IsLocalUnicast(ip, allowLoopback: false))
        {
            return false;
        }

        return !ip.ToString().StartsWith("169.254.", StringComparison.Ordinal);
    }

    /// <summary>
    /// CGNAT and vendor VPN pools. A real Wi-Fi adapter on 10.x is still allowed
    /// when <see cref="Rank"/> sees Wi-Fi or Ethernet.
    /// </summary>
    public static bool IsVpnRange(string address)
    {
        if (!IPAddress.TryParse(address, out var ip) || ip.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork)
        {
            return false;
        }

        var bytes = ip.GetAddressBytes();
        if (bytes[0] == 10 && bytes[1] == 14)
        {
            return true;
        }

        if (bytes[0] == 10 && bytes[1] == 8)
        {
            return true;
        }

        if (bytes[0] == 10 && bytes[1] == 7)
        {
            return true;
        }

        if (bytes[0] == 100 && bytes[1] is >= 64 and <= 127)
        {
            return true;
        }

        return false;
    }

    public static bool IsLanCandidate(string address) =>
        IsCandidateAddress(address) && !IsVpnRange(address);

    public static bool IsLanAdapter(AdapterSnapshot adapter)
    {
        if (!adapter.IsUp || adapter.IsVirtual)
        {
            return false;
        }

        if (adapter.Medium == AdapterMedium.Vpn)
        {
            return false;
        }

        if (IsVpnName(adapter.Name) || IsTunName(adapter.Name))
        {
            return false;
        }

        if (!IsCandidateAddress(adapter.Address))
        {
            return false;
        }

        if (adapter.Medium is AdapterMedium.Wifi or AdapterMedium.Ethernet)
        {
            return true;
        }

        return !IsVpnRange(adapter.Address);
    }

    public static IReadOnlyList<string> Rank(IEnumerable<AdapterSnapshot> adapters)
    {
        return adapters
            .Where(IsLanAdapter)
            .OrderBy(Tier)
            .ThenBy(adapter => adapter.Address, StringComparer.Ordinal)
            .Select(adapter => adapter.Address)
            .Distinct(StringComparer.Ordinal)
            .Take(MaxCandidates)
            .ToArray();
    }

    private static int Tier(AdapterSnapshot adapter)
    {
        var medium = adapter.Medium switch
        {
            AdapterMedium.Wifi or AdapterMedium.Ethernet => 0,
            AdapterMedium.Vpn => 2,
            _ => 1
        };
        return (medium * 2) + (adapter.HasGateway ? 0 : 1);
    }

    private static bool Contains(string name, string needle) =>
        name.Contains(needle, StringComparison.OrdinalIgnoreCase);
}

public static class VpnStatus
{
    public const string Line = "A VPN is on. Using the relay.";
    public const string BypassTip = "For faster Wi-Fi transfers, add FlipSend to your VPN's bypass list.";

    public static string? Describe(bool vpnOn, bool bypassable, bool usingRelay)
    {
        if (!vpnOn)
        {
            return null;
        }

        if (!usingRelay && bypassable)
        {
            return null;
        }

        return bypassable ? Line : Line + " " + BypassTip;
    }
}

/// <summary>Room membership is a device id, never an IP.</summary>
public static class RelaySelf
{
    public static bool SameDevice(string? listenDevice, string? dialDevice) =>
        !string.IsNullOrWhiteSpace(listenDevice)
        && !string.IsNullOrWhiteSpace(dialDevice)
        && string.Equals(listenDevice, dialDevice, StringComparison.OrdinalIgnoreCase);
}
