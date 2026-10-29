using StellarSend.Core.Discovery;

namespace StellarSend.Core.Tests;

public sealed class DiscoveryTests
{
    [Fact]
    public void HomeNetworkDoesNotScanTheSubnet()
    {
        var plan = DiscoveryPlanner.Plan(new DiscoveryRequest("192.168.1.20", null, "192.168.1.1", "192.168.1.5", 53321));
        Assert.Equal(["192.168.1.20"], plan.Probes.Select(p => p.Ip).ToArray());
        Assert.Equal(ProbeReason.LastKnown, plan.Probes[0].Reason);
        Assert.DoesNotContain(plan.Probes, p => p.Reason == ProbeReason.HotspotRange);
        Assert.True(plan.UseMdns);
        Assert.True(plan.UseBroadcast);
        Assert.Equal(4000, plan.TotalBudgetMs);
    }

    [Fact]
    public void HotspotAddsGatewayAndABoundedRange()
    {
        var plan = DiscoveryPlanner.Plan(new DiscoveryRequest(null, null, "192.168.43.1", "192.168.43.8", 53321));
        Assert.Equal(ProbeReason.HotspotGateway, plan.Probes[0].Reason);
        Assert.Equal("192.168.43.1", plan.Probes[0].Ip);
        var range = plan.Probes.Where(p => p.Reason == ProbeReason.HotspotRange).Select(p => p.Ip).ToArray();
        Assert.Equal(DiscoveryPlanner.MaxRangeProbes, range.Length);
        Assert.All(range, ip => Assert.StartsWith("192.168.43.", ip, StringComparison.Ordinal));
        Assert.DoesNotContain("192.168.43.1", range);
        Assert.DoesNotContain("192.168.43.0", range);
        Assert.DoesNotContain("192.168.43.255", range);
        Assert.DoesNotContain("192.168.43.8", range);
    }

    [Fact]
    public void HotspotKeepsTheLastKnownHostOctetInsideTheCap()
    {
        var plan = DiscoveryPlanner.Plan(new DiscoveryRequest("192.168.1.50", null, "192.168.137.1", null, 53321));
        Assert.Contains(plan.Probes, p => p.Ip == "192.168.1.50" && p.Reason == ProbeReason.LastKnown);
        Assert.Contains(plan.Probes, p => p.Ip == "192.168.137.1" && p.Reason == ProbeReason.HotspotGateway);
        Assert.Contains(plan.Probes, p => p.Ip == "192.168.137.50" && p.Reason == ProbeReason.HotspotRange);
        Assert.Equal(DiscoveryPlanner.MaxRangeProbes, plan.Probes.Count(p => p.Reason == ProbeReason.HotspotRange));
    }

    [Fact]
    public void PublicAndDuplicateAddressesAreNotProbed()
    {
        var plan = DiscoveryPlanner.Plan(new DiscoveryRequest("8.8.8.8", "8.8.8.8", "192.168.1.1", null, 53321));
        Assert.Empty(plan.Probes);
        var manual = DiscoveryPlanner.Plan(new DiscoveryRequest("10.0.0.5", "10.0.0.5", null, "10.0.0.9", 53321));
        Assert.Single(manual.Probes);
        Assert.Equal(ProbeReason.Manual, manual.Probes[0].Reason);
    }

