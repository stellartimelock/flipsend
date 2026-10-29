using System.Security.Cryptography;
using System.Text;
using System.Security.Cryptography.X509Certificates;
using StellarSend.Core.Discovery;
using StellarSend.Core.Files;
using StellarSend.Core.Pairing;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;
using StellarSend.Core.Shell;
using StellarSend.Core.Transfer;
using StellarSend.Core.Usb;

namespace StellarSend.Core.Tests;

public sealed class PairingAndSafetyTests
{
    [Fact]
    public void PairingFixtureRoundTripsAndHasNoPrivateKey()
    {
        var json = File.ReadAllText(Path.Combine(Repo.Root(), "testdata", "pairing.json"));
        Assert.DoesNotContain("PRIVATE", json, StringComparison.OrdinalIgnoreCase);
        using var doc = System.Text.Json.JsonDocument.Parse(json);
        var qr = doc.RootElement.GetProperty("qr").GetString()!;
        var advert = PairingCodec.Parse(qr);
        Assert.Equal(Guid.Parse("11111111-1111-1111-1111-111111111111"), advert.DeviceId);
        Assert.Equal("Phone", advert.Name);
        Assert.Equal("192.168.43.1", advert.Host);
        Assert.Equal(53321, advert.Port);
        Assert.Equal(doc.RootElement.GetProperty("fp").GetString(), advert.Fingerprint);
        var again = PairingCodec.ToQr(advert);
        Assert.DoesNotContain("private", again, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(advert.Fingerprint, PairingCodec.Parse(again).Fingerprint);
    }

    [Fact]
    public void QrFromIdentityExportsOnlyThePublicKey()
    {
        using var cert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var identity = new DeviceIdentity { DeviceId = Guid.Parse(cert.GetNameInfo(X509NameType.SimpleName, false)), Name = "Windows PC", Certificate = cert };
        var privateKey = cert.GetECDsaPrivateKey()!.ExportPkcs8PrivateKey();
        var qr = PairingCodec.ToQr(identity, "192.168.1.20", 53321);
        Assert.DoesNotContain(Convert.ToBase64String(privateKey), qr, StringComparison.Ordinal);
        Assert.DoesNotContain("PRIVATE", qr, StringComparison.OrdinalIgnoreCase);
        var parsed = PairingCodec.Parse(qr);
        Assert.Equal(identity.Fingerprint, parsed.Fingerprint);
        CryptographicOperations.ZeroMemory(privateKey);
    }

    [Fact]
    public void QrCarriesEveryCandidateAddressInRankOrder()
    {
        using var cert = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var identity = new DeviceIdentity { DeviceId = Guid.Parse(cert.GetNameInfo(X509NameType.SimpleName, false)), Name = "Windows PC", Certificate = cert };
        var qr = PairingCodec.ToQr(identity, ["10.201.100.159", "10.8.0.2", "169.254.1.1", "8.8.8.8"], 53321);
        var parsed = PairingCodec.Parse(qr);
        Assert.Equal("10.201.100.159", parsed.Host);
        Assert.Equal(["10.201.100.159"], parsed.Candidates());
        Assert.Contains("ips=", qr, StringComparison.Ordinal);
        Assert.DoesNotContain("169.254.1.1", qr, StringComparison.Ordinal);
        Assert.DoesNotContain("8.8.8.8", qr, StringComparison.Ordinal);
    }

    [Fact]
    public void RelayRoomHidesDeviceIdsAndMatchesAKnownHash()
    {
        var a = Guid.Parse("22222222-2222-2222-2222-222222222222");
        var b = Guid.Parse("11111111-1111-1111-1111-111111111111");
        var room = RelayRoom.Paired(a, b);
        Assert.Equal(RelayRoom.Paired(b, a), room);
        Assert.Equal("8d691f26b6b708eb4b0a058b4ab26c3550f9b2006086aba986f692df67957a74", room);
        Assert.DoesNotContain(a.ToString("D"), room, StringComparison.Ordinal);
        Assert.Equal("1b0b8e985e76c507f6a56dd97199f4a92ea67e8b78479e84df7e13d23e96e186", RelayRoom.Pairing(b));
        Assert.True(RelayAddress.TryParse("https://relay.stellartimelock.com/v1", out var url));
        Assert.Equal("wss", url.Scheme);
        Assert.Equal(443, url.Port);
        Assert.False(RelayAddress.TryParse("http://8.8.8.8/v1", out _));
    }

    [Fact]
    public async Task RelayForwardsOpaqueBytesAndDoesNotNeedThePlaintext()
    {
        var hub = new RelayHub();
        var room = RelayRoom.Paired(Guid.Parse("11111111-1111-1111-1111-111111111111"), Guid.Parse("22222222-2222-2222-2222-222222222222"));
        var listenTask = hub.JoinAsync(room, RelayRole.Listen, CancellationToken.None);
        var dialTask = hub.JoinAsync(room, RelayRole.Dial, CancellationToken.None);
        var listen = await listenTask;
        var dial = await dialTask;
        var payload = Encoding.UTF8.GetBytes("ciphertext-not-a-file");
        await dial.Stream.WriteAsync(payload);
        var buffer = new byte[payload.Length];
        var read = await listen.Stream.ReadAsync(buffer);
        Assert.Equal(payload.Length, read);
        Assert.Equal(payload, buffer);
        await listen.DisposeAsync();
        await dial.DisposeAsync();
    }

    [Fact]
    public async Task RelayRefusesToPairTheSameDeviceWithItself()
    {
        var hub = new RelayHub();
        var room = RelayRoom.Pairing(Guid.NewGuid());
        var device = Guid.NewGuid().ToString("D");
        var listenTask = hub.JoinAsync(room, RelayRole.Listen, RelaySeat.Unenforced, device, CancellationToken.None);
        var dialTask = hub.JoinAsync(room, RelayRole.Dial, RelaySeat.Unenforced, device, CancellationToken.None);
        await Assert.ThrowsAsync<SelfDialException>(() => dialTask);
        Assert.False(listenTask.IsCompleted);
        var other = Guid.NewGuid().ToString("D");
        var peer = await hub.JoinAsync(room, RelayRole.Dial, RelaySeat.Unenforced, other, CancellationToken.None);
        var listen = await listenTask;
        await listen.DisposeAsync();
        await peer.DisposeAsync();
    }

    [Fact]
    public void PairingRejectsSecretsAndPublicAddresses()
    {
        var json = File.ReadAllText(Path.Combine(Repo.Root(), "testdata", "pairing.json"));
        using var doc = System.Text.Json.JsonDocument.Parse(json);
        var qr = doc.RootElement.GetProperty("qr").GetString()!;
        Assert.Throws<ProtocolException>(() => PairingCodec.Parse(qr + "&secret=nope"));
        Assert.Throws<ProtocolException>(() => PairingCodec.Parse(qr.Replace("192.168.43.1", "8.8.8.8")));
        Assert.Throws<ProtocolException>(() => PairingCodec.Parse(qr.Replace("pk=", "pk=UFJJVkFURSBLRVk=")));
    }

    [Fact]
    public void BareCertificateUpgradeKeepsThePairingPin()
    {
        using var ecdsa = System.Security.Cryptography.ECDsa.Create(System.Security.Cryptography.ECCurve.NamedCurves.nistP256);
        var id = Guid.NewGuid();
        var request = new CertificateRequest($"CN={id:D}", ecdsa, HashAlgorithmName.SHA256);
        using var bare = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(5));
        var pfx = bare.Export(X509ContentType.Pfx);
        using var loaded = X509CertificateLoader.LoadPkcs12(pfx, ReadOnlySpan<char>.Empty, X509KeyStorageFlags.Exportable);
        CryptographicOperations.ZeroMemory(pfx);
        Assert.False(CertificateFactory.HasDeviceUsages(loaded));
        var pin = Pin.Fingerprint(loaded);
        using var upgraded = CertificateFactory.EnsureDeviceUsages(loaded);
        Assert.True(CertificateFactory.HasDeviceUsages(upgraded));
        Assert.True(Pin.FixedEquals(pin, Pin.Fingerprint(upgraded)));
        using var minted = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        Assert.True(CertificateFactory.HasDeviceUsages(minted));
    }

