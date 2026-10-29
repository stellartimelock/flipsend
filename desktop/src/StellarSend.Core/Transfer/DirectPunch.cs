using System.Net;
using System.Net.Sockets;
using StellarSend.Core.Pairing;
using StellarSend.Core.Protocol;

namespace StellarSend.Core.Transfer;

/// <summary>
/// Binds a UDP punch socket and a TCP port, then tries the peer's public address and LAN addresses.
/// The winner is a TLS socket using the existing device certificate. A miss returns null so the relay carries the file.
/// </summary>
public sealed class DirectAttempt : IDisposable
{
    private readonly Socket _udp;
    private readonly TcpListener _tcp;
    private readonly Guid _token;
    private bool _disposed;

    private DirectAttempt(Socket udp, TcpListener tcp, Guid token, DirectOffer offer)
    {
        _udp = udp;
        _tcp = tcp;
        _token = token;
        Offer = offer;
    }

    public DirectOffer Offer { get; }

    public static DirectAttempt Start(string? seen, IReadOnlyList<string>? lan)
    {
        var udp = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        udp.Bind(new IPEndPoint(IPAddress.Any, 0));
        var tcp = new TcpListener(IPAddress.Any, 0);
        tcp.Start();
        var token = Guid.NewGuid();
        var hosts = (lan ?? [])
            .Where(host => IPAddress.TryParse(host, out var ip) && ip.AddressFamily == AddressFamily.InterNetwork)
            .Distinct(StringComparer.Ordinal)
            .Take(8)
            .ToArray();
        var offer = new DirectOffer(
            token.ToString("D"),
            ((IPEndPoint)udp.LocalEndPoint!).Port,
            ((IPEndPoint)tcp.LocalEndpoint).Port,
            DirectLink.IsEndpoint(seen) ? seen : null,
            hosts.Length == 0 ? null : hosts);
        return new DirectAttempt(udp, tcp, token, offer);
    }

