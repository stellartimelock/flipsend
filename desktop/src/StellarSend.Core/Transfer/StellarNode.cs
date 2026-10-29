using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using StellarSend.Core.Companion;
using StellarSend.Core.Terminal;
using StellarSend.Core.Discovery;
using StellarSend.Core.Files;
using StellarSend.Core.Pairing;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;

namespace StellarSend.Core.Transfer;

public sealed class StellarNodeOptions
{
    public required string DataDirectory { get; init; }
    public string DisplayName { get; set; } = "Windows PC";
    public int Port { get; set; } = ProtocolLimits.DefaultTcpPort;
    public string? ManualHost { get; set; }
    public string? InboxDirectory { get; set; }
    public string? RelayUrl { get; set; }
    public bool AllowLoopback { get; init; }
}

public interface IConsent
{
    Task<bool> AcceptPairingAsync(string remoteName, string fingerprint, CancellationToken ct);
    Task<bool> AcceptIncomingAsync(OfferBody offer, string remoteName, CancellationToken ct);
    Task<RemoteDesktopConsent> AcceptRemoteControlAsync(string fromName, CancellationToken ct);
}

public sealed class StellarNode : IAsyncDisposable
{
    private volatile bool _phoneOffline;
    private volatile bool _rejected;

    /// <summary>Relay up, phone never joined. That is an offline phone, never a broken pairing.</summary>
    private string PhoneMissingNow()
    {
        if (!_phoneOffline)
        {
            _log.Info("Phone is offline: the relay is up but the phone did not join the room. Pairing unchanged.");
        }

        _phoneOffline = true;
        return RelayWake.PhoneOffline;
    }

    private void PhoneBack()
    {
        if (_phoneOffline)
        {
            _log.Info("Phone is back.");
        }

        _phoneOffline = false;
    }

    /// <summary>The last send found the relay up but the phone not in the room.</summary>
    public bool PhoneOffline => _phoneOffline;

    /// <summary>
    /// True only when the phone actually rejected this PC's key or pin in a handshake
    /// (key_changed after retries, or unknown_peer). A silent phone never sets this.
    /// </summary>
    public bool RepairNeeded => _rejected;

    private readonly StellarNodeOptions _options;
    private readonly IConsent _consent;
    private readonly ILog _log;
    private readonly IdentityStore _identities;
    private readonly PeerStore _peers;
    private readonly string _identityPath;
    private readonly string _peerPath;
    private string _inbox;
    private readonly SemaphoreSlim _sendGate = new(1, 1);
    private TcpListener? _listener;
    private IDisposable? _bluetooth;
    private CancellationTokenSource? _listenCts;
    private Task? _listenTask;
    private DeviceIdentity _identity;
    private PeerRecord? _peer;
    private DateTimeOffset _pairingUntil;
    private CancellationTokenSource? _announceCts;
    private CancellationTokenSource? _relayCts;
    private Task? _relayTask;
    private int _relayGeneration;
    private readonly RelayDialGate _relayDial = new();
    private readonly System.Collections.Concurrent.ConcurrentDictionary<TcpClient, byte> _live = new();
    private IReadOnlyCollection<string>? _lanSnapshot;
    private int _hopGen;
    private int _lanEvents;
    private readonly object _repairGate = new();
    private RepairSession? _repair;
    private DateTimeOffset? _lastPinOk;
    private string _attemptLink = TransferPath.Direct;

    private sealed class RepairSession
    {
        public required DeviceIdentity Previous { get; init; }
        public PeerRecord? PreviousPeer { get; set; }
    }

    public StellarNode(StellarNodeOptions options, IKeyProtector protector, IConsent consent, ILog? log = null)
    {
        _options = options;
        _consent = consent;
        _log = log ?? NullLog.Instance;
        Directory.CreateDirectory(options.DataDirectory);
        _inbox = string.IsNullOrWhiteSpace(options.InboxDirectory)
            ? Path.Combine(options.DataDirectory, "inbox")
            : options.InboxDirectory;
        Directory.CreateDirectory(_inbox);
        _identityPath = Path.Combine(options.DataDirectory, "identity.bin");
        _peerPath = Path.Combine(options.DataDirectory, "peer.bin");
        _identities = new IdentityStore(protector);
        _peers = new PeerStore(protector);
        _identity = _identities.LoadOrCreate(_identityPath, options.DisplayName);
        _peer = _peers.Load(_peerPath);
        _lastPinOk = PinOkStore.Load(options.DataDirectory, _peer?.PairedAt);
        KeyChangedLog.Sink = _log;
        Terminal = new TerminalSession(Path.Combine(options.DataDirectory, "terminal.log"));
    }

    public DeviceIdentity Identity => _identity;
    public PeerRecord? Peer => _peer;
    public bool IsPaired => _peer is not null;
    public string? CurrentRelayUrl => _options.RelayUrl;
    public bool PairingWindowOpen => DateTimeOffset.UtcNow <= _pairingUntil;
    public bool RepairInProgress => IsRepairOpen();
    public int BoundPort { get; private set; }
    public string InboxDirectory => _inbox;
    public string? LastError { get; private set; }
    public string PathLabel { get; private set; } = "";
    public string? PendingTrustPin { get; private set; }
    public bool BluetoothRadioOn => BluetoothChannel.RadioOn();
    public bool DirectLinkUp { get; private set; }
    public bool DirectLinkMissed { get; private set; }
    public int NetworkHops { get; private set; }
    public CompanionHost? Companion { get; set; }
    public TerminalSession Terminal { get; }

    public event Action<TransferProgress>? Progress;
    public event Action<string>? TextReceived;
    public event Action<string>? FileReceived;
    public event Action? Changed;
    public event Action? PhoneScreenStarted;
    public event Action<byte[]>? PhoneScreenFrame;
    public event Action? PhoneScreenStopped;
    public event Action? RemoteChanged;
    public RemoteSession Remote { get; } = new();
    public bool RemoteEnabled { get; set; }
    public Func<byte[]?>? CaptureDesktop { get; set; }
    public int DesktopMaxWidth { get; private set; } = RemoteDesktop.WifiMaxWidth;
    public int DesktopQuality { get; private set; } = RemoteDesktop.WifiJpegQuality;
    public HashSet<string> AlwaysAllowDeviceIds { get; } = new(StringComparer.OrdinalIgnoreCase);
    public Action<string>? RememberAlwaysAllow { get; set; }
    public Action<RemoteInput>? ApplyDesktopInput { get; set; }

    public async Task StartAsync(CancellationToken ct)
    {
        if (_listener is not null)
        {
            return;
        }

        var listener = new TcpListener(IPAddress.Any, _options.Port);
        listener.Start();
        _listener = listener;
        BoundPort = ((IPEndPoint)listener.LocalEndpoint).Port;
        _listenCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _lanSnapshot = NetworkInfo.ListLanIpv4().ToArray();
        NetworkChange.NetworkAddressChanged += OnLanChanged;
        _listenTask = Task.Run(() => ListenLoopAsync(_listenCts.Token), CancellationToken.None);
        _bluetooth = BluetoothChannel.Listen(OnBluetooth, _listenCts.Token);
        RestartRelayWait();
        _log.Info("Listening for a paired device.");
        await Task.CompletedTask;
    }

    public string ExportQr()
    {
        var hosts = NetworkInfo.ListLanIpv4();
        return PairingCodec.ToQr(_identity, hosts, BoundPort == 0 ? _options.Port : BoundPort);
    }

    public void EnablePairingWindow(TimeSpan duration)
    {
        var already = DateTimeOffset.UtcNow <= _pairingUntil;
        _pairingUntil = DateTimeOffset.UtcNow.Add(duration);
        RestartPairingAnnounce();
        Changed?.Invoke();
        if (already)
        {
            _log.Info(IsRepairOpen() ? "Re-pair code is showing. Waiting for the phone to scan it." : "Pairing window already open.");
            return;
        }

        if (_peer is not null && !IsRepairOpen())
        {
            _log.Info("Pairing window opened. The paired connection stays put.");
            return;
        }

        RestartRelayWait();
        _log.Info("Pairing window opened.");
    }

