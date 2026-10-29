using System.Net;
using System.Security.Cryptography;
using StellarSend.Core.Pairing;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;
using StellarSend.Core.Transfer;

namespace StellarSend.Core.Tests;

[Collection("net")]
public sealed class TransferTests
{
    [Fact]
    public async Task PinnedTlsTransfersAFileWithoutBufferingIt()
    {
        using var serverCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        using var clientCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var server = IdentityOf(serverCert, "Windows PC");
        var client = IdentityOf(clientCert, "Phone");
        var directory = Directory.CreateTempSubdirectory("ss-recv");
        var source = Path.Combine(directory.FullName, "source.bin");
        var payloadBytes = RandomNumberGenerator.GetBytes(200_000);
        await File.WriteAllBytesAsync(source, payloadBytes);

        using var listener = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var receivedText = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);

        var serverTask = Task.Run(async () =>
        {
            var tcp = await listener.AcceptTcpClientAsync();
            await using var tls = await TlsChannel.AcceptAsync(tcp, server.Certificate, null, allowNew: true, CancellationToken.None);
            return await TransferLink.ReceiveOneAsync(
                tls.Stream,
                server,
                peer: null,
                tls.RemoteFingerprint,
                allowNewPeer: true,
                confirmPeer: (_, _) => Task.FromResult(true),
                confirmOffer: (_, _) => Task.FromResult(true),
                openFile: offer => FileReceiveSink.Create(directory.FullName, offer.Name),
                onText: text =>
                {
                    receivedText.TrySetResult(text);
                    return Task.CompletedTask;
                },
                progress: null,
                CancellationToken.None);
        });

        var peer = new PeerRecord(server.DeviceId, server.Name, server.Fingerprint, Base64Url.Encode(server.Spki), "127.0.0.1", port, DateTimeOffset.UtcNow);
        await using var clientTls = await TlsChannel.ConnectAsync(new IPEndPoint(IPAddress.Loopback, port), client.Certificate, server.Fingerprint, CancellationToken.None);
        await using var outgoing = await OutgoingPayload.FromFileAsync(source, CancellationToken.None);
        var sent = await TransferLink.SendAsync(clientTls.Stream, client, peer, clientTls.RemoteFingerprint, outgoing, null, CancellationToken.None);
        var received = await serverTask;

