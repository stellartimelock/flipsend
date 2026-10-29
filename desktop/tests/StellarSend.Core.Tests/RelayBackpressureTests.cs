using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using StellarSend.Core.Pairing;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;
using StellarSend.Core.Transfer;

namespace StellarSend.Core.Tests;

[Collection("net")]
public sealed class RelayBackpressureTests
{
    [Fact(Timeout = 15000)]
    public async Task RelayMemoryStaysBoundedWhenTheReceiverIsSlow()
    {
        var hub = new RelayHub();
        var room = RelayRoom.Pairing(Guid.NewGuid());
        var listenTask = hub.JoinAsync(room, RelayRole.Listen, CancellationToken.None);
        var dialTask = hub.JoinAsync(room, RelayRole.Dial, CancellationToken.None);
        var listen = await listenTask;
        var dial = await dialTask;
        var payload = new byte[RelayBuffer.SliceBytes];
        var writing = Task.Run(async () =>
        {
            for (var i = 0; i < RelayBuffer.Capacity + 4; i++)
            {
                await dial.Stream.WriteAsync(payload);
            }
        });

        var blocked = await Task.WhenAny(writing, Task.Delay(2000));
        Assert.NotSame(writing, blocked);
        Assert.InRange(dial.OutgoingQueuedBytes, RelayBuffer.BudgetBytes, RelayBuffer.BudgetBytes + RelayBuffer.SliceBytes);

        var buffer = new byte[RelayBuffer.SliceBytes];
        long got = 0;
        var target = (long)RelayBuffer.SliceBytes * (RelayBuffer.Capacity + 4);
        while (got < target)
        {
            var n = await listen.Stream.ReadAsync(buffer);
            Assert.True(n > 0);
            got += n;
            Assert.InRange(dial.OutgoingQueuedBytes, 0, RelayBuffer.BudgetBytes + RelayBuffer.SliceBytes);
        }

        await writing.WaitAsync(TimeSpan.FromSeconds(10));
        await listen.DisposeAsync();
        await dial.DisposeAsync();
    }

    [Fact]
    public async Task ADeadListenerIsNotPairedWithADialer()
    {
        var hub = new RelayHub();
        var room = RelayRoom.Pairing(Guid.NewGuid());
        using var dead = new CancellationTokenSource();
        var listen = hub.JoinAsync(room, RelayRole.Listen, dead.Token);
        dead.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => listen);

        var dial = hub.JoinAsync(room, RelayRole.Dial, CancellationToken.None);
        var stillWaiting = await Task.WhenAny(dial, Task.Delay(300));
        Assert.NotSame(dial, stillWaiting);