    public async Task PairFromShortCodeAsync(string code, CancellationToken ct)
    {
        if (!ShortCode.LooksLike(code))
        {
            throw new ProtocolException("Enter the 8 character code shown on the other device.");
        }

        var hit = await LanDiscovery.ListenForCodeAsync(code, DiscoveryPlanner.TotalBudgetMs, ct).ConfigureAwait(false);
        if (hit is null)
        {
            throw new ProtocolException("Could not find a device showing that code. Scan the QR code if broadcast is blocked.");
        }

        var endpoint = new IPEndPoint(IPAddress.Parse(hit.Ip), hit.Port);
        await using var tls = await TlsChannel.ConnectPairingAsync(endpoint, _identity.Certificate, ct).ConfigureAwait(false);
        if (!ShortCode.Matches(code, tls.RemoteFingerprint))
        {
            throw new ProtocolException("That code does not match the device that answered.");
        }

        var remote = await TransferLink.HandshakeAsync(
            tls.Stream,
            _identity,
            tls.RemoteFingerprint,
            null,
            tls.RemoteFingerprint,
            allowNewPeer: false,
            confirmPeer: null,
            ct).ConfigureAwait(false);
        RememberPeer(new PeerRecord(
            Guid.Parse(remote.DeviceId),
            remote.Name,
            remote.Fingerprint,
            Base64Url.Encode(tls.RemoteSpki),
            hit.Ip,
            hit.Port,
            DateTimeOffset.UtcNow,
            PhonePlan: remote.Plan,
            PhoneQuotaKey: remote.QuotaKey,
            PhoneRelayUsed: remote.RelayUsed,
            PhoneRelayLimit: remote.RelayLimit), confirmRepair: true);
        _log.Info("Paired with the device that showed the short code.");
    }