    [Fact]
    public void PinRejectsADifferentCertificate()
    {
        using var first = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        using var second = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var fingerprint = Pin.Fingerprint(first);
        Assert.True(Pin.FixedEquals(fingerprint, Pin.Fingerprint(first.PublicKey.ExportSubjectPublicKeyInfo())));
        Assert.False(Pin.FixedEquals(fingerprint, Pin.Fingerprint(second)));
        Assert.False(Pin.FixedEquals(fingerprint, "zzzz"));
        Assert.False(Pin.FixedEquals(null, null));
    }

    [Fact]
    public void IdentityStoreRoundTripsThePrivateKeyOnlyThroughTheProtector()
    {
        var directory = Directory.CreateTempSubdirectory("ss-id");
        var path = Path.Combine(directory.FullName, "identity.bin");
        var store = new IdentityStore(new TestProtector());
        var created = store.LoadOrCreate(path, "Windows PC");
        var fingerprint = created.Fingerprint;
        var pfx = created.Certificate.Export(X509ContentType.Pfx);
        created.Dispose();
        var loaded = store.LoadOrCreate(path, "Windows PC");
        Assert.Equal(fingerprint, loaded.Fingerprint);
        var stored = File.ReadAllBytes(path);
        Assert.NotEqual(pfx, stored);
        loaded.Dispose();
        CryptographicOperations.ZeroMemory(pfx);
    }

