using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using StellarSend.Core.Pairing;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;
using StellarSend.Core.Transfer;

namespace StellarSend.Core.Tests;

/// <summary>
/// Locks the 1.5.46 pairing identity and handshake. PC builds 1.5.47–1.5.52
/// changed TlsChannel / identity load and the phone hung up.
/// </summary>
[Collection("net")]
public sealed class PairingRegressionTests
{
    [Fact]
    public void SameIdentityFilePresentsThe1546CertAndPin()
    {
        var directory = Directory.CreateTempSubdirectory("ss-id-1546");
        var path = Path.Combine(directory.FullName, "identity.bin");
        var store = new IdentityStore(new TestProtector());
        using var created = store.LoadOrCreate(path, "Windows PC");
        var expectedPin = created.Fingerprint;
        var expectedId = created.DeviceId;
        var expectedDer = created.Certificate.Export(X509ContentType.Cert);
        var expectedSpki = created.Spki;

        var protectedBytes = File.ReadAllBytes(path);
        var pfx = new TestProtector().Unprotect(protectedBytes);
        using var loaded1546 = FlipSend1546.LoadIdentityPfx(pfx);
        CryptographicOperations.ZeroMemory(pfx);

        Assert.Equal(expectedId, Guid.Parse(loaded1546.GetNameInfo(X509NameType.SimpleName, false)));
        Assert.Equal(expectedPin, Pin.Fingerprint(loaded1546));
        Assert.Equal(expectedDer, loaded1546.Export(X509ContentType.Cert));
        Assert.Equal(expectedSpki, loaded1546.PublicKey.ExportSubjectPublicKeyInfo());

        using var reopened = store.LoadOrCreate(path, "Windows PC");
        Assert.Equal(expectedPin, reopened.Fingerprint);
        Assert.Equal(expectedDer, reopened.Certificate.Export(X509ContentType.Cert));
        Assert.Equal(X509KeyStorageFlags.Exportable, FlipSend1546.PfxFlags);
        Assert.Equal(0, (int)(FlipSend1546.PfxFlags & X509KeyStorageFlags.EphemeralKeySet));
    }