    public void SetInbox(string? path)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            return;
        }

        Directory.CreateDirectory(path);
        _inbox = path;
        Changed?.Invoke();
    }

    public void RememberPeer(PeerRecord peer, bool confirmRepair = false)
    {
        var commit = false;
        var sameDevice = false;
        PeerRecord staged;
        lock (_repairGate)
        {
            var existing = _peer ?? _repair?.PreviousPeer;
            sameDevice = existing is not null && existing.DeviceId == peer.DeviceId;
            staged = PeerTrust.Merge(existing, peer);
            if (_repair is not null && (confirmRepair || !sameDevice))
            {
                commit = true;
            }
            else if (_repair is not null && sameDevice)
            {
                _peer = staged;
            }
        }

        if (commit)
        {
            CommitRepair(staged);
            return;
        }

        if (_repair is not null)
        {
            Changed?.Invoke();
            return;
        }

        if (!PeerTrust.ShouldWrite(_peer, staged))
        {
            _peer = staged;
            return;
        }

        SavePeer(staged, endPairing: !sameDevice);
    }

    public async Task PairFromQrAsync(string qr, CancellationToken ct)
    {
        var advert = PairingCodec.Parse(qr);
        var hosts = advert.Candidates().ToList();
        if (!string.IsNullOrWhiteSpace(_options.ManualHost))
        {
            hosts.Add(_options.ManualHost.Trim());
        }

        var peer = new PeerRecord(
            advert.DeviceId,
            advert.Name,
            advert.Fingerprint,
            advert.PublicKeySpkiBase64Url,
            hosts.FirstOrDefault(),
            advert.Port,
            DateTimeOffset.UtcNow,
            hosts);
        if (hosts.Count == 0 && string.IsNullOrWhiteSpace(_options.RelayUrl))
        {
            if (_repair is null)
            {
                RememberPeer(peer);
            }

            return;
        }

        await using var tls = await ConnectAdvertAsync(peer, hosts, ct).ConfigureAwait(false);
        var remote = await TransferLink.HandshakeAsync(
            tls.Stream,
            _identity,
            peer.Fingerprint,
            peer.DeviceId.ToString("D"),
            tls.RemoteFingerprint,
            allowNewPeer: false,
            confirmPeer: null,
            ct).ConfigureAwait(false);
        RememberPeer(peer, confirmRepair: true);
        NotePhonePlan(remote);
        _log.Info("Paired with the device that presented the QR code.");
    }

    public void Unpair()
    {
        DiscardRepair();
        PeerStore.Delete(_peerPath);
        _peer = null;
        _pairingUntil = DateTimeOffset.MinValue;
        StopPairingAnnounce();
        RestartRelayWait();
        Changed?.Invoke();
        _log.Info("Device unpaired.");
    }

    public void RotateIdentity()
    {
        CancelRepair();
        var staged = DeviceIdentity.Create(_options.DisplayName);
        lock (_repairGate)
        {
            _repair = new RepairSession { Previous = _identity, PreviousPeer = _peer };
            _identity = staged;
            _pairingUntil = DateTimeOffset.UtcNow.AddMinutes(2);
        }

        RestartPairingAnnounce();
        RestartRelayWait();
        Changed?.Invoke();
        _log.Info("Re-pair started. The current pairing stays until both devices confirm.");
    }

    public void CancelRepair()
    {
        if (!DiscardRepair())
        {
            return;
        }

        RestartRelayWait();
        Changed?.Invoke();
        _log.Info("Re-pair cancelled. The previous pairing is unchanged.");
    }

    public void SetManualHost(string? host)
    {
        _options.ManualHost = string.IsNullOrWhiteSpace(host) ? null : host.Trim();
    }

    public void SetRelayUrl(string? url)
    {
        _options.RelayUrl = string.IsNullOrWhiteSpace(url) ? null : url.Trim();
        RestartRelayWait();
    }

    public void SetDisplayName(string name)
    {
        _options.DisplayName = Validators.TrimName(name);
        _identity.Name = _options.DisplayName;
        Changed?.Invoke();
    }

    public Task<TransferResult> SendTextAsync(string text, CancellationToken ct) =>
        SendWithRetryAsync(_ => Task.FromResult(OutgoingPayload.FromText(text)), ct);

    public Task<TransferResult> SendFileAsync(string path, CancellationToken ct, int batchIndex = 0, int batchCount = 0) =>
        SendWithRetryAsync(async token =>
        {
            var payload = await OutgoingPayload.FromFileAsync(path, token).ConfigureAwait(false);
            payload.BatchIndex = batchIndex;
            payload.BatchCount = batchCount;
            return payload;
        }, ct);

    public Task<TransferResult> SendFolderAsync(string path, CancellationToken ct) =>
        SendWithRetryAsync(token => OutgoingPayload.FromFolderAsync(path, token), ct);

    public async ValueTask DisposeAsync()
    {
        NetworkChange.NetworkAddressChanged -= OnLanChanged;
        if (_listenCts is not null)
        {
            await _listenCts.CancelAsync().ConfigureAwait(false);
        }

        _bluetooth?.Dispose();
        _listener?.Stop();
        if (_listenTask is not null)
        {
            try
            {
                await _listenTask.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }

        StopPairingAnnounce();
        _relayCts?.Cancel();
        _listenCts?.Dispose();
        DeviceIdentity? previous;
        lock (_repairGate)
        {
            previous = _repair?.Previous;
            _repair = null;
        }

        if (previous is not null && !ReferenceEquals(previous, _identity))
        {
            previous.Dispose();
        }

        _identity.Dispose();
        _sendGate.Dispose();
    }

    private void SavePeer(PeerRecord peer, bool endPairing)
    {
        _peers.Save(_peerPath, peer);
        _peer = peer;
        if (endPairing)
        {
            _pairingUntil = DateTimeOffset.MinValue;
            StopPairingAnnounce();
        }

        RestartRelayWait();
        Changed?.Invoke();
    }

    private void CommitRepair(PeerRecord peer)
    {
        DeviceIdentity? previous = null;
        lock (_repairGate)
        {
            if (_repair is null)
            {
                return;
            }

            previous = _repair.Previous;
            PairingCommit.Commit(_identities, _identityPath, _identity, _peers, _peerPath, peer);
            _peer = peer;
            _pairingUntil = DateTimeOffset.MinValue;
            _repair = null;
        }

        if (previous is not null && !ReferenceEquals(previous, _identity))
        {
            previous.Dispose();
        }

        StopPairingAnnounce();
        RestartRelayWait();
        Changed?.Invoke();
        _log.Info("Re-pair confirmed.");
        _rejected = false;
    }

    private bool DiscardRepair()
    {
        DeviceIdentity? staged;
        lock (_repairGate)
        {
            if (_repair is null)
            {
                return false;
            }

            staged = _identity;
            _identity = _repair.Previous;
            _peer = _repair.PreviousPeer;
            _pairingUntil = DateTimeOffset.MinValue;
            _repair = null;
        }

        if (!ReferenceEquals(staged, _identity))
        {
            staged.Dispose();
        }

        StopPairingAnnounce();
        return true;
    }

    private bool IsRepairOpen()
    {
        lock (_repairGate)
        {
            return _repair is not null && DateTimeOffset.UtcNow <= _pairingUntil;
        }
    }

    private void SweepRepair()
    {
        var expired = false;
        lock (_repairGate)
        {
            expired = _repair is not null && DateTimeOffset.UtcNow > _pairingUntil;
        }

        if (expired)
        {
            CancelRepair();
        }
    }

    private async Task<TransferResult> SendWithRetryAsync(Func<CancellationToken, Task<OutgoingPayload>> open, CancellationToken ct)
    {
        if (_peer is null)
        {
            throw new PeerRejectedException(ErrorCodes.UnknownPeer, "Pair a device before sending.");
        }

        await _sendGate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            var transferId = Guid.NewGuid();
            var attempt = 1;
            var keyChangedStreak = 0;
            while (true)
            {
                await using var payload = await open(ct).ConfigureAwait(false);
                try
                {
                    var result = await SendOnceAsync(payload, transferId, ct).ConfigureAwait(false);
                    keyChangedStreak = 0;
                    if (result.State is TransferState.Succeeded)
                    {
                        PhoneBack();
                        _rejected = false;
                    }
                    if (result.State is not TransferState.Succeeded)
                    {
                        _log.Warn(DescribeSendResult(result, attempt));
                    }

                    var transient = result.Code == ErrorCodes.Io;
                    if (result.State is TransferState.Succeeded or TransferState.Cancelled
                        || !RetryPolicy.ShouldRetry(false, false, attempt, transient))
                    {
                        return result;
                    }

                    LastError = KeyChangeRetry.Retrying;
                    Changed?.Invoke();
                    _log.Warn($"Transfer interrupted. Retrying ({attempt + 1} of {RetryPolicy.MaxAttempts}). {result.Code}: {result.Message}");
                }
                catch (RelayQuotaException ex)
                {
                    LastError = ex.Message;
                    _log.Warn($"Transfer failed. {ex.Message}");
                    return new TransferResult(TransferState.Failed, RelayPlans.Quota, ex.Message, null, null, 0, false);
                }
                catch (PeerRejectedException ex) when (ex.Code == ErrorCodes.UnknownPeer && _peer is not null)
                {
                    _log.Warn($"Transfer failed. {ex.Message}");
                    LastError = ex.Message;
                    NoteRejected(ex);
                    throw;
                }
                catch (PeerRejectedException ex) when (ex.Code == ErrorCodes.KeyChanged && !KeyChangeRetry.IsOwnKey(ex, _identity.Fingerprint))
                {
                    keyChangedStreak++;
                    if (!KeyChangeRetry.ShouldRetry(_attemptLink, _lastPinOk, keyChangedStreak, attempt, DateTimeOffset.UtcNow))
                    {
                        _log.Warn($"Transfer failed. {ex.Message}");
                        LastError = ex.Message;
                        NoteRejected(ex);
                        throw;
                    }

                    LastError = KeyChangeRetry.Retrying;
                    Changed?.Invoke();
                    _log.Warn($"key_changed on {_attemptLink}. Retrying ({attempt + 1} of {RetryPolicy.MaxAttempts}). {ex.Message}");
                }
                catch (Exception ex) when (ex is IOException or TimeoutException or SocketException
                    || KeyChangeRetry.IsOwnKey(ex, _identity.Fingerprint))
                {
                    keyChangedStreak = 0;
                    var message = DescribeException(ex);
                    if (!RetryPolicy.ShouldRetry(false, false, attempt, true))
                    {
                        _log.Warn($"Transfer failed. {message}");
                        LastError = message;
                        throw new IOException(message, ex);
                    }

                    LastError = KeyChangeRetry.Retrying;
                    Changed?.Invoke();
                    _log.Warn($"Transfer interrupted. Retrying ({attempt + 1} of {RetryPolicy.MaxAttempts}). {message}");
                }

                attempt++;
                await Task.Delay(RetryPolicy.Delay(attempt - 1), ct).ConfigureAwait(false);
            }
        }
        finally
        {
            _sendGate.Release();
        }
    }

    /// <summary>A real handshake rejection of this PC's key or pin. Only this asks for re-pair.</summary>
    private void NoteRejected(PeerRejectedException ex)
    {
        _rejected = true;
        PhoneBack();
        _log.Warn($"Phone rejected this PC's key ({ex.Code}). Re-pair needed.");
        Changed?.Invoke();
    }

    private async Task<TransferResult> SendOnceAsync(OutgoingPayload payload, Guid transferId, CancellationToken ct)
    {
        var peer = _peer ?? throw new PeerRejectedException(ErrorCodes.UnknownPeer, "Pair a device before sending.");
        _attemptLink = TransferPath.Direct;
        _log.Info("Connecting to paired device.");
        WarmRelay(ct);
        await using var tls = await ConnectPeerAsync(peer, ct).ConfigureAwait(false);
        RememberPinOk();
        DirectAttempt? punch = null;
        if (tls.IsRelay)
        {
            punch = DirectAttempt.Start(tls.RelaySeen, NetworkInfo.ListLanIpv4());
        }

        try
        {
            var result = await TransferLink.SendAsync(tls.Stream, _identity, peer, tls.RemoteFingerprint, payload, new Progress<TransferProgress>(OnProgress), ct, hello =>
            {
                NotePhonePlan(hello);
                if (IsRepairOpen())
                {
                    RememberPeer(_peer ?? peer, confirmRepair: true);
                }
            }, transferId, punch, () =>
            {
                DirectLinkMissed = false;
                DirectLinkUp = true;
                SetPath(TransferPath.Cross);
                _log.Info("Direct link open.");
            }, () =>
            {
                DirectLinkUp = false;
                DirectLinkMissed = true;
                _log.Info("Direct link did not open. Sending through the relay.");
            }).ConfigureAwait(false);
            if (result.State == TransferState.Succeeded && PathLabel == TransferPath.Direct)
            {
                RememberPeer(_peer ?? peer);
            }

            LastError = result.State == TransferState.Succeeded ? null : result.Message;
            if (result.State == TransferState.Succeeded)
            {
                _log.Info($"Transfer {result.State}.");
            }

            return result;
        }
        catch (Exception ex) when (ex is IOException or TimeoutException or SocketException)
        {
            var message = DescribeException(ex);
            LastError = message;
            _log.Warn($"Transfer failed after connect. {message}");
            return new TransferResult(TransferState.Failed, ErrorCodes.Io, message, null, null, 0, false);
        }
        finally
        {
            punch?.Dispose();
        }
    }

    private static string DescribeException(Exception ex)
    {
        var message = string.IsNullOrWhiteSpace(ex.Message) ? "Transfer failed." : ex.Message;
        return $"{ex.GetType().Name}: {message}";
    }

    private static string DescribeSendResult(TransferResult result, int attempt)
    {
        var reason = string.IsNullOrWhiteSpace(result.Message) ? "Transfer failed." : result.Message;
        return $"Transfer {result.State} on attempt {attempt}. {result.Code}: {reason}";
    }

    private async Task<IPEndPoint> ResolveAsync(PeerRecord peer, CancellationToken ct)
    {
        var interfaces = NetworkInfo.ListInterfaces();
        var request = new DiscoveryRequest(
            peer.LastHost,
            _options.ManualHost,
            NetworkInfo.TryGetGateway(),
            NetworkInfo.TryGetLanIpv4(),
            peer.Port,
            _options.AllowLoopback,
            peer.Hosts,
            interfaces);
        var plan = DiscoveryPlanner.Plan(request);
        _ = LanDiscovery.AnnounceAsync(_identity.DeviceId, BoundPort == 0 ? _options.Port : BoundPort, _identity.Name, ct);
        var hit = await DiscoveryCoordinator.RunAsync(
            plan,
            (timeout, token) => LanDiscovery.ListenMdnsAsync(peer.DeviceId, timeout, token),
            (timeout, token) => LanDiscovery.ListenBroadcastAsync(peer.DeviceId, timeout, token),
            LanDiscovery.TcpProbeAsync,
            ct).ConfigureAwait(false);
        if (hit is null)
        {
            var message = DiscoveryPlanner.DescribeFailure(plan);
            LastError = message;
            throw new DiscoveryException(message);
        }

        return new IPEndPoint(IPAddress.Parse(hit.Ip), hit.Port);
    }

    private async Task<TlsConnection> ConnectAdvertAsync(PeerRecord peer, IReadOnlyList<string> hosts, CancellationToken ct)
    {
        try
        {
            if (hosts.Count > 0)
            {
                var tls = await DirectDial.FirstAsync(
                    hosts,
                    peer.Port,
                    (endpoint, token) => TlsChannel.ConnectAsync(endpoint, _identity.Certificate, peer.Fingerprint, token),
                    ct).ConfigureAwait(false);
                SetPath(TransferPath.Direct);
                return tls;
            }
        }
        catch (DiscoveryException)
        {
            if (string.IsNullOrWhiteSpace(_options.RelayUrl))
            {
                LastError = Reachability.Phone;
                throw new DiscoveryException(Reachability.Phone);
            }
        }

        return await DialRelayAsync(RelayRoom.Pairing(peer.DeviceId), peer.Fingerprint, allowNew: false, ct).ConfigureAwait(false);
    }

    private async Task<TlsConnection> ConnectPeerAsync(PeerRecord peer, CancellationToken ct)
    {
        Exception? lanError = null;
        IPEndPoint? endpoint = null;
        using (var search = CancellationTokenSource.CreateLinkedTokenSource(ct))
        {
            search.CancelAfter(LanSend.AttemptBudgetMs);
            try
            {
                endpoint = await ResolveAsync(peer, search.Token).ConfigureAwait(false);
            }
            catch (Exception ex) when (IsLanMiss(ex))
            {
                if (ct.IsCancellationRequested)
                {
                    throw;
                }

                lanError = ex;
            }
        }

        if (NetworkInfo.VpnOn() && NetworkInfo.TryGetLanIpv4() is null)
        {
            var keepLan = endpoint is not null && (_options.AllowLoopback || IPAddress.IsLoopback(endpoint.Address));
            if (!keepLan)
            {
                _log.Info(VpnStatus.Line);
                lanError = new DiscoveryException(VpnStatus.Line);
                endpoint = null;
            }
        }

        var listenPort = BoundPort == 0 ? _options.Port : BoundPort;
        if (endpoint is not null
            && endpoint.Port == listenPort
            && DeliveryFault.IsOwnAddress(endpoint.Address.ToString(), NetworkInfo.ListLanIpv4()))
        {
            _log.Info(DeliveryFault.SelfAnswered);
            lanError = new SelfDialException();
            endpoint = null;
        }

        if (endpoint is not null)
        {
            _attemptLink = TransferPath.Direct;
            using var handshake = CancellationTokenSource.CreateLinkedTokenSource(ct);
            handshake.CancelAfter(LanSend.HandshakeBudgetMs);
            try
            {
                var localText = LanPresence.BindAddress(endpoint.Address.ToString(), NetworkInfo.ListInterfaces());
                IPAddress? local = null;
                if (!string.IsNullOrWhiteSpace(localText))
                {
                    IPAddress.TryParse(localText, out local);
                }

                var tls = await TlsChannel.ConnectAsync(endpoint, _identity.Certificate, peer.Fingerprint, handshake.Token, local).ConfigureAwait(false);
                if (DeliveryFault.IsSelf(tls.RemoteFingerprint, _identity.Fingerprint))
                {
                    await tls.DisposeAsync().ConfigureAwait(false);
                    throw new SelfDialException();
                }

                SetPath(TransferPath.Direct);
                _peer = peer with { LastHost = endpoint.Address.ToString(), Port = endpoint.Port };
                return tls;
            }
            catch (Exception ex) when (IsLanMiss(ex) || KeyChangeRetry.IsOwnKey(ex, _identity.Fingerprint))
            {
                if (ct.IsCancellationRequested)
                {
                    throw;
                }

                lanError = KeyChangeRetry.MapOwnKey(ex, _identity.Fingerprint);
                _log.Info(lanError is SelfDialException
                    ? DeliveryFault.SelfAnswered
                    : $"{DeliveryFault.LanUnfinished} {ex.GetType().Name}: {ex.Message}");
                return await AfterLanMissAsync(peer, lanError, ct).ConfigureAwait(false);
            }
        }

        return await AfterLanMissAsync(peer, lanError, ct).ConfigureAwait(false);
    }

    private async Task<TlsConnection> AfterLanMissAsync(PeerRecord peer, Exception? lanError, CancellationToken ct)
    {
        _attemptLink = TransferPath.Bluetooth;
        var bluetooth = await TryBluetoothAsync(peer, ct).ConfigureAwait(false);
        if (bluetooth is not null)
        {
            return bluetooth;
        }

        if (string.IsNullOrWhiteSpace(_options.RelayUrl))
        {
            if (lanError is DiscoveryException discovery)
            {
                throw discovery;
            }

            LastError = Reachability.Phone;
            throw new DiscoveryException(Reachability.Phone);
        }

        _log.Info("Phone not on this Wi-Fi. Waking relay…");
        _attemptLink = TransferPath.Relay;
        SetPath(RelayWake.Status);
        Changed?.Invoke();
        DeviceIdentity self;
        lock (_repairGate)
        {
            self = _repair?.Previous ?? _identity;
        }

        return await DialRelayAsync(RelayRoom.Paired(self.DeviceId, peer.DeviceId), peer.Fingerprint, allowNew: false, ct).ConfigureAwait(false);
    }

    private async Task<TlsConnection?> TryBluetoothAsync(PeerRecord peer, CancellationToken ct)
    {
        if (!BluetoothChannel.RadioOn())
        {
            return null;
        }

        using var budget = CancellationTokenSource.CreateLinkedTokenSource(ct);
        budget.CancelAfter(BluetoothService.AttemptMs);
        try
        {
            var stream = BluetoothChannel.Dial(BluetoothBook.Load(_options.DataDirectory), budget.Token);
            if (stream is null)
            {
                return null;
            }

            if (stream.RemoteAddress != 0)
            {
                BluetoothBook.Save(_options.DataDirectory, stream.RemoteAddress);
            }

            _attemptLink = TransferPath.Bluetooth;
            var tls = await TlsChannel.ConnectStreamAsync(stream, _identity.Certificate, peer.Fingerprint, ct).ConfigureAwait(false);
            if (DeliveryFault.IsSelf(tls.RemoteFingerprint, _identity.Fingerprint))
            {
                await tls.DisposeAsync().ConfigureAwait(false);
                return null;
            }

            SetPath(TransferPath.Bluetooth);
            _log.Info("Sending over Bluetooth.");
            return tls;
        }
            catch (PeerRejectedException ex) when (KeyChangeRetry.IsOwnKey(ex, _identity.Fingerprint))
            {
                return null;
            }
            catch (PeerRejectedException)
            {
                throw;
            }
            catch (Exception ex)
            {
                if (ct.IsCancellationRequested && ex is OperationCanceledException)
                {
                    throw;
                }

                _log.Info($"Bluetooth did not answer. {ex.GetType().Name}: {ex.Message}");
                return null;
            }
    }

    private void OnBluetooth(BluetoothStream stream)
    {
        _ = Task.Run(async () =>
        {
            try
            {
                if (stream.RemoteAddress != 0)
                {
                    BluetoothBook.Save(_options.DataDirectory, stream.RemoteAddress);
                }

                SweepRepair();
                var pairing = IsRepairOpen() || (DateTimeOffset.UtcNow <= _pairingUntil && _peer is null);
                if (_peer is null && !pairing)
                {
                    _log.Info("Rejected a Bluetooth connection while unpaired.");
                    return;
                }

                SetPath(TransferPath.Bluetooth);
                await using var tls = await TlsChannel.AcceptStreamAsync(
                    stream,
                    _identity.Certificate,
                    expectedFingerprint: null,
                    allowNew: true,
                    _listenCts?.Token ?? CancellationToken.None).ConfigureAwait(false);
                await FinishIncomingAsync(tls, null, pairing, _listenCts?.Token ?? CancellationToken.None).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                _log.Warn($"Bluetooth connection closed. {ex.GetType().Name}: {ex.Message}");
            }
            finally
            {
                stream.Dispose();
            }
        }, CancellationToken.None);
    }

    private static bool IsLanMiss(Exception ex) =>
        ex is DiscoveryException or IOException or SocketException or TimeoutException
        or OperationCanceledException or ObjectDisposedException or InvalidOperationException;

    private void WarmRelay(CancellationToken ct)
    {
        var url = _options.RelayUrl;
        if (string.IsNullOrWhiteSpace(url))
        {
            return;
        }

        _ = Task.Run(async () =>
        {
            try
            {
                await RelayClient.WakeAsync(url, null, ct).ConfigureAwait(false);
            }
            catch
            {
            }
        }, CancellationToken.None);
    }

    private async Task<TlsConnection> DialRelayAsync(string room, string fingerprint, bool allowNew, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(_options.RelayUrl))
        {
            LastError = Reachability.Phone;
            throw new DiscoveryException(Reachability.Phone);
        }

        _attemptLink = TransferPath.Relay;
        _relayDial.Begin();
        await LeaveRelayListenAsync().ConfigureAwait(false);
        try
        {
        var deadline = DateTime.UtcNow + TimeSpan.FromSeconds(60);
        var relayAwake = false;
        var pause = TimeSpan.FromMilliseconds(250);
        while (true)
        {
            ct.ThrowIfCancellationRequested();
            Stream? stream = null;
            try
            {
                _log.Info(RelayWake.Status);
                SetPath(RelayWake.Status);
                Changed?.Invoke();
                stream = await RelayClient.OpenAsync(_options.RelayUrl, room, RelayRole.Dial, PhoneCredential(), () =>
                {
                    _log.Info(RelayWake.Status);
                    SetPath(RelayWake.Status);
                    Changed?.Invoke();
                }, ct, _identity.DeviceId.ToString("D")).ConfigureAwait(false);
                relayAwake = true;
                var seen = stream is WebSocketByteStream web ? web.Seen : null;
                var tls = allowNew
                    ? await TlsChannel.AcceptStreamAsync(stream, _identity.Certificate, fingerprint, allowNew: true, ct).ConfigureAwait(false)
                    : await TlsChannel.ConnectStreamAsync(stream, _identity.Certificate, fingerprint, ct).ConfigureAwait(false);
                tls.IsRelay = true;
                tls.RelaySeen = seen;
                SetPath(TransferPath.Relay);
                PhoneBack();
                return tls;
            }
            catch (RelayQuotaException ex)
            {
                if (stream is not null)
                {
                    await stream.DisposeAsync().ConfigureAwait(false);
                }

                LastError = ex.Message;
                Changed?.Invoke();
                throw;
            }
            catch (RelayWakeException ex)
            {
                if (stream is not null)
                {
                    await stream.DisposeAsync().ConfigureAwait(false);
                }

                if (string.Equals(ex.Message, RelayWake.PhoneMissing, StringComparison.Ordinal))
                {
                    LastError = PhoneMissingNow();
                    Changed?.Invoke();
                    throw new RelayWakeException(LastError, ex);
                }

                LastError = ex.Message;
                Changed?.Invoke();
                throw;
            }
            catch (Exception ex) when (ex is SelfDialException || KeyChangeRetry.IsOwnKey(ex, _identity.Fingerprint))
            {
                if (stream is not null)
                {
                    await stream.DisposeAsync().ConfigureAwait(false);
                    stream = null;
                }

                _log.Info(DeliveryFault.RelaySelf);
                if (DateTime.UtcNow >= deadline)
                {
                    LastError = PhoneMissingNow();
                    throw new RelayWakeException(LastError);
                }

                await Task.Delay(TimeSpan.FromMilliseconds(400), ct).ConfigureAwait(false);
            }
            catch (PeerRejectedException)
            {
                if (stream is not null)
                {
                    await stream.DisposeAsync().ConfigureAwait(false);
                }

                throw;
            }
            catch (Exception ex) when (stream is not null && IsEarlyRelayTlsFailure(ex) && DateTime.UtcNow < deadline)
            {
                await stream.DisposeAsync().ConfigureAwait(false);
                stream = null;
                relayAwake = true;
                _log.Info($"Relay link dropped before the phone was ready. {ex.GetType().Name}: {ex.Message}");
                var remaining = deadline - DateTime.UtcNow;
                var wait = pause < remaining ? pause : remaining;
                if (wait > TimeSpan.Zero)
                {
                    await Task.Delay(wait, ct).ConfigureAwait(false);
                }

                pause = TimeSpan.FromMilliseconds(Math.Min(pause.TotalMilliseconds * 2, 2000));
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                if (stream is not null)
                {
                    await stream.DisposeAsync().ConfigureAwait(false);
                }

                if (relayAwake || ex is SelfDialException)
                {
                    LastError = PhoneMissingNow();
                    throw new RelayWakeException(LastError, ex);
                }

                LastError = Reachability.Phone;
                throw new DiscoveryException(Reachability.Phone);
            }
        }
        }
        finally
        {
            _relayDial.End();
            if (_relayDial.ShouldListen())
            {
                RestartRelayWait();
            }
        }
    }

    private static bool IsEarlyRelayTlsFailure(Exception ex) =>
        ex is IOException
            or TimeoutException
            or SocketException
            or System.Security.Authentication.AuthenticationException
            or InvalidOperationException;

    private void RestartRelayWait()
    {
        _relayCts?.Cancel();
        if (_listenCts is null || _listenCts.IsCancellationRequested)
        {
            return;
        }

        var linked = CancellationTokenSource.CreateLinkedTokenSource(_listenCts.Token);
        _relayCts = linked;
        var generation = Interlocked.Increment(ref _relayGeneration);
        _relayTask = Task.Run(() => RelayWaitLoop(linked.Token, generation), CancellationToken.None);
    }

    /// <summary>
    /// Drop this computer's relay listener before dialing. A dial that is still
    /// the listener gets paired with itself, and the phone never joins.
    /// </summary>
    private async Task LeaveRelayListenAsync()
    {
        var task = _relayTask;
        _relayCts?.Cancel();
        if (task is null)
        {
            return;
        }

        try
        {
            await task.WaitAsync(TimeSpan.FromSeconds(3)).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            _log.Info("Relay listen was still in the room.");
        }
        catch (Exception)
        {
            // The listen task already stopped.
        }

        await Task.Delay(200).ConfigureAwait(false);
    }

    private async Task RelayWaitLoop(CancellationToken ct, int generation)
    {
        try
        {
            while (!ct.IsCancellationRequested && generation == Volatile.Read(ref _relayGeneration))
            {
                if (!_relayDial.ShouldListen())
                {
                    await Task.Delay(200, ct).ConfigureAwait(false);
                    continue;
                }

                SweepRepair();
                var url = _options.RelayUrl;
                var room = CurrentRelayRoom();
                if (string.IsNullOrWhiteSpace(url) || room is null)
                {
                    await Task.Delay(500, ct).ConfigureAwait(false);
                    continue;
                }

                Stream? stream = null;
                try
                {
                    stream = await RelayClient.OpenAsync(url, room, RelayRole.Listen, PhoneCredential(), () => SetPath(RelayWake.Status), ct, _identity.DeviceId.ToString("D")).ConfigureAwait(false);
                    if (generation != Volatile.Read(ref _relayGeneration))
                    {
                        await stream.DisposeAsync().ConfigureAwait(false);
                        break;
                    }

                    var pairing = IsRepairOpen() || (DateTimeOffset.UtcNow <= _pairingUntil && _peer is null);
                    var pin = pairing ? null : PeerPin.OrNull(_peer?.Fingerprint);
                    if (!pairing && _peer is not null && pin is null)
                    {
                        _log.Warn("The saved pairing has no certificate pin. Refusing to compare a device id to one.");
                        await stream.DisposeAsync().ConfigureAwait(false);
                        stream = null;
                        await Task.Delay(1000, ct).ConfigureAwait(false);
                        continue;
                    }

                    var seen = stream is WebSocketByteStream web ? web.Seen : null;
                    SetPath(TransferPath.Relay);
                    await using var tls = await TlsChannel.AcceptStreamAsync(
                        stream,
                        _identity.Certificate,
                        pin,
                        pairing,
                        ct).ConfigureAwait(false);
                    tls.IsRelay = true;
                    tls.RelaySeen = seen;
                    stream = null;
                    DirectAttempt? punch = pairing ? null : DirectAttempt.Start(seen, NetworkInfo.ListLanIpv4());
                    try
                    {
                        await FinishIncomingAsync(tls, null, pairing, _listenCts?.Token ?? ct, punch).ConfigureAwait(false);
                    }
                    finally
                    {
                        punch?.Dispose();
                    }
                }
                catch (OperationCanceledException)
                {
                    if (stream is not null)
                    {
                        await stream.DisposeAsync().ConfigureAwait(false);
                        stream = null;
                    }

                    break;
                }
                catch (RelayQuotaException ex)
                {
                    if (stream is not null)
                    {
                        await stream.DisposeAsync().ConfigureAwait(false);
                    }

                    LastError = ex.Message;
                    Changed?.Invoke();
                    await Task.Delay(TimeSpan.FromSeconds(30), ct).ConfigureAwait(false);
                }
                catch (RelayWakeException ex)
                {
                    if (stream is not null)
                    {
                        await stream.DisposeAsync().ConfigureAwait(false);
                    }

                    LastError = ex.Message;
                    Changed?.Invoke();
                    await Task.Delay(TimeSpan.FromSeconds(5), ct).ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is IOException or SocketException or ProtocolException or PeerRejectedException or TimeoutException or InvalidOperationException or DiscoveryException or System.Security.Authentication.AuthenticationException)
                {
                    if (stream is not null)
                    {
                        await stream.DisposeAsync().ConfigureAwait(false);
                    }

                    if (ex.Message.Contains("No peer in the room yet", StringComparison.Ordinal))
                    {
                        await Task.Delay(1000, ct).ConfigureAwait(false);
                        continue;
                    }

                    LastError = ex.Message;
                    _log.Warn($"Relay listen paused. {ex.GetType().Name}: {ex.Message}");
                    await Task.Delay(1000, ct).ConfigureAwait(false);
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private void NotePhonePlan(HelloBody hello)
    {
        if (_peer is null)
        {
            return;
        }

        if (!string.Equals(hello.DeviceId, _peer.DeviceId.ToString("D"), StringComparison.OrdinalIgnoreCase))
        {
            return;
        }

        if (hello.Plan is null && hello.QuotaKey is null && hello.RelayLimit is null)
        {
            return;
        }

        var updated = _peer with
        {
            PhonePlan = hello.Plan ?? _peer.PhonePlan,
            PhoneQuotaKey = hello.QuotaKey ?? _peer.PhoneQuotaKey,
            PhoneRelayUsed = hello.RelayUsed ?? _peer.PhoneRelayUsed,
            PhoneRelayLimit = hello.RelayLimit ?? _peer.PhoneRelayLimit
        };
        if (updated == _peer)
        {
            return;
        }

        RememberPeer(updated);
    }

    private RelayCredential? PhoneCredential()
    {
        var key = _peer?.PhoneQuotaKey;
        var account = Environment.GetEnvironmentVariable("RELAY_ACCOUNT");
        var secret = Environment.GetEnvironmentVariable("RELAY_ACCOUNT_SECRET");
        string? proof = null;
        if (UnlimitedAccounts.TryNormalize(account, out var email) && !string.IsNullOrEmpty(secret))
        {
            account = email;
            proof = UnlimitedAccounts.Proof(secret, email);
        }
        else
        {
            account = null;
        }

        if (string.IsNullOrEmpty(key) && account is null)
        {
            return null;
        }

        return new RelayCredential(null, null, null, RelayPlans.Package, key, account, proof);
    }

    private string? CurrentRelayRoom()
    {
        if (_peer is not null)
        {
            DeviceIdentity self;
            lock (_repairGate)
            {
                self = _repair?.Previous ?? _identity;
            }

            return RelayRoom.Paired(self.DeviceId, _peer.DeviceId);
        }

        if (DateTimeOffset.UtcNow <= _pairingUntil)
        {
            return RelayRoom.Pairing(_identity.DeviceId);
        }

        return null;
    }

    private void SetPath(string label)
    {
        if (label != TransferPath.Cross)
        {
            DirectLinkUp = false;
        }

        if (label == TransferPath.Direct)
        {
            DirectLinkMissed = false;
        }

        if (PathLabel == label)
        {
            return;
        }

        PathLabel = label;
        Changed?.Invoke();
    }

    private void RememberPinOk()
    {
        _lastPinOk = DateTimeOffset.UtcNow;
        try
        {
            PinOkStore.Save(_options.DataDirectory, _lastPinOk.Value);
        }
        catch (IOException)
        {
        }
    }

    public void NoteLanAddresses(IReadOnlyCollection<string> addresses)
    {
        var before = _lanSnapshot;
        _lanSnapshot = addresses.ToArray();
        if (!NetworkHop.Changed(before, _lanSnapshot))
        {
            return;
        }

        RebindAfterHop();
    }

    private void OnLanChanged(object? sender, EventArgs e)
    {
        // The event often fires before the new address is bound. Reading immediately
        // compares the old snapshot to itself and skips the rebind.
        var ticket = Interlocked.Increment(ref _lanEvents);
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(400).ConfigureAwait(false);
                if (ticket != Volatile.Read(ref _lanEvents))
                {
                    return;
                }

                NoteLanAddresses(NetworkInfo.ListLanIpv4());
            }
            catch (Exception ex) when (ex is SocketException or NetworkInformationException)
            {
            }
        });
    }

    private void RebindAfterHop()
    {
        NetworkHops++;
        Interlocked.Increment(ref _hopGen);
        LastError = NetworkHop.Line;
        DirectLinkUp = false;
        DirectLinkMissed = false;
        PathLabel = "";
        foreach (var client in _live.Keys)
        {
            try
            {
                client.Close();
            }
            catch (SocketException)
            {
            }
        }

        var old = _listener;
        _listener = null;
        try
        {
            old?.Stop();
        }
        catch (SocketException)
        {
        }

        if (_listenCts is null || _listenCts.IsCancellationRequested)
        {
            Changed?.Invoke();
            return;
        }

        var listener = new TcpListener(IPAddress.Any, BoundPort == 0 ? _options.Port : BoundPort);
        try
        {
            listener.Start();
        }
        catch (SocketException)
        {
            listener = new TcpListener(IPAddress.Any, 0);
            listener.Start();
        }

        _listener = listener;
        BoundPort = ((IPEndPoint)listener.LocalEndpoint).Port;
        RestartRelayWait();
        _log.Info(NetworkHop.Line);
        Changed?.Invoke();
    }

    private async Task ListenLoopAsync(CancellationToken ct)
    {
        var seenHop = Volatile.Read(ref _hopGen);
        while (!ct.IsCancellationRequested)
        {
            var listener = _listener;
            if (listener is null)
            {
                try
                {
                    await Task.Delay(30, ct).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }

                continue;
            }

            TcpClient tcp;
            try
            {
                tcp = await listener.AcceptTcpClientAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex) when (ex is IOException or SocketException or ObjectDisposedException or InvalidOperationException or System.Security.Authentication.AuthenticationException)
            {
                if (Volatile.Read(ref _hopGen) != seenHop)
                {
                    seenHop = Volatile.Read(ref _hopGen);
                    continue;
                }

                LastError = ex.Message;
                _log.Warn($"Incoming connection closed. {ex.GetType().Name}: {ex.Message}");
                continue;
            }

            _live.TryAdd(tcp, 0);

            // A discovery probe and the real handshake can be in the backlog together.
            // Handling each socket on its own task keeps the probe from holding Accept.
            _ = Task.Run(() => HandleAcceptedAsync(tcp, ct), CancellationToken.None);
        }
    }

    private async Task HandleAcceptedAsync(TcpClient tcp, CancellationToken ct)
    {
        try
        {
            await HandleClientAsync(tcp, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
        }
        catch (Exception ex) when (ex is IOException or SocketException or ProtocolException or PeerRejectedException or TimeoutException or System.Security.Authentication.AuthenticationException)
        {
            if (ex is PeerRejectedException rejected && rejected.Code == ErrorCodes.KeyChanged)
            {
                _log.Warn($"Incoming connection closed. {ex.GetType().Name}: {ex.Message}");
            }
            else if (LastError != NetworkHop.Line)
            {
                LastError = ex.Message;
                _log.Warn($"Incoming connection closed. {ex.GetType().Name}: {ex.Message}");
            }
        }
        finally
        {
            _live.TryRemove(tcp, out _);
            tcp.Dispose();
        }
    }

    private async Task HandleClientAsync(TcpClient tcp, CancellationToken ct)
    {
        if (IsThrowawayProbe(tcp))
        {
            return;
        }

        SweepRepair();
        var pairing = IsRepairOpen() || (DateTimeOffset.UtcNow <= _pairingUntil && _peer is null);
        if (_peer is null && !pairing)
        {
            _log.Info("Rejected a connection while unpaired.");
        }

        var remoteHost = tcp.Client.RemoteEndPoint is IPEndPoint remote ? remote.Address.ToString() : null;
        SetPath(TransferPath.Direct);
        // Allow the handshake to finish so a phone this PC no longer recognizes gets
        // an error frame instead of a socket that closes with no explanation.
        await using var tls = await TlsChannel.AcceptAsync(tcp, _identity.Certificate, expectedFingerprint: null, allowNew: true, ct).ConfigureAwait(false);
        try
        {
            await FinishIncomingAsync(tls, remoteHost, pairing, ct).ConfigureAwait(false);
        }
        catch (PeerRejectedException ex) when (ex.Code == ErrorCodes.KeyChanged)
        {
            NoteIncomingKeyChange(tls.RemoteFingerprint, ex);
            throw;
        }
    }

    private void NoteIncomingKeyChange(string? presented, PeerRejectedException ex)
    {
        var stored = _peer?.Fingerprint;
        _log.Info(
            "key_changed raised by phone-sent " + ex.Code +
            ". presented=" + TrustNewKey.ShortPin(presented) +
            " stored=" + TrustNewKey.ShortPin(stored));
        if (!TrustNewKey.ShouldPrompt(_peer is not null, ex.Code, ex.Message, presented, stored))
        {
            LastError = "The transfer did not finish. FlipSend will try again.";
            PendingTrustPin = null;
            _log.Info("Presented pin matches stored, or no cert was on the socket. Not asking Trust.");
            Changed?.Invoke();
            return;
        }

        PendingTrustPin = presented;
        LastError = TrustNewKey.Message(_peer?.Name);
        Changed?.Invoke();
    }

    private async Task FinishIncomingAsync(TlsConnection tls, string? remoteHost, bool pairing, CancellationToken ct, DirectAttempt? punch = null)
    {
        var expect = pairing ? null : _peer;
        PeerRecord? pendingPeer = null;
        var result = await TransferLink.ReceiveOneAsync(
            tls.Stream,
            _identity,
            expect,
            tls.RemoteFingerprint,
            pairing,
            async (hello, token) =>
            {
                var ok = await _consent.AcceptPairingAsync(hello.Name, hello.Fingerprint, token).ConfigureAwait(false);
                if (!ok)
                {
                    return false;
                }

                pendingPeer = new PeerRecord(
                    Guid.Parse(hello.DeviceId),
                    hello.Name,
                    hello.Fingerprint,
                    Base64Url.Encode(tls.RemoteSpki),
                    LocalAddressPolicy.IsAllowedHost(remoteHost ?? "", _options.AllowLoopback) ? remoteHost : null,
                    ProtocolLimits.DefaultTcpPort,
                    DateTimeOffset.UtcNow);
                return true;
            },
            (offer, token) => _consent.AcceptIncomingAsync(offer, _peer?.Name ?? "device", token),
            offer => FileReceiveSink.Create(_inbox, offer.Name, offer.Sha256, offer.Size, offer.TransferId),
            text =>
            {
                TextReceived?.Invoke(text);
                return Task.CompletedTask;
            },
            new Progress<TransferProgress>(OnProgress),
            ct,
            HandleIncomingCompanionAsync,
            NotePhonePlan,
            (request, emit, token) =>
            {
                _log.Info($"Companion {request.Service} {request.Op}.");
                return Terminal.HandleAsync(request, _peer, emit, token);
            },
            _ =>
            {
                if (pendingPeer is not null)
                {
                    RememberPeer(pendingPeer, confirmRepair: true);
                }

                return Task.CompletedTask;
            },
            () => PhoneScreenStarted?.Invoke(),
            frame => PhoneScreenFrame?.Invoke(frame),
            () => PhoneScreenStopped?.Invoke(),
            ApplyRemoteInput,
            () => CaptureDesktop?.Invoke(),
            punch,
            () =>
            {
                DirectLinkMissed = false;
                DirectLinkUp = true;
                SetPath(TransferPath.Cross);
                _log.Info("Direct link open.");
            },
            () =>
            {
                DirectLinkUp = false;
                DirectLinkMissed = true;
                _log.Info("Direct link did not open. The relay carries the bytes.");
            },
            line =>
            {
                _log.Info(line);
                if (line.StartsWith("Remote desktop watch ended", StringComparison.Ordinal))
                {
                    Remote.EndWatch();
                    RemoteChanged?.Invoke();
                    Changed?.Invoke();
                }
                else if (line.StartsWith("Remote desktop request link closed", StringComparison.Ordinal))
                {
                    Remote.ParkWatch();
                    RemoteChanged?.Invoke();
                    Changed?.Invoke();
                }
            }).ConfigureAwait(false);
        PhoneBack();

        if (!string.IsNullOrWhiteSpace(result.SavedPath))
        {
            var saved = result.SavedPath;
            if (FolderArchive.IsBundle(saved))
            {
                try
                {
                    var folder = FolderArchive.Unpack(saved, _inbox);
                    File.Delete(saved);
                    saved = folder;
                }
                catch (Exception ex) when (ex is IOException or InvalidDataException)
                {
                    LastError = ex.Message;
                    _log.Warn("Could not unpack a folder.");
                }
            }

            FileReceived?.Invoke(saved);
        }

        if (result.State == TransferState.Failed)
        {
            LastError = result.Message;
        }
        else
        {
            RememberPinOk();
        }

        if (result.Code != RemoteDesktop.SessionCode)
        {
            _log.Info(result.PairingOnly ? "Pairing connection finished." : $"Incoming transfer {result.State}.");
        }
    }

    private void RestartPairingAnnounce()
    {
        StopPairingAnnounce();
        var announce = new CancellationTokenSource();
        _announceCts = announce;
        _ = Task.Run(async () =>
        {
            try
            {
                while (!announce.IsCancellationRequested && DateTimeOffset.UtcNow <= _pairingUntil && (_peer is null || IsRepairOpen()))
                {
                    var code = ShortCode.Format(_identity.Fingerprint);
                    var port = BoundPort == 0 ? _options.Port : BoundPort;
                    await LanDiscovery.AnnounceAsync(_identity.DeviceId, port, _identity.Name, announce.Token, code).ConfigureAwait(false);
                    try
                    {
                        await Task.Delay(1000, announce.Token).ConfigureAwait(false);
                    }
                    catch (Exception ex) when (ex is OperationCanceledException or ObjectDisposedException)
                    {
                        return;
                    }
                }
            }
            finally
            {
                if (ReferenceEquals(_announceCts, announce))
                {
                    SweepRepair();
                }
            }
        }, CancellationToken.None);
    }

    private void StopPairingAnnounce()
    {
        if (_announceCts is null)
        {
            return;
        }

        _announceCts.Cancel();
        _announceCts.Dispose();
        _announceCts = null;
    }

    private void OnProgress(TransferProgress progress) => Progress?.Invoke(progress);

    private static bool IsThrowawayProbe(TcpClient tcp)
    {
        try
        {
            var socket = tcp.Client;
            // Probes connect and close, so a FIN shows up immediately. A real handshake
            // can take longer than a few hundred milliseconds to write its first bytes,
            // and closing it at 500ms drops the transfer.
            var deadline = Environment.TickCount64 + 2000;
            while (true)
            {
                var remaining = (int)(deadline - Environment.TickCount64);
                if (remaining <= 0)
                {
                    return true;
                }

                var readable = socket.Poll(Math.Min(remaining, 50) * 1000, SelectMode.SelectRead);
                if (!readable)
                {
                    continue;
                }

                return socket.Available == 0;
            }
        }
        catch (SocketException)
        {
            return true;
        }
    }

    public void SetAlwaysAllow(IEnumerable<string> ids)
    {
        AlwaysAllowDeviceIds.Clear();
        foreach (var id in ids)
        {
            if (AlwaysAllow.IsDeviceId(id))
            {
                AlwaysAllowDeviceIds.Add(id.Trim());
            }
            else if (!string.IsNullOrWhiteSpace(id))
            {
                _log.Warn("Ignored an always-allow entry that is not a device id.");
            }
        }
    }

    public void ApplyDesktopProfile()
    {
        var relay = !DirectLinkUp && string.Equals(PathLabel, TransferPath.Relay, StringComparison.Ordinal);
        var profile = RemoteDesktop.Profile(relay);
        DesktopMaxWidth = profile.MaxWidth;
        DesktopQuality = profile.JpegQuality;
        RemoteDesktop.ActiveIntervalMs = profile.IntervalMs;
    }

    public void RefreshRemote()
    {
        var was = Remote.Enabled;
        Remote.Enabled = RemoteEnabled;
        Remote.Paired = _peer is not null;
        if (was && !Remote.Enabled && Remote.From == RemoteControl.FromPc && Remote.State != RemoteSessionState.Idle)
        {
            StopRemote();
        }
    }

    public void StopRemote()
    {
        var id = Remote.SessionId;
        Remote.Stop(id);
        RemoteChanged?.Invoke();
        Changed?.Invoke();
    }

    public async Task<ServiceResult> RequestRemoteAsync(string from, CancellationToken ct)
    {
        RefreshRemote();
        var peer = _peer ?? throw new PeerRejectedException(ErrorCodes.UnknownPeer, RemoteControl.NeedPair);
        var sessionId = Remote.SessionId ?? Guid.NewGuid().ToString("D");
        await using var tls = await ConnectPeerAsync(peer, ct).ConfigureAwait(false);
        using var body = System.Text.Json.JsonDocument.Parse($"{{\"session\":{System.Text.Json.JsonSerializer.Serialize(sessionId)},\"from\":{System.Text.Json.JsonSerializer.Serialize(from)}}}");
        var request = new ServiceRequest(Guid.NewGuid().ToString("D"), CompanionServices.RemoteControl, RemoteControl.OpRequest, body.RootElement.Clone());
        var result = await TransferLink.CallAsync(tls.Stream, _identity, peer, tls.RemoteFingerprint, request, ct).ConfigureAwait(false);
        if (result.Ok)
        {
            Remote.OpenLocal(sessionId, from);
            RemoteChanged?.Invoke();
        }

        return result;
    }

    public async Task<ServiceResult> DriveRemoteAsync(Func<RemoteInput?> nextInput, CancellationToken ct)
    {
        var sessionId = Remote.SessionId ?? throw new ProtocolException(RemoteControl.NeedAllow, ErrorCodes.Declined);
        var peer = _peer ?? throw new PeerRejectedException(ErrorCodes.UnknownPeer, RemoteControl.NeedPair);
        await using var tls = await ConnectPeerAsync(peer, ct).ConfigureAwait(false);
        return await TransferLink.DriveRemoteAsync(tls.Stream, _identity, peer, tls.RemoteFingerprint, sessionId, nextInput, ct).ConfigureAwait(false);
    }

    private async Task<ServiceResult> HandleIncomingCompanionAsync(ServiceRequest request, CancellationToken ct)
    {
        _log.Info($"Companion {request.Service} {request.Op}.");
        if (request.Service == CompanionServices.RemoteControl)
        {
            return await HandleIncomingRemoteAsync(request, ct).ConfigureAwait(false);
        }

        if (request.Service == CompanionServices.InstallResult)
        {
            LogInstallResults(request);
            return ServiceResult.Done(request.Id, "Install results logged.");
        }

        return Companion is null
            ? ServiceResult.Fail(request.Id, ErrorCodes.NotImplemented, "This device does not accept companion calls.")
            : Companion.Handle(request);
    }

    private void LogInstallResults(ServiceRequest request)
    {
        try
        {
            if (!request.Body.TryGetProperty("results", out var results) ||
                results.ValueKind != System.Text.Json.JsonValueKind.Array)
            {
                return;
            }

            foreach (var item in results.EnumerateArray())
            {
                var name = ServiceCodec.ReadString(item, "name") ?? "app";
                var status = ServiceCodec.ReadString(item, "status") ?? "";
                var reason = ServiceCodec.ReadString(item, "reason");
                var line = status switch
                {
                    "installed" => $"Install Succeeded: {name}",
                    "pending"   => $"Install Pending: {name}",
                    _           => string.IsNullOrWhiteSpace(reason)
                                       ? $"Install Failed: {name}"
                                       : $"Install Failed: {name}: {reason}"
                };
                _log.Info(line);
            }
        }
        catch (Exception)
        {
            // A malformed report must not affect the protocol.
        }
    }

    private async Task<ServiceResult> HandleIncomingRemoteAsync(ServiceRequest request, CancellationToken ct)
    {
        RefreshRemote();
        var session = ServiceCodec.ReadString(request.Body, "session") ?? Remote.SessionId ?? "";
        var from = ServiceCodec.ReadString(request.Body, "from") ?? RemoteControl.FromPhone;
        switch (request.Op)
        {
            case RemoteControl.OpStatus:
                return ServiceResult.Done(request.Id, RemoteDesktop.Ready);
            case RemoteControl.OpRequest:
                _log.Info($"Remote desktop request from {from}.");
                var asked = Remote.Request(session, from);
                if (!asked.Ok)
                {
                    _log.Warn($"Remote desktop refused. {asked.Message}");
                    return ServiceResult.Fail(request.Id, asked.Code, asked.Message);
                }

                var peerId = _peer?.DeviceId.ToString("D") ?? "";
                if (from == RemoteControl.FromPhone && AlwaysAllow.Contains(AlwaysAllowDeviceIds, peerId))
                {
                    _log.Info("Remote desktop auto-allowed for this phone.");
                    var skipped = Remote.Allow(session);
                    if (skipped.Ok && from == RemoteControl.FromPhone)
                    {
                        ApplyDesktopProfile();
                        if (!string.IsNullOrWhiteSpace(session))
                        {
                            Remote.BeginWatch(session);
                        }

                        _log.Info("Remote desktop session open.");
                        RemoteChanged?.Invoke();
                        Changed?.Invoke();
                    }

                    return skipped.Ok
                        ? ServiceResult.Done(request.Id, skipped.Message)
                        : ServiceResult.Fail(request.Id, skipped.Code, skipped.Message);
                }

                var choice = await _consent.AcceptRemoteControlAsync(_peer?.Name ?? "the paired phone", ct).ConfigureAwait(false);
                if (!choice.Allowed)
                {
                    var denied = Remote.Deny(session);
                    return ServiceResult.Fail(request.Id, denied.Code, denied.Message);
                }

                if (choice.Always && !string.IsNullOrWhiteSpace(peerId))
                {
                    AlwaysAllowDeviceIds.Add(peerId);
                    RememberAlwaysAllow?.Invoke(peerId);
                }

                _log.Info(choice.Allowed ? "Remote desktop allowed." : "Remote desktop was not allowed.");
                var ok = Remote.Allow(session);
                if (ok.Ok && from == RemoteControl.FromPhone)
                {
                    ApplyDesktopProfile();
                    if (!string.IsNullOrWhiteSpace(session))
                    {
                        Remote.BeginWatch(session);
                    }

                    _log.Info("Remote desktop session open.");
                }
                if (ok.Ok)
                {
                    RemoteChanged?.Invoke();
                    Changed?.Invoke();
                }

                return ok.Ok
                    ? ServiceResult.Done(request.Id, ok.Message)
                    : ServiceResult.Fail(request.Id, ok.Code, ok.Message);
            case RemoteControl.OpDrive:
            case RemoteControl.OpWatch:
                if (request.Op == RemoteControl.OpWatch)
                {
                    ApplyDesktopProfile();
                    _log.Info("Remote desktop watch starting.");
                }

                var activeId = string.IsNullOrWhiteSpace(session) ? Remote.SessionId : session;
                if (!Remote.IsActive(activeId))
                {
                    _log.Warn("Remote desktop watch refused. Allow was not given.");
                    return ServiceResult.Fail(request.Id, ErrorCodes.Declined, RemoteControl.NeedAllow);
                }

                if (request.Op == RemoteControl.OpWatch && activeId is not null)
                {
                    Remote.BeginWatch(activeId);
                }

                RemoteChanged?.Invoke();
                return ServiceResult.Done(request.Id, RemoteControl.Allowed);
            case RemoteControl.OpStop:
                _log.Info("Remote desktop stop.");
                Remote.EndWatch(string.IsNullOrWhiteSpace(session) ? null : session);
                RemoteChanged?.Invoke();
                Changed?.Invoke();
                return ServiceResult.Done(request.Id, RemoteControl.Stopped);
            default:
                return ServiceResult.Fail(request.Id, ErrorCodes.NotImplemented, "That remote control action is not available.");
        }
    }

    private bool ApplyRemoteInput(RemoteInput input)
    {
        RefreshRemote();
        if (!Remote.AcceptInput(input.Session))
        {
            return false;
        }

        ApplyDesktopInput?.Invoke(input);
        return true;
    }
}
