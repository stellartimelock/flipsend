using System.Net;
using System.Net.Sockets;

namespace StellarSend.Core.Discovery;

/// <summary>
/// Whether the paired phone is on this LAN. A TCP answer on the FlipSend port is enough.
/// A cached address from another network is not.
/// </summary>
public readonly record struct LanInterface(string Address, int PrefixLength, bool Prefer);

public static class LanPresence
{
    /// <summary>The phone announces about this often. One search has to cover it.</summary>
    public const int AnnounceIntervalMs = 3000;

    /// <summary>A connect that finishes just after the search clock still counts.</summary>
    public const int AnswerGraceMs = 400;

    public static bool OnWifi(bool portAnswered) => portAnswered;

    public static bool IsCurrent(string? host, IReadOnlyList<LanInterface> interfaces)
    {
        if (!IPAddress.TryParse(host ?? "", out var remote) || remote.AddressFamily != AddressFamily.InterNetwork)
        {
            return false;
        }

        foreach (var lan in interfaces)
        {
            if (Shares(lan, remote))
            {
                return true;
            }
        }

        return false;
    }

    public static string? BindAddress(string host, IReadOnlyList<LanInterface>? interfaces)
    {
        if (interfaces is null || interfaces.Count == 0 || !IPAddress.TryParse(host, out var remote))
        {
            return null;
        }

        string? fallback = null;
        foreach (var lan in interfaces)
        {
            if (!Shares(lan, remote))
            {
                continue;
            }

            if (lan.Prefer)
            {
                return lan.Address;
            }

            fallback ??= lan.Address;
        }

        return fallback;
    }

    private static bool Shares(LanInterface lan, IPAddress remote)
    {
        if (!IPAddress.TryParse(lan.Address, out var local) || local.AddressFamily != AddressFamily.InterNetwork)
        {
            return false;
        }

        var prefix = EffectivePrefix(local, lan.PrefixLength);
        var left = local.GetAddressBytes();
        var right = remote.GetAddressBytes();
        var bits = prefix;
        for (var i = 0; i < 4 && bits > 0; i++)
        {
            var take = Math.Min(8, bits);
            var mask = (byte)(0xFF << (8 - take));
            if ((left[i] & mask) != (right[i] & mask))
            {
                return false;
            }

            bits -= take;
        }

        return true;
    }

    /// <summary>
    /// Windows often reports a /32 on a normal Wi-Fi adapter. Private addresses then
    /// share a /24, which is the subnet the phone is actually on.
    /// </summary>
    private static int EffectivePrefix(IPAddress local, int prefix)
    {
        if (prefix is >= 8 and <= 30)
        {
            return prefix;
        }

        var bytes = local.GetAddressBytes();
        var privateLan = bytes[0] == 10
            || (bytes[0] == 192 && bytes[1] == 168)
            || (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31);
        if (privateLan)
        {
            return 24;
        }

        return prefix is >= 1 and <= 32 ? prefix : 24;
    }
}