    public async Task<TlsConnection?> FinishAsync(DeviceIdentity local, HelloBody remote, CancellationToken ct, int budgetMs = DirectLink.BudgetMs)
    {
        if (remote.Direct is null || !Guid.TryParse(remote.Direct.Token, out var peerToken) || peerToken == _token)
        {
            return null;
        }

        var targets = DirectLink.Targets(Offer, remote.Direct);
        if (targets.Count == 0)
        {
            return null;
        }

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(budgetMs);
        var token = timeout.Token;
        TcpClient? winner = null;
        try
        {
            var client = DirectLink.IAmClient(local.DeviceId.ToString("D"), remote.DeviceId);
            winner = await RaceAsync(peerToken, targets, client, token).ConfigureAwait(false);
            if (winner is null)
            {
                return null;
            }

            var punched = client
                ? await TlsChannel.ConnectAsync(winner, local.Certificate, remote.Fingerprint, token).ConfigureAwait(false)
                : await TlsChannel.AcceptAsync(winner, local.Certificate, remote.Fingerprint, allowNew: false, token).ConfigureAwait(false);
            winner = null;
            return punched;
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex) when (ex is IOException or SocketException or TimeoutException or OperationCanceledException or System.Security.Authentication.AuthenticationException or ProtocolException or PeerRejectedException)
        {
            return null;
        }
        finally
        {
            winner?.Dispose();
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        try
        {
            _udp.Dispose();
        }
        catch (SocketException)
        {
        }

        try
        {
            _tcp.Stop();
        }
        catch (SocketException)
        {
        }
    }

    private async Task<TcpClient?> RaceAsync(Guid peerToken, IReadOnlyList<PunchTarget> targets, bool iAmClient, CancellationToken ct)
    {
        var accepted = ChannelOf();
        var connected = ChannelOf();
        var udp = Task.Run(() => ProbeUdp(peerToken, targets, ct), ct);
        var accept = AcceptLoop(accepted, ct);
        await Task.WhenAny(udp, Task.Delay(300, ct)).ConfigureAwait(false);
        var connect = Task.Run(() => ConnectLoop(targets, connected, ct), ct);
        TcpClient? winner = null;
        var fallbackAfter = DateTime.UtcNow.AddMilliseconds(700);
        try
        {
            while (!ct.IsCancellationRequested && winner is null)
            {
                var preferred = iAmClient ? connected : accepted;
                var other = iAmClient ? accepted : connected;
                if (preferred.Reader.TryRead(out var hit))
                {
                    winner = hit;
                    break;
                }

                if (DateTime.UtcNow >= fallbackAfter && other.Reader.TryRead(out var fallback))
                {
                    winner = fallback;
                    break;
                }

                await Task.Delay(20, ct).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }

        Drain(accepted, winner);
        Drain(connected, winner);
        _ = udp;
        _ = accept;
        _ = connect;
        return winner;
    }

    private void ProbeUdp(Guid peerToken, IReadOnlyList<PunchTarget> targets, CancellationToken ct)
    {
        var packet = DirectLink.Packet(_token);
        var buffer = new byte[64];
        EndPoint remote = new IPEndPoint(IPAddress.Any, 0);
        while (!ct.IsCancellationRequested)
        {
            foreach (var target in targets)
            {
                try
                {
                    _udp.SendTo(packet, new IPEndPoint(IPAddress.Parse(target.Host), target.UdpPort));
                }
                catch (Exception ex) when (ex is SocketException or ObjectDisposedException or OperationCanceledException)
                {
                    return;
                }
            }

            try
            {
                if (_udp.Poll(100_000, SelectMode.SelectRead))
                {
                    var read = _udp.ReceiveFrom(buffer, ref remote);
                    if (DirectLink.TryReadPacket(buffer.AsSpan(0, read), out var token) && token == peerToken)
                    {
                        return;
                    }
                }
            }
            catch (Exception ex) when (ex is SocketException or ObjectDisposedException)
            {
                return;
            }
        }
    }

    private async Task AcceptLoop(System.Threading.Channels.Channel<TcpClient> accepted, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var tcp = await _tcp.AcceptTcpClientAsync(ct).ConfigureAwait(false);
                tcp.NoDelay = true;
                if (!accepted.Writer.TryWrite(tcp))
                {
                    tcp.Dispose();
                }
            }
            catch (Exception ex) when (ex is SocketException or InvalidOperationException or ObjectDisposedException or OperationCanceledException)
            {
                return;
            }
        }
    }

    private static void ConnectLoop(IReadOnlyList<PunchTarget> targets, System.Threading.Channels.Channel<TcpClient> connected, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            foreach (var target in targets)
            {
                if (ct.IsCancellationRequested)
                {
                    return;
                }

                var tcp = new TcpClient { NoDelay = true };
                try
                {
                    var joined = tcp.ConnectAsync(target.Host, target.TcpPort, ct).AsTask();
                    if (!joined.Wait(800, ct) || joined.IsFaulted || !tcp.Connected)
                    {
                        tcp.Dispose();
                        continue;
                    }

                    if (!connected.Writer.TryWrite(tcp))
                    {
                        tcp.Dispose();
                    }
                }
                catch (Exception ex) when (ex is SocketException or OperationCanceledException or AggregateException or ObjectDisposedException)
                {
                    tcp.Dispose();
                }
            }
        }
    }

    private static System.Threading.Channels.Channel<TcpClient> ChannelOf() =>
        System.Threading.Channels.Channel.CreateBounded<TcpClient>(new System.Threading.Channels.BoundedChannelOptions(4)
        {
            FullMode = System.Threading.Channels.BoundedChannelFullMode.Wait,
            SingleReader = true,
            SingleWriter = false
        });

    private static void Drain(System.Threading.Channels.Channel<TcpClient> channel, TcpClient? keep)
    {
        while (channel.Reader.TryRead(out var extra))
        {
            if (!ReferenceEquals(extra, keep))
            {
                extra.Dispose();
            }
        }
    }
}
