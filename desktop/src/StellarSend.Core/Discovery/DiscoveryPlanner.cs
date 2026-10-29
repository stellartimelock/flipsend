namespace StellarSend.Core.Discovery;

public enum ProbeReason
{
    Manual,
    LastKnown,
    HotspotGateway,
    HotspotRange
}

public sealed record ProbeTarget(string Ip, int Port, ProbeReason Reason, int TimeoutMs, string? LocalAddress = null);

public sealed record DiscoveryRequest(
    string? LastKnownIp,
    string? ManualIp,
    string? GatewayIp,
    string? OwnIp,
    int Port,
    bool AllowLoopback = false,
    IReadOnlyList<string>? ExtraIps = null,
    IReadOnlyList<LanInterface>? LocalInterfaces = null);

public sealed record DiscoveryPlan(
    bool UseMdns,
    bool UseBroadcast,
    int MdnsTimeoutMs,
    int BroadcastTimeoutMs,
    IReadOnlyList<ProbeTarget> Probes,
    int TotalBudgetMs);

public sealed record DiscoveryHit(string Ip, int Port, string Source);

public static class Hotspot
{
    public static readonly string[] Gateways =
    [
        "192.168.43.1",
        "192.168.49.1",
        "192.168.137.1",
        "172.20.10.1",
        "192.168.225.1"
    ];

    public static bool IsSignature(string? ip) =>
        ip is not null && Gateways.Contains(ip, StringComparer.Ordinal);
}

public static class DiscoveryPlanner
{
    public const int MaxRangeProbes = 16;
    public const int TotalBudgetMs = 4000;

    public static DiscoveryPlan Plan(DiscoveryRequest request)
    {
        var probes = new List<ProbeTarget>();
        var seen = new HashSet<string>(StringComparer.Ordinal);

        void Add(string? ip, ProbeReason reason, int timeout, bool cached)
        {
            if (!TryNormalize(ip, request.AllowLoopback, out var normalized))
            {
                return;
            }

            if (request.OwnIp is not null && string.Equals(normalized, request.OwnIp, StringComparison.Ordinal))
            {
                return;
            }

            if (request.LocalInterfaces is { Count: > 0 } interfaces)
            {
                if (interfaces.Any(lan => string.Equals(lan.Address, normalized, StringComparison.Ordinal)))
                {
                    return;
                }

                // A cached address from the previous network is not evidence the phone left Wi-Fi.
                if (cached && !LanPresence.IsCurrent(normalized, interfaces))
                {
                    return;
                }
            }

            if (!seen.Add(normalized))
            {
                return;
            }

            probes.Add(new ProbeTarget(
                normalized,
                request.Port,
                reason,
                timeout,
                LanPresence.BindAddress(normalized, request.LocalInterfaces)));
        }

        Add(request.ManualIp, ProbeReason.Manual, 800, cached: false);
        Add(request.LastKnownIp, ProbeReason.LastKnown, 800, cached: true);
        if (request.ExtraIps is not null)
        {
            foreach (var extra in request.ExtraIps)
            {
                Add(extra, ProbeReason.LastKnown, 800, cached: true);
            }
        }

        if (Hotspot.IsSignature(request.GatewayIp) && TryNormalize(request.GatewayIp, false, out var gateway))
        {
            Add(gateway, ProbeReason.HotspotGateway, 500, cached: false);
            var dot = gateway.LastIndexOf('.');
            var prefix = gateway[..dot];
            var rangeAdded = 0;

            void AddRange(string candidate)
            {
                if (rangeAdded >= MaxRangeProbes)
                {
                    return;
                }

                var before = probes.Count;
                Add(candidate, ProbeReason.HotspotRange, 200, cached: false);
                if (probes.Count > before)
                {
                    rangeAdded++;
                }
            }

            if (TryNormalize(request.LastKnownIp, false, out var last))
            {
                var octetText = last[(last.LastIndexOf('.') + 1)..];
                if (int.TryParse(octetText, out var octet) && octet is >= 2 and <= 254)
                {
                    AddRange($"{prefix}.{octet}");
                }
            }

            for (var host = 2; host <= 254 && rangeAdded < MaxRangeProbes; host++)
            {
                AddRange($"{prefix}.{host}");
            }
        }

        // The phone beacons about every 3s. A 1.5s listen misses that beacon and
        // the PC then says the phone is not on Wi-Fi even though the port is open.
        var listenMs = Math.Max(TotalBudgetMs, LanPresence.AnnounceIntervalMs);
        return new DiscoveryPlan(true, true, listenMs, listenMs, probes, TotalBudgetMs);
    }

    public static string DescribeFailure(DiscoveryPlan plan)
    {
        var parts = new List<string> { "mDNS", "UDP broadcast" };
        foreach (var probe in plan.Probes.Where(p => p.Reason != ProbeReason.HotspotRange).Take(4))
        {
            parts.Add($"{ReasonLabel(probe.Reason)} {probe.Ip}");
        }

        if (plan.Probes.Any(p => p.Reason == ProbeReason.HotspotRange))
        {
            parts.Add("a bounded sample of the phone-hotspot subnet");
        }

        return "Could not find the paired device. Checked " + string.Join(", ", parts)
            + ". For faster direct transfers, turn on the VPN's allow local network option, or enter its Wi-Fi address. USB still works.";
    }

    private static string ReasonLabel(ProbeReason reason) => reason switch
    {
        ProbeReason.Manual => "manual IP",
        ProbeReason.LastKnown => "last-known IP",
        ProbeReason.HotspotGateway => "hotspot gateway",
        _ => "hotspot address"
    };

    private static bool TryNormalize(string? ip, bool allowLoopback, out string normalized)
    {
        normalized = "";
        if (string.IsNullOrWhiteSpace(ip) || !System.Net.IPAddress.TryParse(ip, out var address))
        {
            return false;
        }

        if (!LocalAddressPolicy.IsLocalUnicast(address, allowLoopback))
        {
            return false;
        }

        if (AdapterRank.IsVpnRange(address.ToString()))
        {
            return false;
        }

        var bytes = address.GetAddressBytes();
        if (bytes[3] is 0 or 255 && bytes[0] != 127)
        {
            return false;
        }

        normalized = address.ToString();
        return true;
    }
}
