using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using StellarSend.Core.Companion;
using StellarSend.Core.Pairing;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;
using StellarSend.Core.Terminal;
using StellarSend.Core.Transfer;

namespace StellarSend.Core.Tests;

public sealed class TerminalTests
{
    [Fact]
    public void DestructiveCommandsAreTheNamedToolsOnly()
    {
        Assert.True(TerminalPolicy.IsDestructive("del /q *.*"));
        Assert.True(TerminalPolicy.IsDestructive("rm -rf /"));
        Assert.True(TerminalPolicy.IsDestructive("format C:"));
        Assert.True(TerminalPolicy.IsDestructive("shutdown /s /t 0"));
        Assert.True(TerminalPolicy.IsDestructive("rmdir /s build"));
        Assert.True(TerminalPolicy.IsDestructive("cmd /c del notes.txt"));
        Assert.False(TerminalPolicy.IsDestructive("echo hello"));
        Assert.False(TerminalPolicy.IsDestructive("dir"));
        Assert.False(TerminalPolicy.IsDestructive("delta"));
        Assert.False(TerminalPolicy.IsDestructive("formatted"));
    }

    [Fact]
    public void TerminalFrameIsTypeTwelveAndFileFramesStayPut()
    {
        var frame = FrameCodec.Encode(MessageType.TerminalData, Encoding.UTF8.GetBytes("{}"));
        Assert.Equal(12, frame[5]);
        Assert.Equal(3, FrameCodec.Encode(MessageType.Offer, Encoding.UTF8.GetBytes("{}"))[5]);
        Assert.Equal(10, FrameCodec.Encode(MessageType.Service, Encoding.UTF8.GetBytes("{}"))[5]);
        Assert.Equal(11, FrameCodec.Encode(MessageType.ServiceResult, Encoding.UTF8.GetBytes("{}"))[5]);
    }

    [Fact]
    public void SignatureUsesThePairingKeyAndRejectsATamperedCommand()
    {
        using var certificate = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var id = "11111111-1111-1111-1111-111111111111";
        var command = "echo hi";
        var signature = TerminalAuth.Sign(certificate, id, command, false);
        var spki = certificate.PublicKey.ExportSubjectPublicKeyInfo();
        Assert.True(TerminalAuth.Verify(spki, id, command, false, signature));
        Assert.False(TerminalAuth.Verify(spki, id, "echo no", false, signature));
        Assert.False(TerminalAuth.Verify(spki, id, command, true, signature));
        Assert.False(TerminalAuth.Verify(spki, id, command, false, null));
    }

    [Fact]
    public async Task OffByDefaultAndAllowlistAndConfirmGateTheProcess()
    {
        using var certificate = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var peer = PeerOf(certificate);
        var log = new TerminalLog(Path.Combine(Directory.CreateTempSubdirectory("ss-term").FullName, "terminal.log"));
        var process = new FakeProcess();
        var settings = new TerminalSettings { Enabled = false, AllowlistOnly = true };

        var off = await TerminalHost.RunAsync(Request(certificate, "echo hi", false), peer, settings, log, process, (_, _) => Task.CompletedTask, CancellationToken.None);
        Assert.Equal(TerminalPolicy.Disabled, off.Code);
        Assert.Equal(0, process.Calls);

        settings.Enabled = true;
        var blocked = await TerminalHost.RunAsync(Request(certificate, "echo hi", false), peer, settings, log, process, (_, _) => Task.CompletedTask, CancellationToken.None);
        Assert.Equal(TerminalPolicy.NotAllowed, blocked.Code);
        Assert.Equal(0, process.Calls);

        var dangerous = await TerminalHost.RunAsync(Request(certificate, "del /q temp", false), peer, settings, log, process, (_, _) => Task.CompletedTask, CancellationToken.None);
        Assert.Equal(TerminalPolicy.NeedsConfirm, dangerous.Code);
        Assert.Equal(0, process.Calls);

        settings.Allowlist = ["echo hi"];
        process.Chunks.Add(("out", "hi\n"));
        var chunks = new List<string>();
        var ran = await TerminalHost.RunAsync(
            Request(certificate, "echo hi", false),
            peer,
            settings,
            log,
            process,
            (chunk, _) =>
            {
                chunks.Add(chunk.Text);
                return Task.CompletedTask;
            },
            CancellationToken.None);
        Assert.True(ran.Ok);
        Assert.Equal(["hi\n"], chunks);
        Assert.Equal(1, process.Calls);
        var written = await File.ReadAllTextAsync(log.Path);
        Assert.Contains("echo hi", written);
        Assert.Contains("finished", written);
        Assert.Contains("terminal_off", written);
        Assert.Contains("not_allowed", written);
        Assert.Contains("needs_confirm", written);
        Assert.Contains("del /q temp", written);
    }