    [Fact]
    public async Task ExistingPairCompletesThe1546HandshakeAndATransfer()
    {
        var directory = Directory.CreateTempSubdirectory("ss-pair-1546");
        var identityPath = Path.Combine(directory.FullName, "identity.bin");
        var peerPath = Path.Combine(directory.FullName, "peer.bin");
        var protector = new TestProtector();
        var identities = new IdentityStore(protector);
        using var pcIdentity = identities.LoadOrCreate(identityPath, "Windows PC");
        using var phoneCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var phone = IdentityOf(phoneCert, "Phone");
        new PeerStore(protector).Save(peerPath, new PeerRecord(
            phone.DeviceId,
            phone.Name,
            phone.Fingerprint,
            Base64Url.Encode(phone.Spki),
            "127.0.0.1",
            0,
            DateTimeOffset.UtcNow));

        await using var desktop = await StartPcAsync(directory.FullName);
        Assert.True(desktop.IsPaired);
        Assert.Equal(pcIdentity.Fingerprint, desktop.Identity.Fingerprint);
        Assert.Equal(phone.Fingerprint, desktop.Peer?.Fingerprint);

        Assert.Equal(
            pcIdentity.Certificate.Export(X509ContentType.Cert),
            desktop.Identity.Certificate.Export(X509ContentType.Cert));

        string? got = null;
        desktop.TextReceived += value => got = value;
        await using var phoneTls = await PhoneTls.ConnectAsync(
            new IPEndPoint(IPAddress.Loopback, desktop.BoundPort),
            phoneCert,
            desktop.Identity.Fingerprint,
            CancellationToken.None);
        Assert.Equal(desktop.Identity.Fingerprint, phoneTls.RemoteFingerprint);

        var pcPeer = new PeerRecord(
            desktop.Identity.DeviceId,
            desktop.Identity.Name,
            desktop.Identity.Fingerprint,
            Base64Url.Encode(desktop.Identity.Spki),
            "127.0.0.1",
            desktop.BoundPort,
            DateTimeOffset.UtcNow);
        await using var outgoing = OutgoingPayload.FromText("hello from the 1.5.46 phone client");
        var sent = await TransferLink.SendAsync(
            phoneTls.Stream,
            phone,
            pcPeer,
            phoneTls.RemoteFingerprint,
            outgoing,
            null,
            CancellationToken.None);
        var deadline = DateTime.UtcNow.AddSeconds(6);
        while (got is null && DateTime.UtcNow < deadline)
        {
            await Task.Delay(40);
        }

        Assert.Equal(TransferState.Succeeded, sent.State);
        Assert.Equal("hello from the 1.5.46 phone client", got);
        Assert.Equal(phone.Fingerprint, desktop.Peer?.Fingerprint);

        string? saved = null;
        desktop.FileReceived += path => saved = path;
        var fileDir = Directory.CreateTempSubdirectory("ss-pair-1546-file");
        var source = Path.Combine(fileDir.FullName, "from-phone.bin");
        var payload = Enumerable.Range(0, 12_288).Select(i => (byte)(i % 251)).ToArray();
        await File.WriteAllBytesAsync(source, payload);
        await using var phoneFileTls = await PhoneTls.ConnectAsync(
            new IPEndPoint(IPAddress.Loopback, desktop.BoundPort),
            phoneCert,
            desktop.Identity.Fingerprint,
            CancellationToken.None);
        Assert.Equal(desktop.Identity.Fingerprint, phoneFileTls.RemoteFingerprint);
        await using var fileOutgoing = await OutgoingPayload.FromFileAsync(source, CancellationToken.None);
        var fileSent = await TransferLink.SendAsync(
            phoneFileTls.Stream,
            phone,
            pcPeer,
            phoneFileTls.RemoteFingerprint,
            fileOutgoing,
            null,
            CancellationToken.None);
        var fileDeadline = DateTime.UtcNow.AddSeconds(8);
        while (saved is null && DateTime.UtcNow < fileDeadline)
        {
            await Task.Delay(40);
        }

        Assert.Equal(TransferState.Succeeded, fileSent.State);
        Assert.NotNull(saved);
        Assert.Equal(payload, await File.ReadAllBytesAsync(saved));
        Assert.False(TrustNewKey.ShouldPrompt(true, ErrorCodes.KeyChanged, "The paired device key changed.", phoneFileTls.RemoteFingerprint, desktop.Identity.Fingerprint));
        Assert.Equal(File.ReadAllBytes(identityPath).Length, (await File.ReadAllBytesAsync(identityPath)).Length);
    }