    [Theory]
    [InlineData(@"..\..\evil.txt", "evil.txt")]
    [InlineData("foo/../../etc/passwd", "passwd")]
    [InlineData(@"C:\Windows\System32\cmd.exe", "cmd.exe")]
    [InlineData("CON.txt", "_CON.txt")]
    [InlineData("..", "file")]
    [InlineData("hello\0.txt", "hello_.txt")]
    [InlineData("report.pdf", "report.pdf")]
    public void FilenamesAreSanitized(string raw, string expected) =>
        Assert.Equal(expected, FileNames.Sanitize(raw));

    [Fact]
    public void DestinationStaysInsideTheInbox()
    {
        var directory = Directory.CreateTempSubdirectory("ss-inbox");
        var path = FileNames.Allocate(directory.FullName, @"..\outside.txt");
        Assert.StartsWith(directory.FullName, path, StringComparison.Ordinal);
        Assert.Equal("outside.txt", Path.GetFileName(path));
        File.WriteAllText(path, "x");
        var second = FileNames.Allocate(directory.FullName, "outside.txt");
        Assert.Equal("outside (1).txt", Path.GetFileName(second));
    }

    [Fact]
    public async Task FortressBotResponseKeepsItsExactNameAndOverwrites()
    {
        var directory = Directory.CreateTempSubdirectory("ss-fortress-inbox");
        var first = FileNames.Exact(directory.FullName, "fortress-bot-response.json");
        await File.WriteAllTextAsync(first, "one");
        var again = FileNames.Exact(directory.FullName, "fortress-bot-response.json");
        Assert.Equal("fortress-bot-response.json", Path.GetFileName(again));
        Assert.Equal(first, again);
        Assert.True(FileNames.IsFortressBotResponse("Fortress-Bot-Response.JSON"));
        Assert.False(FileNames.IsFortressBotResponse("notes.json"));
        var sink = FileReceiveSink.Create(directory.FullName, "fortress-bot-response.json");
        await sink.WriteAsync(System.Text.Encoding.UTF8.GetBytes("two"), CancellationToken.None);
        await sink.CommitAsync(CancellationToken.None);
        Assert.Equal("two", await File.ReadAllTextAsync(first));
        Assert.Equal("fortress-bot-response.json", Path.GetFileName(first));
    }

    [Theory]
    [InlineData("8.8.8.8", false)]
    [InlineData("192.168.1.20", true)]
    [InlineData("10.1.2.3", true)]
    [InlineData("172.16.5.5", true)]
    [InlineData("169.254.1.1", true)]
    [InlineData("127.0.0.1", false)]
    [InlineData("224.0.0.1", false)]
    public void OnlyLocalAddressesAreAllowed(string host, bool allowed) =>
        Assert.Equal(allowed, StellarSend.Core.Discovery.LocalAddressPolicy.IsAllowedHost(host, allowLoopback: false));

