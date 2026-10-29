using System.Net;
using System.Net.Sockets;
using StellarSend.Core.Protocol;

namespace StellarSend.Core.Discovery;

public static class LanDiscovery
{
    public static async Task<DiscoveryHit?> ListenBroadcastAsync(Guid expectedId, int timeoutMs, CancellationToken ct)
    {
        UdpClient? udp = null;
        try
        {
            udp = new UdpClient { EnableBroadcast = true };
            udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            udp.Client.Bind(new IPEndPoint(IPAddress.Any, ProtocolLimits.DiscoveryUdpPort));
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(timeoutMs);
            while (!timeout.IsCancellationRequested)
            {
                var result = await udp.ReceiveAsync(timeout.Token).ConfigureAwait(false);
                if (!BeaconCodec.TryParse(result.Buffer, out var beacon) || beacon is null)
                {
                    continue;
                }

                if (!Guid.TryParse(beacon.Id, out var id) || id != expectedId)
                {
                    continue;
                }

                if (!LocalAddressPolicy.IsLocalUnicast(result.RemoteEndPoint.Address, allowLoopback: false))
                {
                    continue;
                }

                return new DiscoveryHit(result.RemoteEndPoint.Address.ToString(), beacon.Port, "broadcast");
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or SocketException)
        {
            return null;
        }
        finally
        {
            udp?.Dispose();
        }

        return null;
    }

    public static async Task<DiscoveryHit?> ListenForCodeAsync(string code, int timeoutMs, CancellationToken ct)
    {
        UdpClient? udp = null;
        try
        {
            udp = new UdpClient { EnableBroadcast = true };
            udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            udp.Client.Bind(new IPEndPoint(IPAddress.Any, ProtocolLimits.DiscoveryUdpPort));
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(timeoutMs);
            while (!timeout.IsCancellationRequested)
            {
                var result = await udp.ReceiveAsync(timeout.Token).ConfigureAwait(false);
                if (!BeaconCodec.TryParse(result.Buffer, out var beacon) || beacon is null || string.IsNullOrEmpty(beacon.Code))
                {
                    continue;
                }

                if (!Pairing.ShortCode.Same(code, beacon.Code))
                {
                    continue;
                }

                if (!LocalAddressPolicy.IsLocalUnicast(result.RemoteEndPoint.Address, allowLoopback: false))
                {
                    continue;
                }

                return new DiscoveryHit(result.RemoteEndPoint.Address.ToString(), beacon.Port, "broadcast");
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or SocketException)
        {
            return null;
        }
        finally
        {
            udp?.Dispose();
        }

        return null;
    }

    public static async Task AnnounceAsync(Guid deviceId, int port, string name, CancellationToken ct, string? pairingCode = null)
    {
        try
        {
            using var udp = new UdpClient { EnableBroadcast = true };
            var payload = BeaconCodec.Encode(deviceId, port, name, pairingCode);
            await udp.SendAsync(payload, new IPEndPoint(IPAddress.Broadcast, ProtocolLimits.DiscoveryUdpPort), ct).ConfigureAwait(false);
            var lan = NetworkInfo.TryGetLanIpv4();
            if (lan is not null && IPAddress.TryParse(lan, out var address))
            {
                using var mdns = new UdpClient { EnableBroadcast = false };
                mdns.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                var announcement = MdnsPackets.BuildAnnouncement(deviceId, port, address);
                await mdns.SendAsync(announcement, new IPEndPoint(IPAddress.Parse("224.0.0.251"), 5353), ct).ConfigureAwait(false);
                var query = MdnsPackets.BuildQuery();
                await mdns.SendAsync(query, new IPEndPoint(IPAddress.Parse("224.0.0.251"), 5353), ct).ConfigureAwait(false);
            }
        }
        catch (Exception ex) when (ex is SocketException or OperationCanceledException)
        {
            // Discovery is best-effort. Direct IP and hotspot probes still run.
        }
    }

    public static async Task<DiscoveryHit?> ListenMdnsAsync(Guid expectedId, int timeoutMs, CancellationToken ct)
    {
        UdpClient? udp = null;
        try
        {
            udp = new UdpClient { EnableBroadcast = false };
            udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            udp.Client.Bind(new IPEndPoint(IPAddress.Any, 5353));
            udp.JoinMulticastGroup(IPAddress.Parse("224.0.0.251"));
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(timeoutMs);
            while (!timeout.IsCancellationRequested)
            {
                var result = await udp.ReceiveAsync(timeout.Token).ConfigureAwait(false);
                if (!MdnsPackets.TryParseAnnouncement(result.Buffer, out var announcement) || announcement is null)
                {
                    continue;
                }

                if (announcement.DeviceId != expectedId)
                {
                    continue;
                }

                if (!LocalAddressPolicy.IsLocalUnicast(announcement.Address, allowLoopback: false))
                {
                    continue;
                }

                return new DiscoveryHit(announcement.Address.ToString(), announcement.Port, "mdns");
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or SocketException)
        {
            return null;
        }
        finally
        {
            udp?.Dispose();
        }

        return null;
    }

    public static async Task<bool> TcpProbeAsync(ProbeTarget target, CancellationToken ct)
    {
        TcpClient? tcp = null;
        try
        {
            tcp = new TcpClient();
            if (!string.IsNullOrWhiteSpace(target.LocalAddress) && IPAddress.TryParse(target.LocalAddress, out var local))
            {
                tcp.Client.Bind(new IPEndPoint(local, 0));
            }

            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(Math.Max(50, target.TimeoutMs));
            using var abort = timeout.Token.Register(() =>
            {
                try
                {
                    tcp?.Dispose();
                }
                catch
                {
                }
            });
            await tcp.ConnectAsync(IPAddress.Parse(target.Ip), target.Port, timeout.Token).ConfigureAwait(false);
            return tcp.Connected;
        }
        catch
        {
            return false;
        }
        finally
        {
            tcp?.Dispose();
        }
    }
}

public sealed class DiscoveryException : Exception
{
    public DiscoveryException(string message) : base(message)
    {
    }
}