    [Fact]
    public async Task ExistingPairCompletesPcToPhoneTextAndFile()
    {
        using var phoneCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        using var pcCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var phone = IdentityOf(phoneCert, "Phone");
        var pc = IdentityOf(pcCert, "Windows PC");
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        string? gotText = null;
        var inbox = Directory.CreateTempSubdirectory("ss-pc-to-phone");
        var receive = Task.Run(async () =>
        {
            using var tcp = await listener.AcceptTcpClientAsync();
            await using var tls = await TlsChannel.AcceptAsync(tcp, phoneCert, pc.Fingerprint, allowNew: false, CancellationToken.None);
            Assert.Equal(pc.Fingerprint, tls.RemoteFingerprint);
            return await TransferLink.ReceiveOneAsync(
                tls.Stream,
                phone,
                new PeerRecord(pc.DeviceId, pc.Name, pc.Fingerprint, Base64Url.Encode(pc.Spki), "127.0.0.1", port, DateTimeOffset.UtcNow),
                tls.RemoteFingerprint,
                allowNewPeer: false,
                confirmPeer: null,
                confirmOffer: (_, _) => Task.FromResult(true),
                openFile: offer => FileReceiveSink.Create(inbox.FullName, offer.Name, offer.Sha256, offer.Size, offer.TransferId),
                onText: text => { gotText = text; return Task.CompletedTask; },
                progress: null,
                CancellationToken.None);
        });

        await using var tls = await TlsChannel.ConnectAsync(
            new IPEndPoint(IPAddress.Loopback, port),
            pcCert,
            phone.Fingerprint,
            CancellationToken.None);
        Assert.Equal(phone.Fingerprint, tls.RemoteFingerprint);
        await using var outgoing = OutgoingPayload.FromText("hello from the 1.5.56 pc client");
        var sent = await TransferLink.SendAsync(
            tls.Stream,
            pc,
            new PeerRecord(phone.DeviceId, phone.Name, phone.Fingerprint, Base64Url.Encode(phone.Spki), "127.0.0.1", port, DateTimeOffset.UtcNow),
            tls.RemoteFingerprint,
            outgoing,
            null,
            CancellationToken.None);
        var received = await receive.WaitAsync(TimeSpan.FromSeconds(8));
        Assert.Equal(TransferState.Succeeded, sent.State);
        Assert.Equal(TransferState.Succeeded, received.State);
        Assert.Equal("hello from the 1.5.56 pc client", gotText);

        var fileInbox = Directory.CreateTempSubdirectory("ss-pc-to-phone-file");
        var sourceDir = Directory.CreateTempSubdirectory("ss-pc-to-phone-src");
        var source = Path.Combine(sourceDir.FullName, "from-pc.bin");
        var payload = Enumerable.Range(0, 8_192).Select(i => (byte)(i % 199)).ToArray();
        await File.WriteAllBytesAsync(source, payload);
        var receiveFile = Task.Run(async () =>
        {
            using var tcp = await listener.AcceptTcpClientAsync();
            await using var fileTls = await TlsChannel.AcceptAsync(tcp, phoneCert, pc.Fingerprint, allowNew: false, CancellationToken.None);
            Assert.Equal(pc.Fingerprint, fileTls.RemoteFingerprint);
            return await TransferLink.ReceiveOneAsync(
                fileTls.Stream,
                phone,
                new PeerRecord(pc.DeviceId, pc.Name, pc.Fingerprint, Base64Url.Encode(pc.Spki), "127.0.0.1", port, DateTimeOffset.UtcNow),
                fileTls.RemoteFingerprint,
                allowNewPeer: false,
                confirmPeer: null,
                confirmOffer: (_, _) => Task.FromResult(true),
                openFile: offer => FileReceiveSink.Create(fileInbox.FullName, offer.Name, offer.Sha256, offer.Size, offer.TransferId),
                onText: _ => Task.CompletedTask,
                progress: null,
                CancellationToken.None);
        });
        await using var pcFileTls = await TlsChannel.ConnectAsync(
            new IPEndPoint(IPAddress.Loopback, port),
            pcCert,
            phone.Fingerprint,
            CancellationToken.None);
        Assert.Equal(phone.Fingerprint, pcFileTls.RemoteFingerprint);
        await using var fileOutgoing = await OutgoingPayload.FromFileAsync(source, CancellationToken.None);
        var fileSent = await TransferLink.SendAsync(
            pcFileTls.Stream,
            pc,
            new PeerRecord(phone.DeviceId, phone.Name, phone.Fingerprint, Base64Url.Encode(phone.Spki), "127.0.0.1", port, DateTimeOffset.UtcNow),
            pcFileTls.RemoteFingerprint,
            fileOutgoing,
            null,
            CancellationToken.None);
        var fileReceived = await receiveFile.WaitAsync(TimeSpan.FromSeconds(8));
        Assert.Equal(TransferState.Succeeded, fileSent.State);
        Assert.Equal(TransferState.Succeeded, fileReceived.State);
        Assert.Equal(payload, await File.ReadAllBytesAsync(fileReceived.SavedPath!));
    }

    [Fact]
    public async Task AcceptCapturesThePhoneClientPinSoTrustDoesNotSeeNone()
    {
        using var serverCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        using var clientCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var accept = Task.Run(async () =>
        {
            using var tcp = await listener.AcceptTcpClientAsync();
            return await TlsChannel.AcceptAsync(tcp, serverCert, Pin.Fingerprint(clientCert), allowNew: false, CancellationToken.None);
        });
        await using var phoneTls = await PhoneTls.ConnectAsync(
            new IPEndPoint(IPAddress.Loopback, port),
            clientCert,
            Pin.Fingerprint(serverCert),
            CancellationToken.None);
        await using var accepted = await accept.WaitAsync(TimeSpan.FromSeconds(8));
        Assert.Equal(Pin.Fingerprint(clientCert), accepted.RemoteFingerprint);
        Assert.Equal(Pin.Fingerprint(serverCert), phoneTls.RemoteFingerprint);
        Assert.True(PinFormat.IsWellFormed(accepted.RemoteFingerprint));
        Assert.NotEqual("none", TrustNewKey.ShortPin(accepted.RemoteFingerprint));
        Assert.False(TrustNewKey.ShouldRaiseKeyChanged(accepted.RemoteFingerprint, Pin.Fingerprint(clientCert)));
        Assert.False(TrustNewKey.ShouldPrompt(true, ErrorCodes.KeyChanged, "The paired device key changed.", accepted.RemoteFingerprint, Pin.Fingerprint(clientCert)));
    }