        Assert.Equal(TransferState.Succeeded, sent.State);
        Assert.Equal(TransferState.Succeeded, received.State);
        Assert.InRange(sent.Chunks, 3, 6);
        Assert.True(File.Exists(received.SavedPath));
        Assert.Equal(payloadBytes, await File.ReadAllBytesAsync(received.SavedPath!));
        Assert.Empty(Directory.GetFiles(directory.FullName, "*.partial"));
        server.Dispose();
        client.Dispose();
    }

    [Fact]
    public async Task TextGoesBothWaysAndDeclineDoesNotWriteAFile()
    {
        await using var phone = await StartNodeAsync("Phone");
        await using var desktop = await StartNodeAsync("Windows PC");
        string? received = null;
        phone.TextReceived += value => received = value;
        phone.EnablePairingWindow(TimeSpan.FromMinutes(1));
        desktop.RememberPeer(PeerOf(phone));
        var textResult = await desktop.SendTextAsync("hello from the desktop", CancellationToken.None);
        Assert.Equal(TransferState.Succeeded, textResult.State);
        Assert.Equal("hello from the desktop", received);
        Assert.True(phone.IsPaired);

        var declined = new ScriptedConsent { AcceptIncoming = false };
        await using var choosy = await StartNodeAsync("Choosy", declined);
        choosy.EnablePairingWindow(TimeSpan.FromMinutes(1));
        await using var sender = await StartNodeAsync("Sender");
        sender.RememberPeer(PeerOf(choosy));
        var file = Path.Combine(sender.InboxDirectory, "note.txt");
        await File.WriteAllTextAsync(file, "nope");
        var declinedResult = await sender.SendFileAsync(file, CancellationToken.None);
        Assert.Equal(TransferState.Failed, declinedResult.State);
        Assert.Equal(ErrorCodes.Declined, declinedResult.Code);
        Assert.DoesNotContain(Directory.GetFiles(choosy.InboxDirectory), path => path.EndsWith("note.txt", StringComparison.Ordinal));
    }

    [Fact]
    public async Task AHandshakeThatPausesLongerThanTheOldProbeWindowStillConnects()
    {
        await using var caller = await StartNodeAsync("Caller");
        await using var listener = await StartNodeAsync("Listener");
        listener.EnablePairingWindow(TimeSpan.FromMinutes(1));
        System.Net.Sockets.TcpClient? tcp = new();
        try
        {
            await tcp.ConnectAsync(IPAddress.Loopback, listener.BoundPort);
            await Task.Delay(800);
            await using var tls = await TlsChannel.ConnectAsync(tcp, caller.Identity.Certificate, listener.Identity.Fingerprint, CancellationToken.None);
            tcp = null;
            Assert.Equal(listener.Identity.Fingerprint, tls.RemoteFingerprint);
        }
        finally
        {
            tcp?.Dispose();
        }
    }

    [Fact]
    public async Task UnknownPeerAndChangedKeyAreRejected()
    {
        using var serverCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        using var clientCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        using var listener = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var serverTask = Task.Run(async () =>
        {
            var tcp = await listener.AcceptTcpClientAsync();
            await using var tls = await TlsChannel.AcceptAsync(tcp, serverCert, expectedFingerprint: null, allowNew: false, CancellationToken.None);
            return tls;
        });
        var clientTask = TlsChannel.ConnectAsync(new IPEndPoint(IPAddress.Loopback, port), clientCert, Pin.Fingerprint(serverCert), CancellationToken.None);
        await Assert.ThrowsAnyAsync<Exception>(() => serverTask);
        await Assert.ThrowsAnyAsync<Exception>(async () =>
        {
            await using var connection = await clientTask;
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            await FrameCodec.ReadAsync(connection.Stream, timeout.Token);
        });

        var wrongPin = new string('a', 64);
        using var listener2 = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        listener2.Start();
        var port2 = ((IPEndPoint)listener2.LocalEndpoint).Port;
        var serverTask2 = Task.Run(async () =>
        {
            var tcp = await listener2.AcceptTcpClientAsync(CancellationToken.None);
            await using var tls = await TlsChannel.AcceptAsync(tcp, serverCert, Pin.Fingerprint(clientCert), allowNew: false, CancellationToken.None);
            return tls;
        });
        await Assert.ThrowsAnyAsync<Exception>(() => TlsChannel.ConnectAsync(new IPEndPoint(IPAddress.Loopback, port2), clientCert, wrongPin, CancellationToken.None));
        listener2.Stop();
    }

    [Fact]
    public async Task HandshakeRejectsAChangedKey()
    {
        using var serverCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        using var clientCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var server = IdentityOf(serverCert, "Windows PC");
        var client = IdentityOf(clientCert, "Phone");
        using var listener = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var serverTask = Task.Run(async () =>
        {
            using var tcp = await listener.AcceptTcpClientAsync();
            await using var stream = tcp.GetStream();
            await TransferLink.HandshakeAsync(stream, server, new string('b', 64), null, client.Fingerprint, false, null, CancellationToken.None);
        });
        using var clientTcp = new System.Net.Sockets.TcpClient();
        await clientTcp.ConnectAsync(IPAddress.Loopback, port);
        await using var clientStream = clientTcp.GetStream();
        var clientError = await Assert.ThrowsAnyAsync<Exception>(() => TransferLink.HandshakeAsync(
            clientStream, client, server.Fingerprint, server.DeviceId.ToString("D"), server.Fingerprint, false, null, CancellationToken.None));
        var serverError = await Assert.ThrowsAnyAsync<Exception>(() => serverTask);
        Assert.Contains("key", (clientError.Message + serverError.Message).ToLowerInvariant(), StringComparison.Ordinal);
        server.Dispose();
        client.Dispose();
    }

    [Fact]
    public async Task CancelRemovesThePartialFile()
    {
        using var serverCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        using var clientCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var server = IdentityOf(serverCert, "Windows PC");
        var client = IdentityOf(clientCert, "Phone");
        var directory = Directory.CreateTempSubdirectory("ss-cancel");
        var sourceDir = Directory.CreateTempSubdirectory("ss-cancel-src");
        var source = Path.Combine(sourceDir.FullName, "big.bin");
        await File.WriteAllBytesAsync(source, RandomNumberGenerator.GetBytes(180_000));
        using var listener = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        using var cts = new CancellationTokenSource();
        var serverTask = Task.Run(async () =>
        {
            var tcp = await listener.AcceptTcpClientAsync();
            await using var tls = await TlsChannel.AcceptAsync(tcp, server.Certificate, client.Fingerprint, allowNew: false, CancellationToken.None);
            return await TransferLink.ReceiveOneAsync(
                tls.Stream,
                server,
                PeerOf(client, port),
                tls.RemoteFingerprint,
                allowNewPeer: false,
                confirmPeer: null,
                confirmOffer: (_, _) => Task.FromResult(true),
                openFile: offer => FileReceiveSink.Create(directory.FullName, offer.Name),
                onText: _ => Task.CompletedTask,
                progress: null,
                CancellationToken.None);
        });

        await using var clientTls = await TlsChannel.ConnectAsync(new IPEndPoint(IPAddress.Loopback, port), client.Certificate, server.Fingerprint, CancellationToken.None);
        await using var outgoing = await OutgoingPayload.FromFileAsync(source, CancellationToken.None);
        var progress = new SyncProgress(update =>
        {
            if (update.BytesTransferred > 0)
            {
                cts.Cancel();
            }
        });
        var result = await TransferLink.SendAsync(clientTls.Stream, client, PeerOf(server, port), clientTls.RemoteFingerprint, outgoing, progress, cts.Token);
        var received = await serverTask;
        Assert.Equal(TransferState.Cancelled, result.State);
        Assert.Equal(TransferState.Cancelled, received.State);
        Assert.DoesNotContain(Directory.GetFiles(directory.FullName), path => Path.GetFileName(path) == "big.bin");
        Assert.Empty(Directory.GetFiles(directory.FullName, "*.partial"));
        server.Dispose();
        client.Dispose();
    }

    [Fact]
    public async Task APartialFileResumesInsteadOfStartingOver()
    {
        using var serverCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        using var clientCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var server = IdentityOf(serverCert, "Windows PC");
        var client = IdentityOf(clientCert, "Phone");
        var inbox = Directory.CreateTempSubdirectory("ss-resume");
        var sourceDir = Directory.CreateTempSubdirectory("ss-resume-src");
        var payload = RandomNumberGenerator.GetBytes(80_000);
        var source = Path.Combine(sourceDir.FullName, "payload.bin");
        await File.WriteAllBytesAsync(source, payload);
        var hash = Convert.ToHexString(SHA256.HashData(payload)).ToLowerInvariant();
        var partial = Path.Combine(inbox.FullName, "payload.bin.partial");
        await File.WriteAllBytesAsync(partial, payload.AsSpan(0, 20_000).ToArray());
        await File.WriteAllTextAsync(partial + ".meta", "{\"Sha256\":\"" + hash + "\",\"Length\":20000}");

        using var listener = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var serverTask = Task.Run(async () =>
        {
            var tcp = await listener.AcceptTcpClientAsync();
            await using var tls = await TlsChannel.AcceptAsync(tcp, server.Certificate, null, allowNew: true, CancellationToken.None);
            return await TransferLink.ReceiveOneAsync(
                tls.Stream,
                server,
                peer: null,
                tls.RemoteFingerprint,
                allowNewPeer: true,
                confirmPeer: (_, _) => Task.FromResult(true),
                confirmOffer: (_, _) => Task.FromResult(true),
                openFile: offer => FileReceiveSink.Create(inbox.FullName, offer.Name, offer.Sha256, offer.Size),
                onText: _ => Task.CompletedTask,
                progress: null,
                CancellationToken.None);
        });

        var peer = PeerOf(server, port);
        await using var clientTls = await TlsChannel.ConnectAsync(new IPEndPoint(IPAddress.Loopback, port), client.Certificate, server.Fingerprint, CancellationToken.None);
        await using var outgoing = await OutgoingPayload.FromFileAsync(source, CancellationToken.None);
        var sent = await TransferLink.SendAsync(clientTls.Stream, client, peer, clientTls.RemoteFingerprint, outgoing, null, CancellationToken.None);
        var received = await serverTask;
        Assert.Equal(TransferState.Succeeded, sent.State);
        Assert.Equal(TransferState.Succeeded, received.State);
        Assert.Equal(payload, await File.ReadAllBytesAsync(received.SavedPath!));
        Assert.Empty(Directory.GetFiles(inbox.FullName, "*.partial"));
        server.Dispose();
        client.Dispose();
    }

    [Fact]
    public async Task RePairKeepsTheOldPeerUntilBothSidesConfirm()
    {
        var directory = Directory.CreateTempSubdirectory("ss-repair");
        var log = new ListLog();
        await using var desktop = await StartNodeAsync("Windows PC", log: log, directory: directory.FullName);
        var identityPath = Path.Combine(directory.FullName, "identity.bin");
        var peerPath = Path.Combine(directory.FullName, "peer.bin");
        var originalPeer = new PeerRecord(Guid.NewGuid(), "Phone", new string('a', 64), "cHVibGlj", "127.0.0.1", desktop.BoundPort, DateTimeOffset.UtcNow);
        desktop.RememberPeer(originalPeer);
        var identityBefore = await File.ReadAllBytesAsync(identityPath);
        var peerBefore = await File.ReadAllBytesAsync(peerPath);
        var fingerprint = desktop.Identity.Fingerprint;

        desktop.RotateIdentity();
        Assert.True(desktop.RepairInProgress);
        Assert.True(desktop.IsPaired);
        Assert.NotEqual(fingerprint, desktop.Identity.Fingerprint);
        Assert.Equal(identityBefore, await File.ReadAllBytesAsync(identityPath));
        Assert.Equal(peerBefore, await File.ReadAllBytesAsync(peerPath));
        Assert.DoesNotContain(log.Lines, line => line.Contains("Device unpaired", StringComparison.Ordinal));
        Assert.DoesNotContain(log.Lines, line => line.Contains("Device keys rotated", StringComparison.Ordinal));

        desktop.CancelRepair();
        Assert.False(desktop.RepairInProgress);
        Assert.Equal(fingerprint, desktop.Identity.Fingerprint);
        Assert.Equal(originalPeer.DeviceId, desktop.Peer?.DeviceId);
        Assert.Equal(identityBefore, await File.ReadAllBytesAsync(identityPath));
        Assert.Equal(peerBefore, await File.ReadAllBytesAsync(peerPath));
        Assert.Contains(log.Lines, line => line.Contains("previous pairing is unchanged", StringComparison.Ordinal));

        desktop.RotateIdentity();
        using var phoneCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var phone = IdentityOf(phoneCert, "Phone");
        await using var tls = await TlsChannel.ConnectPairingAsync(new IPEndPoint(IPAddress.Loopback, desktop.BoundPort), phoneCert, CancellationToken.None);
        await TransferLink.HandshakeAsync(
            tls.Stream,
            phone,
            desktop.Identity.Fingerprint,
            desktop.Identity.DeviceId.ToString("D"),
            tls.RemoteFingerprint,
            allowNewPeer: false,
            confirmPeer: null,
            CancellationToken.None);

        var deadline = DateTime.UtcNow.AddSeconds(8);
        while (desktop.Peer?.DeviceId != phone.DeviceId && DateTime.UtcNow < deadline)
        {
            await Task.Delay(40);
        }

        Assert.Equal(phone.DeviceId, desktop.Peer?.DeviceId);
        Assert.NotEqual(identityBefore, await File.ReadAllBytesAsync(identityPath));
        Assert.NotEqual(peerBefore, await File.ReadAllBytesAsync(peerPath));
        Assert.False(desktop.RepairInProgress);
        Assert.Contains(log.Lines, line => line.Contains("Re-pair confirmed", StringComparison.Ordinal));
    }

    [Fact]
    public async Task RePairWithTheSamePhoneCommitsAfterBothSidesConfirm()
    {
        var directory = Directory.CreateTempSubdirectory("ss-repair-same");
        await using var desktop = await StartNodeAsync("Windows PC", directory: directory.FullName);
        using var phoneCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var phone = IdentityOf(phoneCert, "Phone");
        desktop.RememberPeer(PeerOf(phone, desktop.BoundPort));
        var identityPath = Path.Combine(directory.FullName, "identity.bin");
        var peerPath = Path.Combine(directory.FullName, "peer.bin");
        var identityBefore = await File.ReadAllBytesAsync(identityPath);
        var peerBefore = await File.ReadAllBytesAsync(peerPath);
        var fingerprint = desktop.Identity.Fingerprint;

        desktop.RotateIdentity();
        Assert.Equal(identityBefore, await File.ReadAllBytesAsync(identityPath));
        Assert.Equal(peerBefore, await File.ReadAllBytesAsync(peerPath));

        await using var tls = await TlsChannel.ConnectPairingAsync(new IPEndPoint(IPAddress.Loopback, desktop.BoundPort), phoneCert, CancellationToken.None);
        await TransferLink.HandshakeAsync(
            tls.Stream,
            phone,
            desktop.Identity.Fingerprint,
            desktop.Identity.DeviceId.ToString("D"),
            tls.RemoteFingerprint,
            allowNewPeer: false,
            confirmPeer: null,
            CancellationToken.None);

        var deadline = DateTime.UtcNow.AddSeconds(8);
        while (desktop.RepairInProgress && DateTime.UtcNow < deadline)
        {
            await Task.Delay(40);
        }

        Assert.False(desktop.RepairInProgress);
        Assert.Equal(phone.DeviceId, desktop.Peer?.DeviceId);
        Assert.NotEqual(fingerprint, desktop.Identity.Fingerprint);
        Assert.NotEqual(identityBefore, await File.ReadAllBytesAsync(identityPath));
        Assert.NotEqual(peerBefore, await File.ReadAllBytesAsync(peerPath));
    }

    [Fact]
    public async Task RepeatPairingWithTheSamePhoneKeepsTrustOnDisk()
    {
        var directory = Directory.CreateTempSubdirectory("ss-merge-trust");
        await using var desktop = await StartNodeAsync("Windows PC", directory: directory.FullName);
        var phoneId = Guid.NewGuid();
        var original = new PeerRecord(phoneId, "Phone", new string('a', 64), "old-key", "192.168.1.20", desktop.BoundPort, DateTimeOffset.UnixEpoch, new[] { "192.168.1.20" });
        desktop.RememberPeer(original);
        var peerPath = Path.Combine(directory.FullName, "peer.bin");
        var before = await File.ReadAllBytesAsync(peerPath);

        desktop.RotateIdentity();
        desktop.RememberPeer(original with
        {
            Name = "Alex's phone",
            Fingerprint = new string('b', 64),
            PublicKeySpkiBase64Url = "new-key",
            LastHost = "10.0.0.8",
            Hosts = new[] { "10.0.0.8" }
        });

        Assert.True(desktop.RepairInProgress);
        Assert.Equal(original.DeviceId, desktop.Peer?.DeviceId);
        Assert.Equal(original.Fingerprint, desktop.Peer?.Fingerprint);
        Assert.Equal(before, await File.ReadAllBytesAsync(peerPath));

        desktop.CancelRepair();
        Assert.Equal(original.DeviceId, desktop.Peer?.DeviceId);
        Assert.Equal(original.Fingerprint, desktop.Peer?.Fingerprint);
        Assert.Equal(before, await File.ReadAllBytesAsync(peerPath));
    }

    [Fact]
    public async Task AnUnpairedPcTellsThePhoneToPairAgain()
    {
        await using var desktop = await StartNodeAsync("Windows PC");
        using var phoneCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var phone = IdentityOf(phoneCert, "Phone");
        await using var tls = await TlsChannel.ConnectPairingAsync(new IPEndPoint(IPAddress.Loopback, desktop.BoundPort), phoneCert, CancellationToken.None);
        var error = await Assert.ThrowsAnyAsync<Exception>(() => TransferLink.HandshakeAsync(
            tls.Stream,
            phone,
            desktop.Identity.Fingerprint,
            desktop.Identity.DeviceId.ToString("D"),
            tls.RemoteFingerprint,
            allowNewPeer: false,
            confirmPeer: null,
            CancellationToken.None));
        Assert.Contains("Pair again", error.Message, StringComparison.Ordinal);
    }

    private static DeviceIdentity IdentityOf(System.Security.Cryptography.X509Certificates.X509Certificate2 cert, string name) =>
        new()
        {
            DeviceId = Guid.Parse(cert.GetNameInfo(System.Security.Cryptography.X509Certificates.X509NameType.SimpleName, false)),
            Name = name,
            Certificate = cert
        };

    private static PeerRecord PeerOf(DeviceIdentity identity, int port) =>
        new(identity.DeviceId, identity.Name, identity.Fingerprint, Base64Url.Encode(identity.Spki), "127.0.0.1", port, DateTimeOffset.UtcNow);

    private static PeerRecord PeerOf(StellarNode node) =>
        new(node.Identity.DeviceId, node.Identity.Name, node.Identity.Fingerprint, Base64Url.Encode(node.Identity.Spki), "127.0.0.1", node.BoundPort, DateTimeOffset.UtcNow);

    private sealed class SyncProgress : IProgress<TransferProgress>
    {
        private readonly Action<TransferProgress> _report;
        public SyncProgress(Action<TransferProgress> report) => _report = report;
        public void Report(TransferProgress value) => _report(value);
    }

    private static async Task<StellarNode> StartNodeAsync(string name, IConsent? consent = null, ILog? log = null, string? directory = null)
    {
        var node = new StellarNode(
            new StellarNodeOptions
            {
                DataDirectory = directory ?? Directory.CreateTempSubdirectory("ss-node").FullName,
                DisplayName = name,
                Port = 0,
                AllowLoopback = true
            },
            new TestProtector(),
            consent ?? new ScriptedConsent(),
            log);
        await node.StartAsync(CancellationToken.None);
        return node;
    }

    private sealed class ListLog : ILog
    {
        public List<string> Lines { get; } = [];
        public void Info(string message) => Lines.Add(message);
        public void Warn(string message) => Lines.Add(message);
        public void Error(string message) => Lines.Add(message);
    }
}
