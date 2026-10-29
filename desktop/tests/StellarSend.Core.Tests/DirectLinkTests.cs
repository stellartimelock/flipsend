using System.Net;
using System.Net.Sockets;
using StellarSend.Core.Pairing;
using StellarSend.Core.Protocol;
using StellarSend.Core.Transfer;

namespace StellarSend.Core.Tests;

public class DirectLinkTests
{
    [Fact]
    public void SeenAddressIsARelayNoteAndTargetsPreferThePublicHost()
    {
        var ready = RelayMessages.Ready(RelaySeat.Unenforced, "203.0.113.8:40000");
        Assert.Equal("203.0.113.8:40000", DirectLink.ReadSeen(ready));
        Assert.Null(DirectLink.ReadSeen(RelayMessages.Ready(RelaySeat.Unenforced)));
        Assert.Null(DirectLink.ReadSeen("{\"ok\":true,\"seen\":\"not-an-address\"}"));

        var local = new DirectOffer(Guid.NewGuid().ToString("D"), 1, 2, "198.51.100.4:9", ["10.0.0.2"]);
        var remote = new DirectOffer(Guid.NewGuid().ToString("D"), 2000, 2001, "203.0.113.8:40000", ["10.1.0.5"]);
        var targets = DirectLink.Targets(local, remote);
        Assert.Equal("203.0.113.8", targets[0].Host);
        Assert.Equal(2001, targets[0].TcpPort);
        Assert.Contains(targets, target => target.Host == "10.1.0.5");
        Assert.Equal(TransferPath.Cross, DirectLink.Choose(punched: true, wifiDirectUp: false));
        Assert.Equal(TransferPath.Relay, DirectLink.Choose(punched: false, wifiDirectUp: false));
    }

    [Fact]
    public async Task PunchOpensADirectTlsSocketWithThePairedCertificates()
    {
        using var leftId = DeviceIdentity.Create("pc");
        using var rightId = DeviceIdentity.Create("phone");
        using var left = DirectAttempt.Start("127.0.0.1:9", ["127.0.0.1"]);
        using var right = DirectAttempt.Start("127.0.0.1:9", ["127.0.0.1"]);
        var helloLeft = Hello(leftId, left.Offer);
        var helloRight = Hello(rightId, right.Offer);
        var opened = await Task.WhenAll(
            left.FinishAsync(leftId, helloRight, CancellationToken.None),
            right.FinishAsync(rightId, helloLeft, CancellationToken.None));
        Assert.NotNull(opened[0]);
        Assert.NotNull(opened[1]);
        await using var a = opened[0]!;
        await using var b = opened[1]!;
        await a.Stream.WriteAsync(new byte[] { 7 });
        await a.Stream.FlushAsync();
        var buffer = new byte[1];
        var read = await b.Stream.ReadAsync(buffer);
        Assert.Equal(1, read);
        Assert.Equal(7, buffer[0]);
        Assert.Equal(leftId.Fingerprint, b.RemoteFingerprint);
        Assert.Equal(rightId.Fingerprint, a.RemoteFingerprint);
    }

    [Fact]
    public async Task PunchGivesUpSoTheRelayCanCarryTheBytes()
    {
        using var id = DeviceIdentity.Create("pc");
        using var attempt = DirectAttempt.Start(null, []);
        var dead = new TcpListener(IPAddress.Loopback, 0);
        dead.Start();
        var port = ((IPEndPoint)dead.LocalEndpoint).Port;
        dead.Stop();
        var remote = Hello(id, new DirectOffer(Guid.NewGuid().ToString("D"), port, port, "127.0.0.1:" + port, null));
        remote = remote with { DeviceId = Guid.NewGuid().ToString("D"), Fingerprint = id.Fingerprint };
        var started = DateTime.UtcNow;
        var opened = await attempt.FinishAsync(id, remote, CancellationToken.None, budgetMs: 900);
        Assert.Null(opened);
        Assert.True(DateTime.UtcNow - started < TimeSpan.FromSeconds(5));
    }

    [Fact]
    public void DirectRouteShowsWhenThatPathIsUp()
    {
        var face = ConnectionState.Face(new ConnectionInput(
            true, true, false, false, false, true, true, false, true, false, false));
        Assert.Equal("Connected", face.Headline);
        Assert.Equal("Direct", face.PathPill);
        Assert.Contains("Direct now", face.RoutesLine);
        Assert.True(face.Agrees());
    }

    [Fact]
    public async Task WindowsRebindsWhenItsAddressesChange()
    {
        var node = new StellarNode(
            new StellarNodeOptions
            {
                DataDirectory = Directory.CreateTempSubdirectory("ss-hop").FullName,
                DisplayName = "pc",
                Port = 0,
                AllowLoopback = true
            },
            new TestProtector(),
            new ScriptedConsent());
        await node.StartAsync(CancellationToken.None);
        try
        {
            var before = node.NetworkHops;
            node.NoteLanAddresses(["10.8.0.2"]);
            var once = node.NetworkHops;
            node.NoteLanAddresses(["10.8.0.2"]);
            Assert.Equal(once, node.NetworkHops);
            node.NoteLanAddresses(["10.9.0.3"]);
            Assert.True(node.NetworkHops > before);
            Assert.Equal(NetworkHop.Line, node.LastError);
            Assert.True(node.BoundPort > 0);
        }
        finally
        {
            await node.DisposeAsync();
        }
    }

    private static HelloBody Hello(DeviceIdentity id, DirectOffer offer) =>
        new(ProtocolLimits.Version, id.DeviceId.ToString("D"), id.Name, id.Fingerprint, Direct: offer);
}