    [Fact]
    public void ConnectionFaceDoesNotShowTrustWhenThePresentedPinWasMissing()
    {
        var face = ConnectionState.Face(new ConnectionInput(
            Paired: true,
            Listening: true,
            PairingWindowOpen: false,
            OnLan: true,
            LanUp: true,
            DirectUp: false,
            DirectAvailable: false,
            RelayUp: false,
            RelayAvailable: true,
            UsbUp: false,
            UsbAvailable: false,
            LastError: "The transfer did not finish. FlipSend will try again."));
        Assert.NotEqual("Keys don't match", face.Headline);
        Assert.True(face.Connected);
        Assert.False(TrustNewKey.Applies(true, null, "The transfer did not finish. FlipSend will try again."));
    }

    [Fact]
    public void TrustNeverPromptsWhenThePresentedPinMatchesOrIsMissing()
    {
        var stored = "24c159d8" + new string('a', 56);
        Assert.False(TrustNewKey.ShouldPrompt(true, ErrorCodes.KeyChanged, "The paired device key changed.", "none", stored));
        Assert.False(TrustNewKey.ShouldPrompt(true, ErrorCodes.KeyChanged, "The paired device key changed.", "", stored));
        Assert.False(TrustNewKey.ShouldPrompt(true, ErrorCodes.KeyChanged, null, stored, stored));
        Assert.False(TrustNewKey.ShouldRaiseKeyChanged("", stored));
        Assert.False(TrustNewKey.ShouldRaiseKeyChanged(stored, stored));
        Assert.True(TrustNewKey.ShouldRaiseKeyChanged(new string('b', 64), stored));
        Assert.True(TrustNewKey.ShouldPrompt(true, ErrorCodes.KeyChanged, "The paired device key changed.", new string('b', 64), stored));
    }

    [Fact]
    public async Task HandshakeDoesNotClaimKeyChangedWhenTheTlsPinHasNotBeenRead()
    {
        using var serverCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        using var clientCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var server = IdentityOf(serverCert, "Windows PC");
        var client = IdentityOf(clientCert, "Phone");
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var serverTask = Task.Run(async () =>
        {
            using var tcp = await listener.AcceptTcpClientAsync();
            await using var stream = tcp.GetStream();
            await TransferLink.HandshakeAsync(
                stream, server, client.Fingerprint, client.DeviceId.ToString("D"), "", false, null, CancellationToken.None);
        });
        using var clientTcp = new TcpClient();
        await clientTcp.ConnectAsync(IPAddress.Loopback, port);
        await using var clientStream = clientTcp.GetStream();
        var clientError = await Assert.ThrowsAnyAsync<Exception>(() => TransferLink.HandshakeAsync(
            clientStream, client, server.Fingerprint, server.DeviceId.ToString("D"), server.Fingerprint, false, null, CancellationToken.None));
        var serverError = await Assert.ThrowsAnyAsync<Exception>(() => serverTask);
        Assert.DoesNotContain("key changed", (clientError.Message + " " + serverError.Message), StringComparison.OrdinalIgnoreCase);
        server.Dispose();
        client.Dispose();
    }

    [Fact]
    public async Task AWrongPinIsStillRejectedThe1546Way()
    {
        using var serverCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        using var clientCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var accept = Task.Run(async () =>
        {
            var tcp = await listener.AcceptTcpClientAsync();
            await using var tls = await TlsChannel.AcceptAsync(tcp, serverCert, Pin.Fingerprint(clientCert), allowNew: false, CancellationToken.None);
            return tls.RemoteFingerprint;
        });

        await Assert.ThrowsAnyAsync<Exception>(() => PhoneTls.ConnectAsync(
            new IPEndPoint(IPAddress.Loopback, port),
            clientCert,
            new string('a', 64),
            CancellationToken.None));
        _ = accept.ContinueWith(_ => { }, TaskScheduler.Default);
    }