    [Fact]
    public async Task UnsignedCommandIsLoggedWithoutTheCommandText()
    {
        using var certificate = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var directory = Directory.CreateTempSubdirectory("ss-term").FullName;
        var log = new TerminalLog(Path.Combine(directory, "terminal.log"));
        var process = new FakeProcess();
        using var document = JsonDocument.Parse("{\"command\":\"secret-command\",\"confirmed\":false,\"sig\":\"aa\"}");
        var request = new ServiceRequest(Guid.NewGuid().ToString("D"), CompanionServices.Terminal, "run", document.RootElement.Clone());
        var result = await TerminalHost.RunAsync(
            request,
            PeerOf(certificate),
            new TerminalSettings { Enabled = true, AllowlistOnly = false },
            log,
            process,
            (_, _) => Task.CompletedTask,
            CancellationToken.None);
        Assert.Equal(TerminalPolicy.Unauthenticated, result.Code);
        Assert.Equal(0, process.Calls);
        var written = await File.ReadAllTextAsync(log.Path);
        Assert.Contains("unauthenticated", written);
        Assert.DoesNotContain("secret-command", written);
    }

    [Fact]
    public async Task UnknownPeerCannotRunACommand()
    {
        using var certificate = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var log = new TerminalLog(Path.Combine(Directory.CreateTempSubdirectory("ss-term").FullName, "terminal.log"));
        var result = await TerminalHost.RunAsync(
            Request(certificate, "echo hi", false),
            peer: null,
            new TerminalSettings { Enabled = true, AllowlistOnly = false },
            log,
            new FakeProcess(),
            (_, _) => Task.CompletedTask,
            CancellationToken.None);
        Assert.Equal(ErrorCodes.UnknownPeer, result.Code);
    }

    [Fact]
    public void SharedVectorsVerifyOnBothSidesOfThePairingKey()
    {
        AssertVerified("terminal-signature.json");
        AssertVerified("terminal-signature-phone.json");
    }

    private static void AssertVerified(string name)
    {
        using var document = JsonDocument.Parse(File.ReadAllText(Path.Combine(Repo.Root(), "testdata", name)));
        var root = document.RootElement;
        Assert.True(TerminalAuth.Verify(
            Base64Url.Decode(root.GetProperty("spki").GetString()!),
            root.GetProperty("id").GetString()!,
            root.GetProperty("command").GetString()!,
            root.GetProperty("confirmed").GetBoolean(),
            root.GetProperty("sig").GetString()));
    }

    private static ServiceRequest Request(X509Certificate2 certificate, string command, bool confirmed)
    {
        var id = Guid.NewGuid().ToString("D");
        var signature = TerminalAuth.Sign(certificate, id, command, confirmed);
        var json = JsonSerializer.Serialize(new { command, confirmed, sig = signature });
        using var document = JsonDocument.Parse(json);
        return new ServiceRequest(id, CompanionServices.Terminal, "run", document.RootElement.Clone());
    }

    private static PeerRecord PeerOf(X509Certificate2 certificate)
    {
        var id = Guid.Parse(certificate.GetNameInfo(X509NameType.SimpleName, false));
        return new PeerRecord(id, "Phone", Pin.Fingerprint(certificate), Base64Url.Encode(certificate.PublicKey.ExportSubjectPublicKeyInfo()), null, ProtocolLimits.DefaultTcpPort, DateTimeOffset.UtcNow);
    }

    private sealed class FakeProcess : ITerminalProcess
    {
        public int Calls { get; private set; }
        public List<(string Stream, string Text)> Chunks { get; } = [];
        public int Exit { get; set; }