    [Fact]
    public void LogsRedactPairingMaterial()
    {
        Assert.Equal("[redacted]", RedactingFileLog.Redact("saw stellarsend://pair?pk=abc"));
        Assert.Equal("[redacted]", RedactingFileLog.Redact("BEGIN PRIVATE KEY"));
        Assert.Equal("Listening", RedactingFileLog.Redact("Listening"));
    }

    [Fact]
    public void ProgressTextIncludesRateAndRemainingTime()
    {
        var text = ProgressText.Format(1536, 4096, 1024, 2.2, TransferState.InProgress, null);
        Assert.Contains("1.5 KB", text, StringComparison.Ordinal);
        Assert.Contains("4 KB", text, StringComparison.Ordinal);
        Assert.Contains("1 KB/s", text, StringComparison.Ordinal);
        Assert.Contains("2 s left", text, StringComparison.Ordinal);
        Assert.Equal("Sent", ProgressText.Format(4, 4, null, null, TransferState.Succeeded, null));
        Assert.Equal("Cancelled", ProgressText.Format(1, 4, null, null, TransferState.Cancelled, null));
    }

    [Fact]
    public void RetryOnlyWhenTheAttemptIsSafe()
    {
        Assert.True(RetryPolicy.ShouldRetry(false, false, 1, true));
        Assert.True(RetryPolicy.ShouldRetry(false, false, 4, true));
        Assert.False(RetryPolicy.ShouldRetry(false, false, RetryPolicy.MaxAttempts, true));
        Assert.False(RetryPolicy.ShouldRetry(true, false, 1, true));
        Assert.False(RetryPolicy.ShouldRetry(false, true, 1, true));
        Assert.False(RetryPolicy.ShouldRetry(false, false, 1, false));
        Assert.Equal(TimeSpan.FromMilliseconds(400), RetryPolicy.Delay(1));
        Assert.Equal(TimeSpan.FromMilliseconds(800), RetryPolicy.Delay(2));
    }

    [Fact]
    public void ShellRegistrationIsRemovable()
    {
        var hive = new MemoryRegistry();
        ShellRegistration.Register(hive, @"C:\Apps\StellarSend.exe");
        Assert.Equal("Send to phone", hive.Get(ShellRegistration.Key, ""));
        Assert.Equal("\"C:\\Apps\\StellarSend.exe\" --send-file \"%1\"", hive.Get(ShellRegistration.CommandKey, ""));
        ShellRegistration.Unregister(hive);
        Assert.False(hive.Exists(ShellRegistration.Key));
        StartupRegistration.Enable(hive, @"C:\Apps\StellarSend.exe");
        StartupRegistration.Disable(hive);
        Assert.False(hive.Exists(StartupRegistration.RunKey));
        Assert.Throws<ArgumentException>(() => ShellRegistration.Register(hive, @"C:\bad\quote"".exe"));
    }

    [Fact]
    public void SendQueueDrainsPaths()
    {
        var directory = Directory.CreateTempSubdirectory("ss-queue");
        SendQueue.Enqueue(directory.FullName, @"C:\docs\a.txt");
        var drained = SendQueue.Drain(directory.FullName);
        Assert.Equal([@"C:\docs\a.txt"], drained);
        Assert.Empty(Directory.GetFiles(directory.FullName, "*.path"));
        Assert.Single(Directory.GetFiles(directory.FullName, "*.ack"));
    }