    private static DeviceIdentity IdentityOf(X509Certificate2 cert, string name) =>
        new()
        {
            DeviceId = Guid.Parse(cert.GetNameInfo(X509NameType.SimpleName, false)),
            Name = name,
            Certificate = cert
        };

    private static async Task<StellarNode> StartPcAsync(string directory)
    {
        var node = new StellarNode(
            new StellarNodeOptions
            {
                DataDirectory = directory,
                DisplayName = "Windows PC",
                Port = 0,
                AllowLoopback = true
            },
            new TestProtector(),
            new ScriptedConsent(),
            null);
        await node.StartAsync(CancellationToken.None);
        return node;
    }
}

/// <summary>
/// 1.5.46 loaded identity.bin with Exportable PFX flags and no EphemeralKeySet.
/// </summary>
public static class FlipSend1546
{
    public static X509KeyStorageFlags PfxFlags => X509KeyStorageFlags.Exportable;

    public static X509Certificate2 LoadIdentityPfx(byte[] pfx) =>
        X509CertificateLoader.LoadPkcs12(pfx, ReadOnlySpan<char>.Empty, PfxFlags);
}

/// <summary>
/// Phone-side TLS from android/core Identity.kt TlsPins: TLSv1.2, device cert,
/// capture the peer cert, then check the pin the way 1.5.46 TlsChannel did.
/// </summary>
public static class PhoneTls
{
    public static Task<TlsConnection> ConnectPairingAsync(
        IPEndPoint endpoint,
        X509Certificate2 certificate,
        CancellationToken ct) =>
        ConnectAsync(endpoint, certificate, expectedFingerprint: null, allowNew: true, ct);

    public static Task<TlsConnection> ConnectAsync(
        IPEndPoint endpoint,
        X509Certificate2 certificate,
        string expectedFingerprint,
        CancellationToken ct) =>
        ConnectAsync(endpoint, certificate, expectedFingerprint, allowNew: false, ct);

    public static async Task<TlsConnection> ConnectAsync(
        IPEndPoint endpoint,
        X509Certificate2 certificate,
        string? expectedFingerprint,
        bool allowNew,
        CancellationToken ct)
    {
        var tcp = new TcpClient();
        tcp.NoDelay = true;
        try
        {
            await tcp.ConnectAsync(endpoint, ct).ConfigureAwait(false);
            string? seenPin = null;
            byte[]? seenSpki = null;
            RemoteCertificateValidationCallback callback = (_, cert, _, _) =>
            {
                if (cert is null)
                {
                    return false;
                }

                var der = cert.Export(X509ContentType.Cert);
                using var parsed = X509CertificateLoader.LoadCertificate(der);
                seenPin = Pin.Fingerprint(parsed);
                seenSpki = parsed.PublicKey.ExportSubjectPublicKeyInfo();
                return true;
            };

            var ssl = new SslStream(tcp.GetStream(), leaveInnerStreamOpen: false, callback);
            await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
            {
                TargetHost = endpoint.Address.ToString(),
                ClientCertificates = new X509CertificateCollection { certificate },
                EnabledSslProtocols = SslProtocols.Tls12,
                CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                RemoteCertificateValidationCallback = callback,
                LocalCertificateSelectionCallback = (_, _, _, _, _) => certificate
            }, ct).ConfigureAwait(false);

            if (seenPin is null || seenSpki is null)
            {
                await ssl.DisposeAsync().ConfigureAwait(false);
                throw PeerRejectedException.Unknown();
            }

            if (expectedFingerprint is not null && !Pin.FixedEquals(seenPin, expectedFingerprint))
            {
                await ssl.DisposeAsync().ConfigureAwait(false);
                throw PeerRejectedException.KeyChanged();
            }

            if (expectedFingerprint is null && !allowNew)
            {
                await ssl.DisposeAsync().ConfigureAwait(false);
                throw PeerRejectedException.Unknown();
            }

            return new TlsConnection(tcp, ssl, seenPin, seenSpki);
        }
        catch
        {
            tcp.Dispose();
            throw;
        }
    }
}