    [Fact]
    public void FailureMessageMentionsHotspotFallback()
    {
        var plan = DiscoveryPlanner.Plan(new DiscoveryRequest("192.168.43.10", null, "192.168.43.1", null, 53321));
        var message = DiscoveryPlanner.DescribeFailure(plan);
        Assert.Contains("mDNS", message, StringComparison.Ordinal);
        Assert.Contains("hotspot", message, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("USB", message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task CoordinatorReturnsTheFirstSuccessfulProbeAndStopsWaiting()
    {
        var started = 0;
        var plan = new DiscoveryPlan(true, true, 5000, 5000,
        [
            new ProbeTarget("192.168.43.2", 53321, ProbeReason.HotspotRange, 200),
            new ProbeTarget("192.168.43.3", 53321, ProbeReason.HotspotRange, 5000)
        ], 4000);

        var hit = await DiscoveryCoordinator.RunAsync(
            plan,
            (_, token) => Wait(token),
            (_, token) => Wait(token),
            async (probe, token) =>
            {
                Interlocked.Increment(ref started);
                if (probe.Ip.EndsWith(".2", StringComparison.Ordinal))
                {
                    return true;
                }

                await Task.Delay(Timeout.Infinite, token);
                return false;
            },
            CancellationToken.None);

        Assert.NotNull(hit);
        Assert.Equal("192.168.43.2", hit!.Ip);
        Assert.True(started <= 4);
    }

    [Fact]
    public async Task CoordinatorReturnsNullWhenProbesIgnoreCancelAndHang()
    {
        var plan = new DiscoveryPlan(false, false, 20, 20,
        [
            new ProbeTarget("192.168.1.77", 53321, ProbeReason.LastKnown, 30_000)
        ], 250);

        var started = DateTime.UtcNow;
        var hit = await DiscoveryCoordinator.RunAsync(
            plan,
            (_, _) => Task.FromResult<DiscoveryHit?>(null),
            (_, _) => Task.FromResult<DiscoveryHit?>(null),
            async (_, _) =>
            {
                await Task.Delay(30_000);
                return false;
            },
            CancellationToken.None);
        var elapsed = DateTime.UtcNow - started;

        Assert.Null(hit);
        Assert.True(elapsed.TotalMilliseconds < 2000, $"LAN search hung for {elapsed.TotalMilliseconds} ms");
    }

    [Fact]
    public async Task CoordinatorReturnsNullWhenEverythingMisses()
    {
        var plan = new DiscoveryPlan(true, false, 20, 20, [], 200);
        var hit = await DiscoveryCoordinator.RunAsync(plan, (_, _) => Task.FromResult<DiscoveryHit?>(null), (_, _) => Task.FromResult<DiscoveryHit?>(null), (_, _) => Task.FromResult(false), CancellationToken.None);
        Assert.Null(hit);
    }

    [Fact]
    public void MdnsAnnouncementRoundTrips()
    {
        var id = Guid.Parse("33333333-3333-3333-3333-333333333333");
        var packet = MdnsPackets.BuildAnnouncement(id, 53321, System.Net.IPAddress.Parse("192.168.43.12"));
        Assert.True(MdnsPackets.TryParseAnnouncement(packet, out var announcement));
        Assert.Equal(id, announcement!.DeviceId);
        Assert.Equal(53321, announcement.Port);
        Assert.Equal("192.168.43.12", announcement.Address.ToString());
        var query = MdnsPackets.BuildQuery();
        Assert.True(MdnsPackets.IsQueryForUs(query));
        Assert.False(MdnsPackets.IsQueryForUs(packet));
    }

    [Fact]
    public async Task UdpBeaconCanBeReadOnLoopback()
    {
        using var udp = new System.Net.Sockets.UdpClient(0);
        var port = ((System.Net.IPEndPoint)udp.Client.LocalEndPoint!).Port;
        var id = Guid.Parse("44444444-4444-4444-4444-444444444444");
        var payload = BeaconCodec.Encode(id, 53321, "Phone");
        await udp.SendAsync(payload, new System.Net.IPEndPoint(System.Net.IPAddress.Loopback, port));
        var received = await udp.ReceiveAsync(CancellationToken.None);
        Assert.True(BeaconCodec.TryParse(received.Buffer, out var beacon));
        Assert.Equal(id.ToString("D"), beacon!.Id);
    }

    [Fact]
    public void AdapterRankPrefersWifiAndKeepsVpnBehindVirtualAdapters()
    {
        var ranked = AdapterRank.Rank(
        [
            new AdapterSnapshot("vEthernet (WSL)", "172.19.224.1", true, true, AdapterMedium.Ethernet, true),
            new AdapterSnapshot("Surfshark WireGuard", "10.8.0.2", true, true, AdapterMedium.Vpn, false),
            new AdapterSnapshot("Wi-Fi", "10.201.100.159", true, true, AdapterMedium.Wifi, false),
            new AdapterSnapshot("Ethernet", "169.254.8.1", true, false, AdapterMedium.Ethernet, false),
            new AdapterSnapshot("Ethernet", "192.168.0.20", false, true, AdapterMedium.Ethernet, false),
            new AdapterSnapshot("Ethernet 2", "10.0.0.4", true, false, AdapterMedium.Ethernet, false)
        ]);
        Assert.Equal(["10.201.100.159", "10.0.0.4"], ranked);
        Assert.True(AdapterRank.IsVirtual("vEthernet (WSL) Hyper-V"));
        Assert.True(AdapterRank.IsVpnName("OpenVPN TAP-Windows Adapter"));
        Assert.True(AdapterRank.IsVpnName("Surfshark WireGuard"));
        Assert.True(NetworkInfo.VpnTunnelActive(
        [
            new AdapterSnapshot("Surfshark WireGuard", "10.14.0.2", true, true, AdapterMedium.Vpn, false),
            new AdapterSnapshot("Wi-Fi", "192.168.1.20", true, true, AdapterMedium.Wifi, false)
        ]));
        Assert.False(NetworkInfo.VpnTunnelActive(
        [
            new AdapterSnapshot("Wi-Fi", "192.168.1.20", true, true, AdapterMedium.Wifi, false),
            new AdapterSnapshot("Surfshark WireGuard", "10.14.0.2", false, true, AdapterMedium.Vpn, false)
        ]));
        Assert.True(AdapterRank.IsTunName("tun1"));
        Assert.True(AdapterRank.IsVpnRange("10.14.0.2"));
        Assert.False(AdapterRank.IsLanCandidate("10.8.0.2"));
        Assert.Equal("A VPN is on. Using the relay.", VpnStatus.Line);
        Assert.Contains("bypass list", VpnStatus.Describe(true, false, true)!, StringComparison.Ordinal);
        Assert.Null(VpnStatus.Describe(false, true, false));
        Assert.True(RelaySelf.SameDevice("3f84d8c1-1111-4111-8111-111111111111", "3F84D8C1-1111-4111-8111-111111111111"));
        Assert.False(RelaySelf.SameDevice(null, "3f84d8c1-1111-4111-8111-111111111111"));
    }

    [Fact]
    public void FailureMessageSuggestsAllowLocalNetwork()
    {
        var plan = DiscoveryPlanner.Plan(new DiscoveryRequest("10.0.0.8", null, null, null, 53321));
        var message = DiscoveryPlanner.DescribeFailure(plan);
        Assert.Contains("allow local network", message, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("turn the VPN off", message, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("VPN is off", message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task APhoneThatAnswersOnTheLanPortIsOnWifi()
    {
        var vpn = new LanInterface("10.8.0.2", 24, false);
        var wifi = new LanInterface("192.168.1.20", 32, true);
        LanInterface[] interfaces = [vpn, wifi];
        Assert.False(LanPresence.IsCurrent("10.0.0.50", interfaces));
        Assert.True(LanPresence.IsCurrent("192.168.1.77", interfaces));
        Assert.Equal("192.168.1.20", LanPresence.BindAddress("192.168.1.77", interfaces));

        var stale = DiscoveryPlanner.Plan(new DiscoveryRequest(
            "10.0.0.50",
            "10.0.0.9",
            "192.168.1.1",
            "192.168.1.20",
            53321,
            ExtraIps: ["192.168.0.40"],
            LocalInterfaces: interfaces));
        Assert.DoesNotContain(stale.Probes, probe => probe.Ip == "10.0.0.50");
        Assert.DoesNotContain(stale.Probes, probe => probe.Ip == "192.168.0.40");
        Assert.Contains(stale.Probes, probe => probe.Ip == "10.0.0.9" && probe.Reason == ProbeReason.Manual);
        Assert.True(stale.BroadcastTimeoutMs >= LanPresence.AnnounceIntervalMs);
        Assert.True(stale.MdnsTimeoutMs >= LanPresence.AnnounceIntervalMs);

        var current = DiscoveryPlanner.Plan(new DiscoveryRequest(
            "192.168.1.77",
            null,
            "192.168.1.1",
            "192.168.1.20",
            53321,
            LocalInterfaces: interfaces));
        var only = Assert.Single(current.Probes);
        Assert.Equal("192.168.1.77", only.Ip);
        Assert.Equal("192.168.1.20", only.LocalAddress);

        using var listener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0);
        listener.Start();
        var port = ((System.Net.IPEndPoint)listener.LocalEndpoint!).Port;
        var accept = listener.AcceptTcpClientAsync();
        var answered = await LanDiscovery.TcpProbeAsync(
            new ProbeTarget("127.0.0.1", port, ProbeReason.LastKnown, 1000, "127.0.0.1"),
            CancellationToken.None);
        Assert.True(answered);
        Assert.True(LanPresence.OnWifi(answered));
        using var accepted = await accept;
        accepted.Close();

        var search = new DiscoveryPlan(false, false, 60, 60,
        [
            new ProbeTarget("192.168.1.77", 53321, ProbeReason.LastKnown, 1000, "192.168.1.20")
        ], 60);
        var started = DateTime.UtcNow;
        var hit = await DiscoveryCoordinator.RunAsync(
            search,
            (_, _) => Task.FromResult<DiscoveryHit?>(null),
            (_, _) => Task.FromResult<DiscoveryHit?>(null),
            async (_, token) =>
            {
                await Task.Delay(150, token);
                return true;
            },
            CancellationToken.None);
        Assert.NotNull(hit);
        Assert.Equal("192.168.1.77", hit.Ip);
        Assert.True(LanPresence.OnWifi(true));
        Assert.True((DateTime.UtcNow - started).TotalMilliseconds < 1000);
    }

    private static async Task<DiscoveryHit?> Wait(CancellationToken token)
    {
        try
        {
            await Task.Delay(Timeout.Infinite, token);
        }
        catch (OperationCanceledException)
        {
        }

        return null;
    }
}