    [Fact]
    public void ASecondLaunchExitsWhenTheRunningInstanceAcceptsTheFile()
    {
        var directory = Directory.CreateTempSubdirectory("ss-handoff-ok");
        var log = new MemoryLog();
        var clock = new JumpClock();
        var decision = SendQueue.Offer(directory.FullName, @"C:\docs\a.txt", TimeSpan.FromSeconds(5), log, clock, delay =>
        {
            if (Directory.GetFiles(directory.FullName, "*.path").Length > 0)
            {
                SendQueue.Drain(directory.FullName, log);
            }

            clock.Advance(delay);
        });

        Assert.Equal(HandoffDecision.Accepted, decision);
        Assert.Contains(log.Lines, line => line.Contains("Handing a.txt", StringComparison.Ordinal));
        Assert.Contains(log.Lines, line => line.Contains("accepted a.txt", StringComparison.Ordinal));
        Assert.Empty(Directory.GetFiles(directory.FullName));
    }

    [Fact]
    public void ASecondLaunchNeverStartsASecondFlipSendWhenTheRunningInstanceDoesNotAcknowledge()
    {
        var directory = Directory.CreateTempSubdirectory("ss-handoff-timeout");
        var log = new MemoryLog();
        var clock = new JumpClock();
        var started = DateTime.UtcNow;
        var decision = SendQueue.Offer(directory.FullName, @"C:\docs\photo.png", TimeSpan.FromSeconds(5), log, clock, clock.Advance);
        Assert.Equal(HandoffDecision.NoAnswer, decision);
        Assert.True(DateTime.UtcNow - started < TimeSpan.FromSeconds(2));
        Assert.Contains(log.Lines, line => line.Contains("did not acknowledge photo.png within 5 seconds", StringComparison.Ordinal));
        Assert.Contains(log.Lines, line => line.Contains("Not starting a second FlipSend", StringComparison.Ordinal));
        Assert.Empty(Directory.GetFiles(directory.FullName));
    }

    [Fact]
    public void ASecondInstanceDoesNotWaitForTheFirstToExit()
    {
        var name = "StellarSend.Test." + Guid.NewGuid().ToString("N");
        var log = new MemoryLog();
        using var first = SingleInstanceClaim.Acquire(name, log);
        Assert.True(first.IsFirst);
        var started = DateTime.UtcNow;
        using var second = SingleInstanceClaim.Acquire(name, log);
        Assert.False(second.IsFirst);
        Assert.True(DateTime.UtcNow - started < TimeSpan.FromSeconds(2));
        Assert.Contains(log.Lines, line => line.Contains("already running", StringComparison.Ordinal));
    }

    [Fact]
    public async Task AdbRequiresConfirmationAndParsesDevices()
    {
        var output = """
            List of devices attached
            emulator-5554          device product:sdk model:emu device:emu
            ABC123                 unauthorized
            bad serial             device
            """;
        var devices = AdbTransfer.ParseDevices(output);
        Assert.Equal(2, devices.Count);
        Assert.Equal("device", devices[0].State);
        Assert.Equal("unauthorized", devices[1].State);
        Assert.False(AdbTransfer.IsSafeRemote("/sdcard/../etc/passwd"));
        Assert.True(AdbTransfer.IsSafeRemote("/sdcard/Download/StellarSend/note.txt"));

        var runner = new FakeRunner();
        var adb = new AdbTransfer(runner);
        await Assert.ThrowsAsync<ConfirmationRequiredException>(() =>
            adb.PushAsync("/usr/bin/adb", "emulator-5554", "/tmp/a.txt", userConfirmed: false, null, CancellationToken.None));
        Assert.Empty(runner.Calls);
        await Assert.ThrowsAsync<ArgumentException>(() =>
            adb.PushAsync("/tmp/not-adb", "emulator-5554", "/tmp/a.txt", true, null, CancellationToken.None));
        await Assert.ThrowsAsync<ArgumentException>(() =>
            adb.PushAsync("/usr/bin/adb", "serial;rm", "/tmp/a.txt", true, null, CancellationToken.None));
    }