        var live = await hub.JoinAsync(room, RelayRole.Listen, CancellationToken.None);
        var paired = await dial.WaitAsync(TimeSpan.FromSeconds(2));
        var payload = Encoding.UTF8.GetBytes("live-peer");
        await paired.Stream.WriteAsync(payload);
        var buffer = new byte[payload.Length];
        var read = await live.Stream.ReadAsync(buffer);
        Assert.Equal(payload.Length, read);
        Assert.Equal(payload, buffer);
        await live.DisposeAsync();
        await paired.DisposeAsync();
    }

    [Fact]
    public void RelaySlicesStayUnderTheWebsocketCap()
    {
        Assert.Equal(8 * 1024, RelayBuffer.SliceBytes);
        Assert.True(RelayBuffer.SliceBytes < 64 * 1024);
        Assert.Equal(0, ResumeAck.IntervalBytes % RelayBuffer.SliceBytes);
        Assert.Equal(RelayBuffer.BudgetBytes, ResumeAck.WindowBytes);
        Assert.Equal(15, (byte)MessageType.Ack);
    }

    [Fact(Timeout = 60000)]
    public Task ARelaySendFromThePcResumesFromTheLastAckedOffsetAfterADrop() =>
        ResumeFromLastAckedOffsetAfterADrop(phoneSends: false);

    [Fact(Timeout = 60000)]
    public Task ARelaySendFromThePhoneResumesFromTheLastAckedOffsetAfterADrop() =>
        ResumeFromLastAckedOffsetAfterADrop(phoneSends: true);

    private async Task ResumeFromLastAckedOffsetAfterADrop(bool phoneSends)
    {
        const long size = 4L * ResumeAck.IntervalBytes + 80_000;
        var dropAt = ResumeAck.IntervalBytes + ResumeAck.IntervalBytes / 2;
        using var phone = DeviceIdentity.Create("Phone");
        using var pc = DeviceIdentity.Create("Windows PC");
        var inbox = Directory.CreateTempSubdirectory("ss-relay-resume");
        var sourceDir = Directory.CreateTempSubdirectory("ss-relay-src");
        var source = Path.Combine(sourceDir.FullName, "movie.bin");
        var sha = await WritePatternAsync(source, size);
        var transferId = Guid.NewGuid();
        var receiverPeer = phoneSends
            ? new PeerRecord(pc.DeviceId, pc.Name, pc.Fingerprint, Base64Url.Encode(pc.Spki), "127.0.0.1", 9, DateTimeOffset.UtcNow)
            : new PeerRecord(phone.DeviceId, phone.Name, phone.Fingerprint, Base64Url.Encode(phone.Spki), "127.0.0.1", 9, DateTimeOffset.UtcNow);

        var first = await PairAsync(phone, pc);
        var dropped = 0;
        var receiveTls = phoneSends ? first.DialTls : first.ListenTls;
        var sendTls = phoneSends ? first.ListenTls : first.DialTls;
        var receiveIdentity = phoneSends ? pc : phone;
        var sendIdentity = phoneSends ? phone : pc;
        var receivePeer = phoneSends ? phone : pc;
        var senderLink = phoneSends ? first.Listen : first.Dial;
        var receive = ReceiveAsync(receiveTls, receiveIdentity, receivePeer, inbox.FullName, update =>
        {
            if (update.BytesTransferred >= dropAt && Interlocked.Exchange(ref dropped, 1) == 0)
            {
                senderLink.DisposeAsync().AsTask().GetAwaiter().GetResult();
            }
        });
        await using (var outgoing = await OutgoingPayload.FromFileAsync(source, CancellationToken.None))
        {
            await Assert.ThrowsAnyAsync<Exception>(() => TransferLink.SendAsync(
                sendTls.Stream,
                sendIdentity,
                receiverPeer,
                sendTls.RemoteFingerprint,
                outgoing,
                progress: null,
                CancellationToken.None,
                transferId: transferId));
        }

        await Assert.ThrowsAnyAsync<Exception>(() => receive);
        var partials = Directory.GetFiles(inbox.FullName, "*.partial");
        Assert.NotEmpty(partials);
        var partialLength = new FileInfo(partials[0]).Length;
        Assert.InRange(partialLength, ResumeAck.IntervalBytes, size - 1);

        var second = await PairAsync(phone, pc);
        var secondReceiveTls = phoneSends ? second.DialTls : second.ListenTls;
        var secondSendTls = phoneSends ? second.ListenTls : second.DialTls;
        var again = ReceiveAsync(secondReceiveTls, receiveIdentity, receivePeer, inbox.FullName, _ => { });
        await using (var outgoing = await OutgoingPayload.FromFileAsync(source, CancellationToken.None))
        {
            var sent = await TransferLink.SendAsync(
                secondSendTls.Stream,
                sendIdentity,
                receiverPeer,
                secondSendTls.RemoteFingerprint,
                outgoing,
                progress: null,
                CancellationToken.None,
                transferId: transferId);
            Assert.Equal(TransferState.Succeeded, sent.State);
            var fullChunks = (int)Math.Ceiling(size / (double)ProtocolLimits.MaxChunkData);
            Assert.True(sent.Chunks < fullChunks, $"resume sent {sent.Chunks} chunks, full file is {fullChunks}");
        }

        var received = await again.WaitAsync(TimeSpan.FromSeconds(30));
        Assert.Equal(TransferState.Succeeded, received.State);
        Assert.Equal(size, new FileInfo(received.SavedPath!).Length);
        Assert.Equal(sha, await HashFileAsync(received.SavedPath!));
        Assert.Empty(Directory.GetFiles(inbox.FullName, "*.partial"));
        await first.DisposeAsync();
        await second.DisposeAsync();
    }

    [Fact]
    public async Task QuotaTextSurfacesWhileUploading()
    {
        using var listener = new HttpListener();
        var port = FreePort();
        listener.Prefixes.Add($"http://127.0.0.1:{port}/");
        listener.Start();
        var served = Task.Run(async () =>
        {
            var context = await listener.GetContextAsync();
            var socket = (await context.AcceptWebSocketAsync(subProtocol: null)).WebSocket;
            var quota = Encoding.UTF8.GetBytes(RelayMessages.Quota(RelaySeat.Unenforced with
            {
                Blocked = true,
                Message = RelayPlans.QuotaMessage
            }));
            await socket.SendAsync(quota, WebSocketMessageType.Text, endOfMessage: true, CancellationToken.None);
            var buffer = new byte[1024];
            try
            {
                while (socket.State == WebSocketState.Open)
                {
                    var result = await socket.ReceiveAsync(buffer, CancellationToken.None);
                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        break;
                    }
                }
            }
            catch (WebSocketException)
            {
            }
        });

        using var socket = new ClientWebSocket();
        await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{port}/v1"), CancellationToken.None);
        await using var stream = new WebSocketByteStream(socket);
        var payload = new byte[1024];
        var error = await Assert.ThrowsAsync<RelayQuotaException>(async () =>
        {
            for (var i = 0; i < 40; i++)
            {
                await stream.WriteAsync(payload);
                await Task.Delay(25);
            }
        });
        Assert.Contains("relay data", error.Message, StringComparison.OrdinalIgnoreCase);
        listener.Stop();
        await served.WaitAsync(TimeSpan.FromSeconds(5));
    }

    private static async Task<string> WritePatternAsync(string path, long size)
    {
        var block = new byte[1024 * 1024];
        for (var i = 0; i < block.Length; i++)
        {
            block[i] = (byte)(i * 31);
        }

        using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        await using var file = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.Read);
        long left = size;
        while (left > 0)
        {
            var n = (int)Math.Min(block.Length, left);
            await file.WriteAsync(block.AsMemory(0, n));
            hasher.AppendData(block.AsSpan(0, n));
            left -= n;
        }

        return Convert.ToHexString(hasher.GetHashAndReset()).ToLowerInvariant();
    }

    private static async Task<string> HashFileAsync(string path)
    {
        await using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        var hash = await Hashing.HashAsync(file, CancellationToken.None);
        return hash;
    }

    private static async Task<Paired> PairAsync(DeviceIdentity phone, DeviceIdentity pc)
    {
        var hub = new RelayHub();
        var room = RelayRoom.Paired(phone.DeviceId, pc.DeviceId);
        var listenTask = hub.JoinAsync(room, RelayRole.Listen, CancellationToken.None);
        var dialTask = hub.JoinAsync(room, RelayRole.Dial, CancellationToken.None);
        var listen = await listenTask;
        var dial = await dialTask;
        var accept = TlsChannel.AcceptStreamAsync(listen.Stream, phone.Certificate, pc.Fingerprint, allowNew: false, CancellationToken.None);
        var connect = TlsChannel.ConnectStreamAsync(dial.Stream, pc.Certificate, phone.Fingerprint, CancellationToken.None);
        await Task.WhenAll(accept, connect);
        return new Paired(listen, dial, await accept, await connect);
    }

    private static Task<TransferResult> ReceiveAsync(TlsConnection tls, DeviceIdentity phone, DeviceIdentity pc, string inbox, Action<TransferProgress> onProgress)
    {
        return Task.Run(async () =>
        {
            return await TransferLink.ReceiveOneAsync(
                tls.Stream,
                phone,
                new PeerRecord(pc.DeviceId, pc.Name, pc.Fingerprint, Base64Url.Encode(pc.Spki), "127.0.0.1", 9, DateTimeOffset.UtcNow),
                tls.RemoteFingerprint,
                allowNewPeer: false,
                confirmPeer: null,
                confirmOffer: (_, _) => Task.FromResult(true),
                openFile: offer => FileReceiveSink.Create(inbox, offer.Name, offer.Sha256, offer.Size, offer.TransferId),
                onText: _ => Task.CompletedTask,
                new Progress<TransferProgress>(onProgress),
                CancellationToken.None);
        });
    }

    private static int FreePort()
    {
        using var tcp = new TcpListener(IPAddress.Loopback, 0);
        tcp.Start();
        var port = ((IPEndPoint)tcp.LocalEndpoint).Port;
        tcp.Stop();
        return port;
    }

    private sealed class Paired : IAsyncDisposable
    {
        public Paired(RelayLink listen, RelayLink dial, TlsConnection listenTls, TlsConnection dialTls)
        {
            Listen = listen;
            Dial = dial;
            ListenTls = listenTls;
            DialTls = dialTls;
        }

        public RelayLink Listen { get; }
        public RelayLink Dial { get; }
        public TlsConnection ListenTls { get; }
        public TlsConnection DialTls { get; }

        public async ValueTask DisposeAsync()
        {
            await ListenTls.DisposeAsync();
            await DialTls.DisposeAsync();
            await Listen.DisposeAsync();
            await Dial.DisposeAsync();
        }
    }
}