        public async Task<int> RunAsync(string command, Func<string, string, CancellationToken, Task> onChunk, CancellationToken ct)
        {
            Calls++;
            foreach (var (stream, text) in Chunks)
            {
                await onChunk(stream, text, ct);
            }

            return Exit;
        }
    }
}

[Collection("net")]
public sealed class TerminalStreamTests
{
    [Fact]
    public async Task PairedPhoneStreamsOutputOverTheExistingSocket()
    {
        using var serverCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        using var clientCert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var server = IdentityOf(serverCert, "Windows PC");
        var client = IdentityOf(clientCert, "Phone");
        var directory = Directory.CreateTempSubdirectory("ss-term-link").FullName;
        var session = new TerminalSession(Path.Combine(directory, "terminal.log"), new ScriptedProcess("from-pc\n"));
        session.Update(enabled: true, allowlistOnly: false, []);
        var clientPeer = new PeerRecord(client.DeviceId, client.Name, client.Fingerprint, Base64Url.Encode(client.Spki), null, ProtocolLimits.DefaultTcpPort, DateTimeOffset.UtcNow);

        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var serverTask = Task.Run(async () =>
        {
            var tcp = await listener.AcceptTcpClientAsync();
            await using var tls = await TlsChannel.AcceptAsync(tcp, server.Certificate, client.Fingerprint, allowNew: false, CancellationToken.None);
            return await TransferLink.ReceiveOneAsync(
                tls.Stream,
                server,
                clientPeer,
                tls.RemoteFingerprint,
                allowNewPeer: false,
                confirmPeer: null,
                confirmOffer: (_, _) => Task.FromResult(true),
                openFile: offer => FileReceiveSink.Create(directory, offer.Name),
                onText: _ => Task.CompletedTask,
                progress: null,
                CancellationToken.None,
                onService: null,
                onHello: null,
                onTerminal: (request, emit, token) => session.HandleAsync(request, clientPeer, emit, token));
        });

        var id = Guid.NewGuid().ToString("D");
        var command = "echo streamed";
        var signature = TerminalAuth.Sign(client.Certificate, id, command, false);
        using var body = JsonDocument.Parse(JsonSerializer.Serialize(new { command, confirmed = false, sig = signature }));
        var serverPeer = new PeerRecord(server.DeviceId, server.Name, server.Fingerprint, Base64Url.Encode(server.Spki), "127.0.0.1", port, DateTimeOffset.UtcNow);
        await using var clientTls = await TlsChannel.ConnectAsync(new IPEndPoint(IPAddress.Loopback, port), client.Certificate, server.Fingerprint, CancellationToken.None);
        var seen = new List<string>();
        var result = await TransferLink.CallStreamingAsync(
            clientTls.Stream,
            client,
            serverPeer,
            clientTls.RemoteFingerprint,
            id,
            CompanionServices.Terminal,
            "run",
            body.RootElement.Clone(),
            (chunk, _) =>
            {
                seen.Add(chunk.Stream + ":" + chunk.Text);
                return Task.CompletedTask;
            },
            CancellationToken.None);
        var received = await serverTask;

        Assert.True(result.Ok);
        Assert.Equal(["out:from-pc\n"], seen);
        Assert.Equal(TransferState.Succeeded, received.State);
        var log = await File.ReadAllTextAsync(session.LogPath);
        Assert.Contains(command, log);
        Assert.Contains("finished", log);
        server.Dispose();
        client.Dispose();
    }

    private static DeviceIdentity IdentityOf(X509Certificate2 cert, string name) =>
        new()
        {
            DeviceId = Guid.Parse(cert.GetNameInfo(X509NameType.SimpleName, false)),
            Name = name,
            Certificate = cert
        };

    private sealed class ScriptedProcess(string text) : ITerminalProcess
    {
        public Task<int> RunAsync(string command, Func<string, string, CancellationToken, Task> onChunk, CancellationToken ct)
        {
            return Run(onChunk, ct);

            async Task<int> Run(Func<string, string, CancellationToken, Task> emit, CancellationToken token)
            {
                await emit("out", text, token);
                return 0;
            }
        }
    }
}