    [Fact]
    public void PairingFilesReplaceBothByTempRenameAndRollBackWhenPeerReplaceFails()
    {
        var directory = Directory.CreateTempSubdirectory("ss-commit");
        var identity = Path.Combine(directory.FullName, "identity.bin");
        var peer = Path.Combine(directory.FullName, "peer.bin");
        var original = new byte[] { 1, 2, 3, 4, 5 };
        File.WriteAllBytes(identity, original);
        File.WriteAllBytes(peer, new byte[] { 6, 7, 8, 9 });
        var nextIdentity = new byte[] { 9, 9, 9, 9, 9 };
        var nextPeer = new byte[] { 4, 4, 4, 4 };
        PairingCommit.CommitBytes(identity, nextIdentity, peer, nextPeer);
        Assert.Equal(nextIdentity, File.ReadAllBytes(identity));
        Assert.Equal(nextPeer, File.ReadAllBytes(peer));
        Assert.False(File.Exists(identity + ".pairing.tmp"));
        Assert.False(File.Exists(peer + ".pairing.tmp"));

        Directory.CreateDirectory(Path.Combine(directory.FullName, "blocked"));
        var blockedPeer = Path.Combine(directory.FullName, "blocked");
        // Windows reports a directory in the way as UnauthorizedAccessException, Linux as IOException.
        var blocked = Assert.ThrowsAny<Exception>(() => PairingCommit.CommitBytes(identity, new byte[] { 3, 3, 3, 3, 3 }, blockedPeer, new byte[] { 1, 1, 1, 1 }));
        Assert.True(blocked is IOException or UnauthorizedAccessException, blocked.GetType().Name);
        Assert.Equal(nextIdentity, File.ReadAllBytes(identity));
        Assert.False(File.Exists(identity + ".pairing.tmp"));
        Assert.True(Directory.Exists(blockedPeer));
    }

    [Fact]
    public void BeaconRejectsSecretsAndRoundTrips()
    {
        var bytes = BeaconCodec.Encode(Guid.Parse("11111111-1111-1111-1111-111111111111"), 53321, "Desk");
        Assert.True(BeaconCodec.TryParse(bytes, out var body));
        Assert.Equal("stellarsend", body!.App);
        Assert.False(BeaconCodec.TryParse("{\"v\":1,\"app\":\"stellarsend\",\"id\":\"x\",\"port\":1,\"name\":\"n\",\"pk\":\"secret\"}"u8, out _));
    }

    private sealed class MemoryRegistry : IRegistry
    {
        private readonly Dictionary<string, Dictionary<string, string>> _keys = new(StringComparer.OrdinalIgnoreCase);

        public void SetString(string subKey, string name, string value)
        {
            if (!_keys.TryGetValue(subKey, out var values))
            {
                values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                _keys[subKey] = values;
            }

            values[name] = value;
        }

        public bool TryGetString(string subKey, string name, out string? value)
        {
            value = null;
            return _keys.TryGetValue(subKey, out var values) && values.TryGetValue(name, out value);
        }

        public void DeleteValue(string subKey, string name)
        {
            if (_keys.TryGetValue(subKey, out var values))
            {
                values.Remove(name);
                if (values.Count == 0)
                {
                    _keys.Remove(subKey);
                }
            }
        }

        public void DeleteTree(string subKey)
        {
            var doomed = _keys.Keys.Where(k => k.Equals(subKey, StringComparison.OrdinalIgnoreCase) || k.StartsWith(subKey + "\\", StringComparison.OrdinalIgnoreCase)).ToList();
            foreach (var key in doomed)
            {
                _keys.Remove(key);
            }
        }

        public string Get(string subKey, string name) => _keys[subKey][name];
        public bool Exists(string subKey) => _keys.ContainsKey(subKey);
    }

    private sealed class MemoryLog : ILog
    {
        public List<string> Lines { get; } = [];
        public void Info(string message) => Lines.Add(message);
        public void Warn(string message) => Lines.Add(message);
        public void Error(string message) => Lines.Add(message);
    }

    private sealed class JumpClock : TimeProvider
    {
        private DateTimeOffset _now = DateTimeOffset.UnixEpoch;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan by) => _now += by;
    }

    private sealed class FakeRunner : IProcessRunner
    {
        public List<(string File, string[] Args)> Calls { get; } = [];

        public Task<ProcessResult> RunAsync(string fileName, IReadOnlyList<string> args, CancellationToken ct)
        {
            Calls.Add((fileName, args.ToArray()));
            return Task.FromResult(new ProcessResult(0, "", ""));
        }
    }
}
