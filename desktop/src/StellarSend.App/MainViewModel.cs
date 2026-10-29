using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Windows.Input;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using QRCoder;
using StellarSend.Core.Companion;
using StellarSend.Core.Discovery;
using StellarSend.Core.Files;
using StellarSend.Core.Memo;
using StellarSend.Core.Pairing;
using StellarSend.Core.Shell;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;
using StellarSend.Core.Terminal;
using StellarSend.Core.Transfer;
using StellarSend.Core.Usb;

namespace StellarSend.App;

public sealed class MainViewModel : INotifyPropertyChanged
{
    private readonly MainWindow _window;
    private readonly AdbTransfer _adb = new(new ProcessRunner());
    private StellarNode? _node;
    private CancellationTokenSource _appCts = new();
    private string _statusText = "Starting";
    private string _detailText = "Pair once, then send text or files on your local network.";
    private string _hintText = "";
    private string _webHint = "";
    private string _outgoingText = "";
    private string _receivedText = "";
    private string _receivedPreview = "";
    private string _qrText = "";
    private string _usbStatus = "USB not checked.";
    private string _manualHost = "";
    private string _relayUrl = RelayPlans.DefaultRelay;
    private bool _relayUrlSet;
    private string _pathText = "";
    private bool _startWithWindows;
    private bool _bringWindowToFrontOnTransfer = TransferAttention.BringToFrontByDefault;
    private bool _shellMenu;
    private bool _hasQr;
    private bool _hasPeer;
    private string _shortCodeText = "";
    private string? _inboxDirectory;
    private Bitmap? _qrImage;
    private string _remoteTitle = "Remote Browser";
    private string _remoteSummary = "Opens a link in a browser on this PC. The desktop is not controlled.";
    private string _remoteCapabilities = "Open links: on. Live view: off. Typing: off.";
    private string _remoteStatus = "Waiting for the paired phone.";
    private string _memoTitle = "Stellar memo commands";
    private string _memoSummary = "Small commands can arrive from a Stellar account without a live link. The desktop is not controlled.";
    private string _memoStatus = "Not watching. No account and no seed are stored in this version.";
    private ILog _log = NullLog.Instance;
    private readonly Queue<string> _handedOff = new();
    private readonly object _handedGate = new();
    private CompanionHost? _companion;
    private DispatcherTimer? _clipboardTimer;
    private DispatcherTimer? _offlineTimer;
    private readonly List<TransferRow> _waitingForPhone = new();
    private bool _offlineRetrying;
    private bool _phoneWasOffline;
    private int _offlineMisses;
    private DateTimeOffset _nextOfflineTry;
    private string? _adbPath;
    private AdbDevice? _usbDevice;
    private bool _loadingSettings = true;
    private bool _terminalEnabled;
    private bool _terminalAllowlist = true;
    private string _terminalDraft = "";
    private readonly List<string> _terminalAllow = [];
    private PhoneScreenWindow? _phoneScreen;
    private Bitmap? _phoneScreenImage;
    private string _phoneScreenStatus = "The paired phone can show its screen here.";
    private bool _hasPhoneScreen;
    private bool _remoteControlEnabled;
    private bool _alwaysAllowThisPhone;
    private List<string> _alwaysIds = [];
    private bool _beingControlled;
    private bool _controllingPhone;
    private bool _remoteSessionOpen;
    private readonly Queue<RemoteInput> _remoteOut = new();
    private readonly object _remoteGate = new();
    private CancellationTokenSource? _driveCts;
    private string _castStatus = "";
    private bool _showSettings;
    private bool _connectionLinked;
    private bool _pairingOpen;
    private bool _started;
    private string _connectionLine = HomeStatus.NotConnected;
    private string _whoText = "No phone paired yet.";
    private string _pathPill = "";
    private string _headerStatus = "Not connected";
    private string _metaLine = "Scan a code to pair";
    private string _errorTitle = "";
    private string _errorDetail = "";
    private string _errorRaw = "";
    private string _detailsText = "";
    private bool _showDetails;

    public MainViewModel(MainWindow window)
    {
        _window = window;
        ShowQrCommand = new RelayCommand(EnlargeQrAsync);
        OpenSettingsCommand = new RelayCommand(() => ShowSettings = true);
        CloseSettingsCommand = new RelayCommand(() => ShowSettings = false);
        ToggleDetailsCommand = new RelayCommand(() => ShowDetails = !ShowDetails);
        OpenPhoneScreenCommand = new RelayCommand(OpenPhoneScreen);
        OpenReceivedCommand = new RelayCommand(OpenReceived);
        OpenOnWebCommand = new RelayCommand(OpenOnWebAsync);
        PairFromCodeCommand = new RelayCommand(PairFromCodeAsync);
        UnpairCommand = new RelayCommand(UnpairAsync);
        RotateCommand = new RelayCommand(RotateAsync);
        SendTextCommand = new RelayCommand(SendTextAsync);
        SendClipboardCommand = new RelayCommand(SendClipboardAsync);
        CopyReceivedCommand = new RelayCommand(CopyReceivedAsync);
        ChooseFileCommand = new RelayCommand(ChooseFileAsync);
        ChooseFolderCommand = new RelayCommand(ChooseFolderAsync);
        SendApkCommand = new RelayCommand(SendApkAsync);
        ChooseInboxCommand = new RelayCommand(ChooseInboxAsync);
        RefreshUsbCommand = new RelayCommand(RefreshUsbAsync);
        UsbSendCommand = new RelayCommand(() => UsbSendAsync());
        UsbPullCommand = new RelayCommand(() => UsbPullAsync());
        AddTerminalCommand = new RelayCommand(AddAllowedCommand);
        CastToTvCommand = new RelayCommand(CastToTv);
        ControlPhoneCommand = new RelayCommand(ControlPhoneAsync);
        StopRemoteCommand = new RelayCommand(StopRemote);
        PhoneBackCommand = new RelayCommand(() => EnqueuePhoneInput("back", 0, 0));
        PhoneHomeCommand = new RelayCommand(() => EnqueuePhoneInput("home", 0, 0));
        PhoneRecentsCommand = new RelayCommand(() => EnqueuePhoneInput("recents", 0, 0));
    }

    public ObservableCollection<TransferRow> Transfers { get; } = new();
    public ObservableCollection<PeerRow> Peers { get; } = new();
    public ObservableCollection<AllowRow> TerminalAllowRows { get; } = new();
    public ICommand ShowQrCommand { get; }
    public ICommand OpenSettingsCommand { get; }
    public ICommand CloseSettingsCommand { get; }
    public ICommand ToggleDetailsCommand { get; }
    public ICommand OpenPhoneScreenCommand { get; }
    public ICommand OpenReceivedCommand { get; }
    public ICommand OpenOnWebCommand { get; }
    public ICommand PairFromCodeCommand { get; }
    public ICommand UnpairCommand { get; }
    public ICommand RotateCommand { get; }
    public ICommand SendTextCommand { get; }
    public ICommand SendClipboardCommand { get; }
    public ICommand CopyReceivedCommand { get; }
    public ICommand ChooseFileCommand { get; }
    public ICommand ChooseFolderCommand { get; }
    public ICommand SendApkCommand { get; }
    public ICommand ChooseInboxCommand { get; }
    public ICommand RefreshUsbCommand { get; }
    public ICommand UsbSendCommand { get; }
    public ICommand UsbPullCommand { get; }
    public ICommand AddTerminalCommand { get; }
    public ICommand CastToTvCommand { get; }
    public ICommand ControlPhoneCommand { get; }
    public ICommand StopRemoteCommand { get; }
    public ICommand PhoneBackCommand { get; }
    public ICommand PhoneHomeCommand { get; }
    public ICommand PhoneRecentsCommand { get; }

    public string StatusText { get => _statusText; private set => Set(ref _statusText, value); }
    public string DetailText { get => _detailText; private set => Set(ref _detailText, value); }
    public string HintText
    {
        get => _hintText;
        private set
        {
            if (Set(ref _hintText, value)) Raise(nameof(HasHint));
        }
    }
    public bool HasHint => !string.IsNullOrWhiteSpace(HintText);
    public string WebHint
    {
        get => _webHint;
        private set
        {
            if (Set(ref _webHint, value))
            {
                Raise(nameof(HasWebHint));
            }
        }
    }
    public bool HasWebHint => !string.IsNullOrWhiteSpace(WebHint);
    public string OutgoingText { get => _outgoingText; set => Set(ref _outgoingText, value); }
    public string ReceivedPreview { get => _receivedPreview; private set => Set(ref _receivedPreview, value); }
    public bool HasReceivedText => !string.IsNullOrEmpty(_receivedText);
    public string QrText { get => _qrText; private set => Set(ref _qrText, value); }
    public string UsbStatus { get => _usbStatus; private set => Set(ref _usbStatus, value); }
    public string InboxText => "Received files: " + (_node?.InboxDirectory ?? _inboxDirectory ?? "");
    public string ShortCodeText { get => _shortCodeText; private set => Set(ref _shortCodeText, value); }
    public bool HasPeer { get => _hasPeer; private set { if (Set(ref _hasPeer, value)) { Raise(nameof(NoPeer)); Raise(nameof(ShowPairCard)); } } }
    public bool NoPeer => !HasPeer;

    /// <summary>True while a pairing or re-pair window is open, so the QR shows even when a phone is still paired.</summary>
    public bool PairingOpen { get => _pairingOpen; private set { if (Set(ref _pairingOpen, value)) Raise(nameof(ShowPairCard)); } }

    /// <summary>The pairing card (QR) shows when no phone is paired or while re-pair is waiting for the phone.</summary>
    public bool ShowPairCard => NoPeer || PairingOpen;

    /// <summary>One plain line near the top: Connected on Wi-Fi, Connected through relay, Not connected.</summary>
    public string ConnectionLine { get => _connectionLine; private set => Set(ref _connectionLine, value); }
    public string HeaderStatus { get => _headerStatus; private set => Set(ref _headerStatus, value); }
    public string MetaLine { get => _metaLine; private set => Set(ref _metaLine, value); }
    public string ErrorTitle { get => _errorTitle; private set => Set(ref _errorTitle, value); }
    public string ErrorDetail
    {
        get => _errorDetail;
        private set
        {
            if (Set(ref _errorDetail, value))
            {
                Raise(nameof(HasErrorBanner));
            }
        }
    }
    public bool HasErrorBanner => !string.IsNullOrWhiteSpace(ErrorTitle) || !string.IsNullOrWhiteSpace(ErrorDetail);
    public string DetailsText
    {
        get => _detailsText;
        private set
        {
            if (Set(ref _detailsText, value))
            {
                Raise(nameof(HasDetails));
            }
        }
    }
    public bool HasDetails => !string.IsNullOrWhiteSpace(DetailsText);
    public bool ShowDetails
    {
        get => _showDetails;
        private set => Set(ref _showDetails, value);
    }
    public string RemoteTitle { get => _remoteTitle; private set => Set(ref _remoteTitle, value); }
    public string RemoteSummary { get => _remoteSummary; private set => Set(ref _remoteSummary, value); }
    public string RemoteCapabilities { get => _remoteCapabilities; private set => Set(ref _remoteCapabilities, value); }
    public string RemoteStatus { get => _remoteStatus; private set => Set(ref _remoteStatus, value); }
    public string MemoTitle { get => _memoTitle; private set => Set(ref _memoTitle, value); }
    public string MemoSummary { get => _memoSummary; private set => Set(ref _memoSummary, value); }
    public string MemoStatus { get => _memoStatus; private set => Set(ref _memoStatus, value); }
    public bool HasQr { get => _hasQr; private set => Set(ref _hasQr, value); }
    public bool ShowSettings { get => _showSettings; private set => Set(ref _showSettings, value); }
    public bool ConnectionLinked { get => _connectionLinked; private set => Set(ref _connectionLinked, value); }
    public string WhoText { get => _whoText; private set => Set(ref _whoText, value); }
    public string PathPill
    {
        get => _pathPill;
        private set
        {
            if (Set(ref _pathPill, value))
            {
                Raise(nameof(HasPathPill));
            }
        }
    }
    public bool HasPathPill => !string.IsNullOrWhiteSpace(PathPill);
    public bool HasTransfers => Transfers.Count > 0;
    public bool HasTerminalAllow => TerminalAllowRows.Count > 0;
    public string TerminalDraft { get => _terminalDraft; set => Set(ref _terminalDraft, value); }
    public Bitmap? PhoneScreenImage
    {
        get => _phoneScreenImage;
        private set
        {
            var previous = _phoneScreenImage;
            if (Set(ref _phoneScreenImage, value))
            {
                previous?.Dispose();
            }
        }
    }
    public string PhoneScreenStatus { get => _phoneScreenStatus; private set => Set(ref _phoneScreenStatus, value); }
    public bool HasPhoneScreen { get => _hasPhoneScreen; private set => Set(ref _hasPhoneScreen, value); }
    public string CastStatus
    {
        get => _castStatus;
        private set
        {
            if (Set(ref _castStatus, value))
            {
                Raise(nameof(HasCastStatus));
            }
        }
    }
    public bool HasCastStatus => !string.IsNullOrWhiteSpace(_castStatus);

    public bool RemoteControlEnabled
    {
        get => _remoteControlEnabled;
        set
        {
            if (!Set(ref _remoteControlEnabled, value) || _loadingSettings)
            {
                return;
            }

            if (_node is not null)
            {
                _node.RemoteEnabled = value;
                _node.RefreshRemote();
            }

            SaveSettings();
            Raise(nameof(CanStartPhoneControl));
        }
    }

    public bool BeingControlled { get => _beingControlled; private set => Set(ref _beingControlled, value); }
    public bool ControllingPhone { get => _controllingPhone; private set { if (Set(ref _controllingPhone, value)) Raise(nameof(CanStartPhoneControl)); } }
    public bool RemoteSessionOpen { get => _remoteSessionOpen; private set { if (Set(ref _remoteSessionOpen, value)) Raise(nameof(CanStartPhoneControl)); } }
    public bool CanStartPhoneControl => !_controllingPhone && HasPhoneScreen;

    public bool AlwaysAllowThisPhone
    {
        get => _alwaysAllowThisPhone;
        set
        {
            if (!Set(ref _alwaysAllowThisPhone, value) || _loadingSettings)
            {
                return;
            }

            var id = _node?.Peer?.DeviceId.ToString("D");
            if (string.IsNullOrWhiteSpace(id))
            {
                return;
            }

            _alwaysIds = value ? AlwaysAllow.Add(_alwaysIds, id) : AlwaysAllow.Remove(_alwaysIds, id);
            _node?.SetAlwaysAllow(_alwaysIds);
            SaveSettings();
        }
    }
    public string RemoteBanner => BeingControlled ? RemoteControl.ActiveLine : RemoteControl.Banner;

    public bool TerminalEnabled
    {
        get => _terminalEnabled;
        set
        {
            if (!Set(ref _terminalEnabled, value) || _loadingSettings)
            {
                return;
            }

            PushTerminal();
            SaveSettings();
        }
    }

    public bool TerminalAllowlist
    {
        get => _terminalAllowlist;
        set
        {
            if (!Set(ref _terminalAllowlist, value) || _loadingSettings)
            {
                return;
            }

            PushTerminal();
            SaveSettings();
        }
    }
    public Bitmap? QrImage { get => _qrImage; private set => Set(ref _qrImage, value); }

    public string ManualHost
    {
        get => _manualHost;
        set
        {
            if (Set(ref _manualHost, value) && _node is not null)
            {
                _node.SetManualHost(value);
                SaveSettings();
            }
        }
    }

    public string RelayUrl
    {
        get => _relayUrl;
        set
        {
            if (!Set(ref _relayUrl, value) || _loadingSettings)
            {
                return;
            }

            _relayUrlSet = true;
            _node?.SetRelayUrl(string.IsNullOrWhiteSpace(value) ? null : value.Trim());
            SaveSettings();
        }
    }

    public string PathText
    {
        get => _pathText;
        private set
        {
            if (Set(ref _pathText, value))
            {
                Raise(nameof(HasPath));
            }
        }
    }

    public bool HasPath => !string.IsNullOrWhiteSpace(_pathText);

    public bool StartWithWindows
    {
        get => _startWithWindows;
        set
        {
            if (!Set(ref _startWithWindows, value) || _loadingSettings)
            {
                return;
            }

            try
            {
                if (value)
                {
                    WindowsIntegration.EnableStartup();
                }
                else
                {
                    WindowsIntegration.DisableStartup();
                }
            }
            catch (Exception ex)
            {
                ReportError(ex.Message);
            }

            SaveSettings();
        }
    }

    public bool BringWindowToFrontOnTransfer
    {
        get => _bringWindowToFrontOnTransfer;
        set
        {
            if (!Set(ref _bringWindowToFrontOnTransfer, value) || _loadingSettings)
            {
                return;
            }

            SaveSettings();
        }
    }

    public bool ShellMenu
    {
        get => _shellMenu;
        set
        {
            if (!Set(ref _shellMenu, value) || _loadingSettings)
            {
                return;
            }

            try
            {
                if (value)
                {
                    WindowsIntegration.RegisterShell();
                }
                else
                {
                    WindowsIntegration.UnregisterShell();
                }
            }
            catch (Exception ex)
            {
                ReportError(ex.Message);
            }

            SaveSettings();
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public async Task StartAsync()
    {
        // The window raises Opened again every time it comes back from the tray.
        // Start the node once; a second start replaced the live node with one that
        // could not bind the port ("Port busy") and left the window showing a dead node.
        if (_started)
        {
            RefreshStatus();
            return;
        }

        _started = true;
        try
        {
            Directory.CreateDirectory(AppPaths.DataDirectory);
            LoadSettings();
            var protector = OperatingSystem.IsWindows()
                ? (IKeyProtector)new DpapiKeyProtector()
                : new MemoryProtector();
            _log = new RedactingFileLog(AppPaths.LogFile);
            _ = AcceptHandedFilesAsync();
            _node = new StellarNode(new StellarNodeOptions
            {
                DataDirectory = AppPaths.DataDirectory,
                DisplayName = "Windows PC",
                ManualHost = string.IsNullOrWhiteSpace(_manualHost) ? null : _manualHost.Trim(),
                RelayUrl = EffectiveRelayUrl(),
                InboxDirectory = string.IsNullOrWhiteSpace(_inboxDirectory) ? null : _inboxDirectory,
                AllowLoopback = false
            }, protector, new WindowConsent(_window), _log);
            _companion = new CompanionHost(new WindowClipboard(_window), new ToastNotice(), new UrlOnlyBrowserSurface());
            var plugin = _companion.Plugins()[0];
            RemoteTitle = plugin.Title;
            RemoteSummary = plugin.Summary;
            RemoteCapabilities = $"Open links: {(plugin.Open ? "on" : "off")}. Live view: {(plugin.View ? "on" : "off")}. Typing: {(plugin.Input ? "on" : "off")}.";
            _companion.Completed += OnCompanion;
            _node.Companion = _companion;
            PushTerminal();
            _clipboardTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
            var memo = new MemoCommandChannel(
                new DisconnectedMemoLedger(),
                new UnconfiguredMemoCrypto(),
                new MemoCommandExecutor(new WindowClipboard(_window), new ToastNotice(), new UrlOnlyBrowserSurface(), new WindowsPcLock()));
            var memoSnapshot = memo.Snapshot();
            MemoTitle = memoSnapshot.Title;
            MemoSummary = memoSnapshot.Summary;
            MemoStatus = memoSnapshot.Status;
            _clipboardTimer.Tick += (_, _) =>
            {
                _companion?.Tick();
                memo.Tick();
            };
            _clipboardTimer.Start();
            _offlineTimer = new DispatcherTimer { Interval = RelayWake.OfflineCheckEvery };
            _offlineTimer.Tick += async (_, _) =>
            {
                if (_waitingForPhone.Count > 0 && DateTimeOffset.UtcNow >= _nextOfflineTry)
                {
                    await RetryWaitingAsync();
                }
            };
            _offlineTimer.Start();
            _node.Progress += OnProgress;
            _node.TextReceived += OnTextReceived;
            _node.FileReceived += path => OnUi(async () => await OnFileReceivedAsync(path));
            _node.Changed += () => OnUi(RefreshStatus);
            _node.PhoneScreenStarted += () => OnUi(OnPhoneScreenStarted);
            _node.PhoneScreenFrame += frame => OnUi(() => OnPhoneScreenFrame(frame));
            _node.PhoneScreenStopped += () => OnUi(OnPhoneScreenStopped);
            _node.RemoteChanged += () => OnUi(OnRemoteChanged);
            _node.RemoteEnabled = _remoteControlEnabled;
            _node.SetAlwaysAllow(_alwaysIds);
            _node.RememberAlwaysAllow = id => OnUi(() =>
            {
                _alwaysIds = AlwaysAllow.Add(_alwaysIds, id);
                _node.SetAlwaysAllow(_alwaysIds);
                SyncAlwaysAllow();
                SaveSettings();
            });
            WindowsDesktop.CaptureFault = line => _log.Warn(line);
            _node.CaptureDesktop = () => WindowsDesktop.CaptureJpeg(_node.DesktopMaxWidth, _node.DesktopQuality);
            _node.ApplyDesktopInput = WindowsDesktop.Apply;
            await StartListeningWithPortRetryAsync();
            RefreshStatus();
            if (_node.IsPaired)
            {
                PaintHomeQr();
            }
            else
            {
                await ShowQrAsync();
            }
            Raise(nameof(InboxText));
            // The file this launch was started with joins the same queue as files handed over by
            // later launches, so queued sends go out one at a time, in order, from this one FlipSend.
            if (!string.IsNullOrWhiteSpace(Program.StartupFile))
            {
                lock (_handedGate)
                {
                    _handedOff.Enqueue(Program.StartupFile);
                }
            }

            _ = SendHandedFilesAsync();
        }
        catch (Exception ex)
        {
            ReportError(ex.Message);
        }
    }

    public void ReportError(string message)
    {
        OnUi(() => ShowError(message));
    }

    private async Task StartListeningWithPortRetryAsync()
    {
        Exception? last = null;
        for (var attempt = 0; attempt < 5; attempt++)
        {
            try
            {
                await _node!.StartAsync(_appCts.Token);
                return;
            }
            catch (Exception ex) when (PlainError.IsPortBusy(ex) && attempt < 4)
            {
                last = ex;
                _log.Warn("Port busy, trying again. " + ex.Message);
                ShowError(ex.Message);
                await Task.Delay(350 * (attempt + 1));
            }
        }

        throw last ?? new InvalidOperationException(PlainError.PortBusyTitle);
    }

    private void ShowError(string message)
    {
        var shown = PlainError.Describe(message);
        _errorRaw = message ?? "";
        RememberDetails(message);
        if (!PlainError.ShouldBanner(message))
        {
            ErrorTitle = "";
            ErrorDetail = "";
            return;
        }

        ErrorTitle = shown.Title;
        ErrorDetail = shown.Detail;
        if (PlainError.IsPortBusy(message) || PlainError.IsKeyChanged(message))
        {
            return;
        }

        StatusText = shown.Title;
        DetailText = shown.Detail;
    }

    private void RememberDetails(string? message)
    {
        var text = message?.Trim() ?? "";
        if (text.Length == 0 || PlainError.IsRetrying(text))
        {
            if (string.IsNullOrWhiteSpace(DetailsText))
            {
                DetailsText = "";
            }

            return;
        }

        if (PlainError.IsOffWifi(text) || PlainError.IsRelayDrop(text) || LooksTechnical(text))
        {
            DetailsText = text;
        }
    }

    private static bool LooksTechnical(string text) =>
        text.Contains("mDNS", StringComparison.Ordinal)
        || text.Contains("attempt ", StringComparison.OrdinalIgnoreCase)
        || text.Contains("Exception", StringComparison.Ordinal)
        || text.Contains("HRESULT", StringComparison.OrdinalIgnoreCase)
        || text.Contains("Waking relay", StringComparison.OrdinalIgnoreCase)
        || text.Contains("WebSocket", StringComparison.OrdinalIgnoreCase);

    private void ClearError()
    {
        _errorRaw = "";
        ErrorTitle = "";
        ErrorDetail = "";
    }

    public async Task<TransferRow?> SendFileAsync(string path, int batchIndex = 0, int batchCount = 0)
    {
        if (_node is null)
        {
            return null;
        }

        var fileName = Path.GetFileName(path);
        if (fileName.EndsWith(".apk", StringComparison.OrdinalIgnoreCase))
        {
            DetailText = "Sending an app update. After it arrives, the phone updates the app in place when the signatures match, or offers Clean replace. The save file is not deleted.";
        }

        var row = BeginRow(fileName, token => _node.SendFileAsync(path, token, batchIndex, batchCount));
        await RunRowAsync(row);
        return row;
    }

    private void SetManual(string value) => _manualHost = value;

    private async Task ShowQrAsync()
    {
        if (_node is null)
        {
            return;
        }

        WebHint = "";
        _node.EnablePairingWindow(TimeSpan.FromMinutes(2));
        PaintHomeQr();
        StatusText = "Waiting to pair";
        DetailText = "On the phone, choose Pair with computer and scan this code, or type " + ShortCodeText + ". The code is the public key, not a private key.";
        RefreshStatus();
        await Task.CompletedTask;
    }

    private async Task EnlargeQrAsync()
    {
        if (_node is null)
        {
            return;
        }

        PaintHomeQr();
        if (PairingEnlarge.MovesRelay(_node.IsPaired) && !_node.PairingWindowOpen)
        {
            _node.EnablePairingWindow(TimeSpan.FromMinutes(2));
            StatusText = "Waiting to pair";
            DetailText = "On the phone, choose Pair with computer and scan this code, or type " + ShortCodeText + ". The code is the public key, not a private key.";
        }

        await _window.ShowLargeQrAsync(QrImage);
    }

    private void PaintHomeQr()
    {
        if (_node is null)
        {
            return;
        }

        var payload = _node.ExportQr();
        QrText = payload;
        ShortCodeText = ShortCode.Format(_node.Identity.Fingerprint);
        QrImage = RenderQr(payload);
        HasQr = true;
    }

    private async Task OpenOnWebAsync()
    {
        await ShowQrAsync();
        WebHint = "On the iPhone, open https://flipsend.stellartimelock.com and scan this code. You can paste the code if the camera is unavailable. The page encrypts the file. The relay does not see it.";
    }

    private void CastToTv()
    {
        CastStatus = WindowsIntegration.OpenCastToTv();
    }

    private async Task PairFromCodeAsync()
    {
        var code = await _window.AskForTextAsync("Pair from code", "Paste the pairing code from the phone.");
        if (string.IsNullOrWhiteSpace(code) || _node is null)
        {
            return;
        }

        try
        {
            var trimmed = code.Trim();
            if (ShortCode.LooksLike(trimmed))
            {
                await _node.PairFromShortCodeAsync(trimmed, _appCts.Token);
            }
            else
            {
                await _node.PairFromQrAsync(trimmed, _appCts.Token);
            }

            PaintHomeQr();
            RefreshStatus();
        }
        catch (RelayQuotaException ex)
        {
            DetailText = ex.Message;
        }
        catch (RelayWakeException ex)
        {
            DetailText = ex.Message;
        }
        catch (Exception ex)
        {
            ReportError(ex.Message);
        }
    }

    private string? EffectiveRelayUrl() => string.IsNullOrWhiteSpace(_relayUrl) ? null : _relayUrl.Trim();

    private static string PlanSentence(PeerRecord peer)
    {
        if (string.IsNullOrWhiteSpace(peer.PhonePlan))
        {
            return " Relay data follows the plan on your phone. This PC is included.";
        }

        var usage = peer.PhoneRelayLimit is > 0 && peer.PhoneRelayUsed is not null
            ? " " + FairUse.Label(peer.PhoneRelayUsed.Value, peer.PhoneRelayLimit.Value) + ". " + FairUse.ResetsOnTheFirst
            : "";
        return " Relay plan: " + peer.PhonePlan + "." + usage + " This PC is included.";
    }

    private async Task UnpairAsync()
    {
        if (_node is null || !_node.IsPaired)
        {
            return;
        }

        var node = _node;
        var ran = await PhoneDisconnect.TryAsync(
            () => _window.ConfirmAsync(PhoneDisconnect.Title, PhoneDisconnect.Body, PhoneDisconnect.Action),
            () =>
            {
                PhoneDisconnect.BackupPeerFiles(AppPaths.DataDirectory, DateTimeOffset.UtcNow);
                node.Unpair();
            });
        if (!ran)
        {
            return;
        }

        await ShowQrAsync();
    }

    private async Task RotateAsync()
    {
        if (_node is null)
        {
            return;
        }

        // Re-pair needed (the phone really rejected this PC's key): one tap straight to the QR. The current pairing still stays until the phone confirms.
        if (!_node.RepairNeeded && !await _window.ConfirmAsync("Re-scan / re-pair", "Create a new device key. The current pairing stays until the phone confirms the new one. If that does not finish, this PC keeps the current pairing.", "Re-scan / re-pair"))
        {
            return;
        }

        _node.RotateIdentity();
        ShowSettings = false;
        await ShowQrAsync();
        await _window.ShowLargeQrAsync(QrImage);
    }

    private async Task SendTextAsync()
    {
        if (_node is null || string.IsNullOrWhiteSpace(OutgoingText))
        {
            return;
        }

        var text = OutgoingText;
        var row = BeginRow("Text", token => _node.SendTextAsync(text, token));
        OutgoingText = "";
        await RunRowAsync(row);
    }

    private async Task SendClipboardAsync()
    {
        if (_node is null)
        {
            return;
        }

        var text = await _window.ReadClipboardAsync();
        if (string.IsNullOrWhiteSpace(text))
        {
            DetailText = "The clipboard is empty.";
            return;
        }

        var row = BeginRow("Clipboard", token => _node.SendTextAsync(text, token));
        await RunRowAsync(row);
    }

    private async Task ChooseFileAsync()
    {
        var path = await _window.PickFileAsync();
        if (!string.IsNullOrWhiteSpace(path))
        {
            await SendFileAsync(path);
        }
    }

    private async Task ChooseFolderAsync()
    {
        var path = await _window.PickFolderAsync("Choose a folder to send");
        if (!string.IsNullOrWhiteSpace(path))
        {
            await SendFolderAsync(path);
        }
    }

    public async Task SendFolderAsync(string path)
    {
        if (_node is null)
        {
            return;
        }

        var row = BeginRow(Path.GetFileName(path.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)), token => _node.SendFolderAsync(path, token));
        await RunRowAsync(row);
    }

    private async Task SendApkAsync()
    {
        var path = await _window.PickApkAsync();
        if (string.IsNullOrWhiteSpace(path))
        {
            return;
        }

        if (!path.EndsWith(".apk", StringComparison.OrdinalIgnoreCase))
        {
            DetailText = "Choose an APK file. The phone checks its save file, then updates the app in place or offers Clean replace.";
            return;
        }

        await SendFileAsync(path);
    }

    private async Task ChooseInboxAsync()
    {
        var path = await _window.PickFolderAsync("Choose the folder for received files");
        if (string.IsNullOrWhiteSpace(path) || _node is null)
        {
            return;
        }

        _inboxDirectory = path;
        _node.SetInbox(path);
        SaveSettings();
        Raise(nameof(InboxText));
    }

    private async Task RefreshUsbAsync()
    {
        _adbPath = LocateAdb();
        var status = await _adb.DetectAsync(_adbPath, _appCts.Token);
        _usbDevice = status.Devices.FirstOrDefault(device => device.State == "device");
        var deviceText = _usbDevice is null ? "No authorized device." : "Device " + _usbDevice.Serial + " is authorized.";
        UsbStatus = status.Message + " " + deviceText;
        RefreshStatus();
    }

    private async Task UsbSendAsync()
    {
        if (!await _window.ConfirmAsync("Send over USB", "adb will push the file you choose to Download/StellarSend on the phone.", "Continue"))
        {
            return;
        }

        var path = await _window.PickFileAsync();
        if (string.IsNullOrWhiteSpace(path) || _usbDevice is null || _adbPath is null)
        {
            UsbStatus = "Choose an authorized device and a file first.";
            return;
        }

        var result = await _adb.PushAsync(_adbPath, _usbDevice.Serial, path, userConfirmed: true, null, _appCts.Token);
        UsbStatus = result.Message ?? result.State.ToString();
    }

    private async Task UsbPullAsync()
    {
        if (!await _window.ConfirmAsync("Pull phone outbox", "adb will copy /sdcard/Android/data/org.stellartimelock.stellarsend/files/stellar-outbox into the inbox.", "Continue"))
        {
            return;
        }

        if (_usbDevice is null || _adbPath is null || _node is null)
        {
            UsbStatus = "Connect a phone with USB debugging authorized, then refresh.";
            return;
        }

        var result = await _adb.PullAsync(
            _adbPath,
            _usbDevice.Serial,
            "/sdcard/Android/data/org.stellartimelock.stellarsend/files/stellar-outbox",
            _node.InboxDirectory,
            userConfirmed: true,
            null,
            _appCts.Token);
        UsbStatus = result.Message ?? result.State.ToString();
    }

    private TransferRow BeginRow(string title, Func<CancellationToken, Task<TransferResult>> action)
    {
        var row = new TransferRow { Title = title, Detail = "Starting", Section = "Pending", Action = action };
        row.CancelCommand = new RelayCommand(() =>
        {
            row.Cancel();
            if (_waitingForPhone.Remove(row))
            {
                row.Detail = "Cancelled";
                row.CanCancel = false;
            }
        });
        row.RetryCommand = new RelayCommand(async () => await RunRowAsync(row));
        OnUi(() =>
        {
            Transfers.Insert(0, row);
            Raise(nameof(HasTransfers));
        });
        return row;
    }

    private async Task RunRowAsync(TransferRow row)
    {
        row.CanCancel = true;
        row.CanRetry = false;
        row.Arm();
        var waiting = false;
        try
        {
            var result = await row.Action(row.Token);
            row.Detail = result.State switch
            {
                TransferState.Succeeded => "Sent",
                TransferState.Cancelled => "Cancelled",
                _ => PlainError.ShortTransfer(result.Message ?? "Failed")
            };
            row.Percent = result.State == TransferState.Succeeded ? 100 : row.Percent;
            row.CanRetry = result.State is TransferState.Failed or TransferState.Cancelled;
            if (result.State is not TransferState.Succeeded)
            {
                _log.Error(row.Detail);
            }
        }
        catch (RelayQuotaException ex)
        {
            row.Detail = ex.Message;
            row.CanRetry = false;
            _log.Error($"{ex.GetType().Name}: {row.Detail}");
        }
        catch (RelayWakeException ex) when (RelayWake.IsPhoneOffline(ex.Message) && !row.Token.IsCancellationRequested)
        {
            // Pairing is fine. Keep the send and try again when the phone is back.
            row.Detail = PlainError.PhoneOfflineRow;
            row.CanRetry = true;
            waiting = true;
            _log.Warn($"Phone is offline. Queued \"{row.Title}\" to send when the phone is back.");
        }
        catch (RelayWakeException ex)
        {
            row.Detail = PlainError.ShortTransfer(ex.Message);
            row.CanRetry = true;
            _log.Error($"{ex.GetType().Name}: {ex.Message}");
        }
        catch (Exception ex)
        {
            row.Detail = PlainError.ShortTransfer(ex.Message);
            row.CanRetry = true;
            _log.Error($"{ex.GetType().Name}: {ex.Message}");
        }
        finally
        {
            if (waiting)
            {
                if (!_waitingForPhone.Contains(row))
                {
                    _waitingForPhone.Add(row);
                }

                _offlineMisses++;
                _nextOfflineTry = DateTimeOffset.UtcNow + RelayWake.OfflineRetryDelay(_offlineMisses);
            }
            else
            {
                _waitingForPhone.Remove(row);
                _offlineMisses = 0;
            }

            row.CanCancel = waiting;
            row.ShowProgress = false;
            row.Section = waiting ? "Pending" : "Finished";
            OnUi(PaintMeta);
        }
    }

    /// <summary>Sends that hit an offline phone go again, oldest first, while FlipSend is open.</summary>
    private async Task RetryWaitingAsync()
    {
        if (_offlineRetrying || _node is null || _waitingForPhone.Count == 0 || _node.RepairNeeded)
        {
            return;
        }

        _offlineRetrying = true;
        try
        {
            while (_waitingForPhone.Count > 0)
            {
                var row = _waitingForPhone[0];
                _log.Info($"Retrying \"{row.Title}\" now that the phone may be back.");
                row.Detail = "Sending";
                await RunRowAsync(row);
                if (_waitingForPhone.Contains(row))
                {
                    break;
                }
            }
        }
        finally
        {
            _offlineRetrying = false;
        }
    }

    private void OnProgress(TransferProgress progress)
    {
        OnUi(() =>
        {
            if (!RemoteDesktopSession.ShowsTransferBar(progress.Detail) ||
                string.Equals(progress.Name, RemoteControl.ActiveLine, StringComparison.Ordinal))
            {
                return;
            }

            var row = Transfers.FirstOrDefault(item => item.TransferId == progress.TransferId || item.TransferId == Guid.Empty);
            if (row is null || row.Title == RemoteControl.ActiveLine)
            {
                return;
            }

            row.TransferId = progress.TransferId;
            row.Title = progress.Name;
            row.Detail = ProgressText.Format(progress.BytesTransferred, progress.TotalBytes, progress.BytesPerSecond, progress.SecondsRemaining, progress.State, progress.Detail);
            row.Percent = progress.TotalBytes <= 0 ? 0 : (int)Math.Clamp(progress.BytesTransferred * 100 / progress.TotalBytes, 0, 100);
            row.ShowProgress = progress.State is TransferState.InProgress or TransferState.AwaitingConsent;
            row.Section = row.ShowProgress ? "In progress" : "Finished";
            PaintMeta();
        });
    }

    private void OnTextReceived(string text)
    {
        OnUi(async () =>
        {
            await PresentReceivedTextAsync("Text received", text);
            DetailText = TransferAttention.OpensReceivedText(_bringWindowToFrontOnTransfer)
                ? "Copied the received text to the clipboard. Select it in the window, or tap Copy."
                : "Copied the received text. Open FlipSend to read it.";
        });
    }

    private async Task OnFileReceivedAsync(string path)
    {
        var name = Path.GetFileName(path);
        if (name.EndsWith(".apk", StringComparison.OrdinalIgnoreCase))
        {
            DetailText = $"Saved {name}. On the phone, a matching signature updates the app in place. Clean replace is there if you want it, and the save file is not deleted.";
            NoticeQuiet(TransferAttention.Received(name));
            return;
        }

        if (ReceivedText.TryRead(path, out var text))
        {
            await PresentReceivedTextAsync(name, text);
            DetailText = TransferAttention.OpensReceivedText(_bringWindowToFrontOnTransfer)
                ? "Saved " + name + ". Select the text, or tap Copy."
                : "Saved " + name + ". Open FlipSend to read it.";
            return;
        }

        DetailText = "Saved " + name;
        NoticeQuiet(TransferAttention.Received(name));
    }

    private async Task PresentReceivedTextAsync(string title, string text)
    {
        RememberReceivedText(title, text);
        await _window.WriteClipboardAsync(text);
        if (TransferAttention.OpensReceivedText(_bringWindowToFrontOnTransfer))
        {
            await _window.ShowTextAsync(title, text);
            return;
        }

        NoticeQuiet(TransferAttention.Received(title));
    }

    private void NoticeQuiet(string message)
    {
        if (TransferAttention.BringsWindowForward(_bringWindowToFrontOnTransfer))
        {
            return;
        }

        _log.Info("Staying in the background. " + message);
        QuietNotice.Show(message);
    }

    private async Task CopyReceivedAsync()
    {
        if (string.IsNullOrEmpty(_receivedText))
        {
            return;
        }

        await _window.WriteClipboardAsync(_receivedText);
        DetailText = "Copied to the clipboard.";
    }

    private void RememberReceivedText(string title, string text)
    {
        _receivedText = text;
        ReceivedPreview = text.Length > 240 ? text[..240] + "…" : text;
        Raise(nameof(HasReceivedText));
        var row = new TransferRow
        {
            Title = title,
            Detail = "Received. Tap Copy on the card above, or on this row.",
            Percent = 100,
            CanCopy = true,
            CanOpen = true
        };
        row.CopyCommand = new RelayCommand(async () =>
        {
            await _window.WriteClipboardAsync(text);
            DetailText = "Copied to the clipboard.";
        });
        row.OpenCommand = new RelayCommand(async () => await _window.ShowTextAsync(title, text));
        Transfers.Insert(0, row);
        Raise(nameof(HasTransfers));
    }

    private void SyncAlwaysAllow()
    {
        var id = _node?.Peer?.DeviceId.ToString("D");
        var on = !string.IsNullOrWhiteSpace(id) && AlwaysAllow.Contains(_alwaysIds, id);
        if (_alwaysAllowThisPhone == on)
        {
            return;
        }

        _loadingSettings = true;
        try
        {
            AlwaysAllowThisPhone = on;
        }
        finally
        {
            _loadingSettings = false;
        }
    }

    private void RefreshStatus()
    {
        if (_node is null)
        {
            return;
        }

        Peers.Clear();
        var paired = _node.IsPaired;
        var listening = _node.BoundPort > 0;
        var lastError = _node.LastError;
        var face = ConnectionState.Face(new ConnectionInput(
            paired,
            listening,
            _node.PairingWindowOpen,
            paired && !string.IsNullOrWhiteSpace(NetworkInfo.TryGetLanIpv4()),
            string.Equals(_node.PathLabel, TransferPath.Direct, StringComparison.Ordinal),
            _node.DirectLinkUp,
            _node.DirectLinkUp || (paired && !string.IsNullOrWhiteSpace(_relayUrl) && !string.Equals(_node.PathLabel, TransferPath.Direct, StringComparison.Ordinal) && !_node.DirectLinkMissed),
            string.Equals(_node.PathLabel, TransferPath.Relay, StringComparison.Ordinal),
            paired && !string.IsNullOrWhiteSpace(_relayUrl),
            string.Equals(_node.PathLabel, TransferPath.Usb, StringComparison.Ordinal),
            _usbDevice is not null,
            lastError,
            _node.DirectLinkMissed,
            string.Equals(_node.PathLabel, TransferPath.Bluetooth, StringComparison.Ordinal),
            paired && _node.BluetoothRadioOn,
            false));
        if (paired)
        {
            var peer = _node.Peer!;
            var pairedOn = UsDate.Format(peer.PairedAt);
            Peers.Add(new PeerRow(peer.Name, "Paired " + pairedOn, UnpairCommand));
            HasPeer = true;
            WhoText = "Paired with " + peer.Name;
            SyncAlwaysAllow();
        }
        else
        {
            HasPeer = false;
            WhoText = "No phone paired yet.";
            SyncAlwaysAllow();
        }

        StatusText = HomeStatus.Line(paired, face.Connected, _node.PairingWindowOpen, face.PathPill, lastError, NetworkInfo.VpnTunnelActive());
        DetailText = face.Hint;
        HintText = face.Hint;
        ConnectionLinked = face.Connected;
        PathText = face.PathPill;
        PathPill = face.PathPill;
        HeaderStatus = StatusText;
        PairingOpen = _node.PairingWindowOpen;
        ConnectionLine = HomeStatus.Connection(paired, listening, face.Active, PairingOpen, _node.RepairNeeded, _node.PhoneOffline);
        var phoneOffline = _node.PhoneOffline;
        if (_phoneWasOffline && !phoneOffline && _waitingForPhone.Count > 0)
        {
            _ = RetryWaitingAsync();
        }

        _phoneWasOffline = phoneOffline;
        RememberDetails(lastError);
        PaintMeta();
        if (listening && (ConnectionLinked || paired) && PlainError.IsPortBusy(_errorRaw))
        {
            ClearError();
        }
        else if (listening && PlainError.IsPortBusy(lastError))
        {
            ClearError();
        }
        else if (!string.IsNullOrWhiteSpace(lastError) && PlainError.ShouldBanner(lastError) && !ConnectionLinked)
        {
            ShowError(lastError);
        }
        else if (!PlainError.ShouldBanner(lastError))
        {
            ClearError();
            RememberDetails(lastError);
        }

        if (QrImage is null)
        {
            PaintHomeQr();
        }
    }

    private void PaintMeta()
    {
        var sending = Transfers.FirstOrDefault(row => row.ShowProgress);
        var meta = HomeStatus.Meta(
            HasPeer,
            sending is not null,
            sending is null ? null : sending.Title + " · " + sending.Percent + "%");
        var vpn = VpnStatus.Describe(
            NetworkInfo.VpnOn(),
            NetworkInfo.TryGetLanIpv4() is not null,
            string.Equals(PathPill, TransferPath.Relay, StringComparison.Ordinal));
        MetaLine = string.IsNullOrWhiteSpace(vpn) ? meta : vpn + " · " + meta;
    }

    internal void ApplyHomeState(
        string headerStatus,
        string metaLine,
        bool linked,
        string who,
        string? errorMessage,
        IReadOnlyList<TransferRow>? rows = null,
        bool pairingOpen = false)
    {
        HeaderStatus = headerStatus;
        PairingOpen = pairingOpen;
        MetaLine = metaLine;
        ConnectionLinked = linked;
        WhoText = who;
        HasPeer = linked || !string.Equals(who, "No phone paired yet.", StringComparison.Ordinal);
        StatusText = headerStatus;
        ConnectionLine = HomeStatus.Connection(
            HasPeer,
            listening: true,
            linked ? (string.Equals(headerStatus, HomeStatus.PhoneAway, StringComparison.Ordinal) ? RouteKind.Relay : RouteKind.Lan) : RouteKind.Offline,
            pairingOpen);
        if (string.IsNullOrWhiteSpace(errorMessage))
        {
            ClearError();
        }
        else
        {
            ShowError(errorMessage);
        }

        Transfers.Clear();
        if (rows is not null)
        {
            foreach (var row in rows)
            {
                Transfers.Add(row);
            }
        }

        Raise(nameof(HasTransfers));
        PaintMeta();
    }

    private static string HomeHeadline(bool paired, bool listening, bool pairingWindowOpen, string? lastError)
    {
        if (IsDrop(lastError))
        {
            return "Disconnected";
        }

        if (paired && listening)
        {
            return "Connected";
        }

        if (pairingWindowOpen)
        {
            return "Waiting to pair";
        }

        return "Not connected";
    }

    private static bool IsDrop(string? lastError)
    {
        var text = lastError?.Trim() ?? "";
        if (text.Length == 0)
        {
            return false;
        }

        if (text.Contains("waking", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        return text.Contains("disconnect", StringComparison.OrdinalIgnoreCase)
            || text.Contains("hung up", StringComparison.OrdinalIgnoreCase)
            || text.Contains("certificate_unknown", StringComparison.OrdinalIgnoreCase);
    }

    internal static string PathPillOf(string? path)
    {
        var text = path?.Trim() ?? "";
        if (text.Contains("Direct", StringComparison.OrdinalIgnoreCase))
        {
            return "Direct";
        }

        if (text.Contains("relay", StringComparison.OrdinalIgnoreCase))
        {
            return "Relay";
        }

        return "";
    }

    private void OpenPhoneScreen()
    {
        if (HasPhoneScreen)
        {
            OnPhoneScreenStarted();
            return;
        }

        DetailText = "On the phone, tap Share screen.";
    }

    private void OpenReceived()
    {
        if (HasReceivedText)
        {
            _ = _window.ShowTextAsync("Received text", _receivedText);
            return;
        }

        var dir = _node?.InboxDirectory ?? _inboxDirectory;
        if (string.IsNullOrWhiteSpace(dir) || !Directory.Exists(dir))
        {
            DetailText = InboxText;
            return;
        }

        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = dir,
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            DetailText = ex.Message;
        }
    }

    private async Task AcceptHandedFilesAsync()
    {
        while (!_appCts.IsCancellationRequested)
        {
            foreach (var path in SendQueue.Drain(AppPaths.QueueDirectory, _log, () => OnUi(() => _window.ShowFromTray())))
            {
                lock (_handedGate)
                {
                    _handedOff.Enqueue(path);
                }
            }

            try
            {
                await Task.Delay(200, _appCts.Token);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private async Task SendHandedFilesAsync()
    {
        while (!_appCts.IsCancellationRequested)
        {
            var batch = DrainHanded();
            if (batch.Count == 0)
            {
                try
                {
                    await Task.Delay(200, _appCts.Token);
                }
                catch (OperationCanceledException)
                {
                    break;
                }

                continue;
            }

            try
            {
                await Task.Delay(300, _appCts.Token);
            }
            catch (OperationCanceledException)
            {
                break;
            }

            batch.AddRange(DrainHanded());
            var ordered = ApkSendOrder.SelfLast(batch);
            var count = ordered.Count > 1 ? ordered.Count : 0;
            if (TransferAttention.BringsWindowForward(_bringWindowToFrontOnTransfer))
            {
                OnUi(() => _window.ShowFromTray());
            }
            else
            {
                NoticeQuiet(ordered.Count == 1
                    ? TransferAttention.Sending(Path.GetFileName(ordered[0]))
                    : TransferAttention.SendingCount(ordered.Count));
            }

            for (var i = 0; i < ordered.Count; i++)
            {
                try
                {
                    var row = await SendFileAsync(ordered[i], count > 1 ? i + 1 : 0, count);
                    if (row is not null)
                    {
                        NoticeQuiet(TransferAttention.Finished(row.Title, row.Detail));
                    }
                }
                catch (Exception ex)
                {
                    _log.Error(ex.Message);
                    NoticeQuiet(TransferAttention.CouldNotSend(Path.GetFileName(ordered[i])));
                }
            }
        }
    }

    private List<string> DrainHanded()
    {
        var batch = new List<string>();
        lock (_handedGate)
        {
            while (_handedOff.Count > 0)
            {
                batch.Add(_handedOff.Dequeue());
            }
        }

        return batch;
    }

    private static Bitmap RenderQr(string payload)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data);
        var bytes = png.GetGraphic(8);
        return new Bitmap(new MemoryStream(bytes));
    }

    private static string? LocateAdb()
    {
        var environment = Environment.GetEnvironmentVariables().Cast<System.Collections.DictionaryEntry>()
            .ToDictionary(entry => (string)entry.Key, entry => entry.Value as string, StringComparer.OrdinalIgnoreCase);
        foreach (var candidate in AdbLocator.Candidates(environment, Environment.GetEnvironmentVariable("PATH") ?? ""))
        {
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        return null;
    }

    private void LoadSettings()
    {
        var path = Path.Combine(AppPaths.DataDirectory, "settings.json");
        var registryOn = false;
        try
        {
            registryOn = WindowsIntegration.StartupOn();
        }
        catch (Exception)
        {
            registryOn = false;
        }

        if (!File.Exists(path))
        {
            _startWithWindows = StartupPreference.Resolve(saved: true, registryOn);
            _loadingSettings = false;
            if (_startWithWindows)
            {
                try
                {
                    WindowsIntegration.EnableStartup();
                }
                catch (Exception)
                {
                }

                SaveSettings();
            }

            Raise(nameof(StartWithWindows));
            return;
        }

        try
        {
            var settings = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(path));
            if (settings is not null)
            {
                SetManual(settings.ManualHost ?? "");
                _relayUrlSet = settings.RelayUrlSet;
                _relayUrl = settings.RelayUrlSet ? settings.RelayUrl ?? "" : string.IsNullOrWhiteSpace(settings.RelayUrl) ? RelayPlans.DefaultRelay : settings.RelayUrl;
                _inboxDirectory = settings.InboxDirectory;
                _startWithWindows = StartupPreference.Resolve(settings.StartWithWindows, registryOn);
                _bringWindowToFrontOnTransfer = settings.BringWindowToFrontOnTransfer;
                if (_startWithWindows && !registryOn)
                {
                    try
                    {
                        WindowsIntegration.EnableStartup();
                    }
                    catch (Exception)
                    {
                    }
                }
                _shellMenu = settings.ShellMenu;
                _terminalEnabled = settings.TerminalEnabled;
                _terminalAllowlist = settings.TerminalAllowlist ?? true;
                _remoteControlEnabled = settings.RemoteControlEnabled;
                _alwaysIds = settings.RemoteDesktopAlways?.Where(item => !string.IsNullOrWhiteSpace(item)).Select(item => item.Trim()).ToList() ?? [];
                _terminalAllow.Clear();
                if (settings.TerminalAllow is not null)
                {
                    _terminalAllow.AddRange(settings.TerminalAllow.Where(item => !string.IsNullOrWhiteSpace(item)).Select(item => item.Trim()));
                }

                Raise(nameof(ManualHost));
                Raise(nameof(RelayUrl));
                Raise(nameof(StartWithWindows));
                Raise(nameof(BringWindowToFrontOnTransfer));
                Raise(nameof(ShellMenu));
                Raise(nameof(TerminalEnabled));
                Raise(nameof(TerminalAllowlist));
                Raise(nameof(RemoteControlEnabled));
                Raise(nameof(CanStartPhoneControl));
                RefreshAllowRows();
            }
        }
        catch (JsonException)
        {
            DetailText = "The settings save could not be read. The file was left in place.";
        }

        _loadingSettings = false;
        if (_startWithWindows)
        {
            SaveSettings();
        }
    }

    private void SaveSettings(bool confirmed = false)
    {
        if (_loadingSettings)
        {
            return;
        }

        var path = Path.Combine(AppPaths.DataDirectory, "settings.json");
        Directory.CreateDirectory(AppPaths.DataDirectory);
        var bytes = JsonSerializer.SerializeToUtf8Bytes(new AppSettings
        {
            ManualHost = _manualHost,
            RelayUrl = EffectiveRelayUrl(),
            RelayUrlSet = _relayUrlSet,
            InboxDirectory = _inboxDirectory,
            StartWithWindows = _startWithWindows,
            BringWindowToFrontOnTransfer = _bringWindowToFrontOnTransfer,
            ShellMenu = _shellMenu,
            TerminalEnabled = _terminalEnabled,
            TerminalAllowlist = _terminalAllowlist,
            TerminalAllow = _terminalAllow.ToList(),
            RemoteControlEnabled = _remoteControlEnabled,
            RemoteDesktopAlways = _alwaysIds.ToList()
        });
        var decision = SaveStore.Commit(path, bytes, confirmed);
        if (decision.Kind == OverwriteKind.NeedsConfirm)
        {
            OnUi(async () =>
            {
                var replace = await _window.ConfirmAsync(
                    "Save settings?",
                    $"{decision.Reason} Current items: {decision.ExistingItems}. New items: {decision.IncomingItems}.",
                    "Replace");
                if (replace)
                {
                    SaveSettings(confirmed: true);
                }
                else
                {
                    DetailText = "Settings were not saved.";
                }
            });
        }
    }

    private void AddAllowedCommand()
    {
        var command = _terminalDraft.Trim();
        if (TerminalPolicy.RejectReason(command) is not null)
        {
            return;
        }

        if (_terminalAllow.Any(item => string.Equals(item, command, StringComparison.OrdinalIgnoreCase)))
        {
            return;
        }

        _terminalAllow.Add(command);
        TerminalDraft = "";
        RefreshAllowRows();
        PushTerminal();
        SaveSettings();
    }

    private void RemoveAllowedCommand(string command)
    {
        _terminalAllow.RemoveAll(item => string.Equals(item, command, StringComparison.OrdinalIgnoreCase));
        RefreshAllowRows();
        PushTerminal();
        SaveSettings();
    }

    private void RefreshAllowRows()
    {
        TerminalAllowRows.Clear();
        foreach (var command in _terminalAllow)
        {
            var captured = command;
            TerminalAllowRows.Add(new AllowRow(captured, new RelayCommand(() => RemoveAllowedCommand(captured))));
        }

        Raise(nameof(HasTerminalAllow));
    }

    private void PushTerminal()
    {
        _node?.Terminal.Update(_terminalEnabled, _terminalAllowlist, _terminalAllow);
    }

    private void OnCompanion(ServiceRequest request, ServiceResult result)
    {
        if (request.Service != CompanionServices.RemoteBrowser && request.Service != CompanionServices.VaultClipboard)
        {
            return;
        }

        OnUi(() => RemoteStatus = result.Message);
    }

    private void OnPhoneScreenStarted()
    {
        HasPhoneScreen = true;
        PhoneScreenStatus = "Showing the paired phone.";
        if (_phoneScreen is null)
        {
            _phoneScreen = new PhoneScreenWindow { DataContext = this };
            _phoneScreen.Closed += (_, _) => _phoneScreen = null;
            _phoneScreen.Show(_window);
        }

        _window.ShowFromTray();
        _phoneScreen?.Activate();
        Raise(nameof(CanStartPhoneControl));
    }

    private void OnPhoneScreenFrame(byte[] jpeg)
    {
        try
        {
            using var stream = new MemoryStream(jpeg);
            PhoneScreenImage = new Bitmap(stream);
            HasPhoneScreen = true;
            PhoneScreenStatus = "Showing the paired phone.";
            if (_phoneScreen is null)
            {
                OnPhoneScreenStarted();
            }
        }
        catch (Exception)
        {
            // A bad frame must not stop the share.
        }
    }

    private void OnPhoneScreenStopped()
    {
        PhoneScreenStatus = "The phone stopped sharing.";
        HasPhoneScreen = PhoneScreenImage is not null;
        Raise(nameof(CanStartPhoneControl));
    }

    private void OnRemoteChanged()
    {
        BeingControlled = _node?.Remote.IsActive() == true && _node.Remote.From == RemoteControl.FromPhone;
        Raise(nameof(RemoteBanner));
        RemoteSessionOpen = _node?.Remote.State != RemoteSessionState.Idle;
        ControllingPhone = _node?.Remote.IsActive() == true && _node.Remote.From == RemoteControl.FromPc;
        Raise(nameof(CanStartPhoneControl));
        if (BeingControlled)
        {
            PhoneScreenStatus = RemoteControl.ActiveLine;
            DetailText = RemoteControl.ActiveLine;
        }
    }

    public void EnqueuePhoneInput(string kind, double x, double y, double x2 = 0, double y2 = 0, double dy = 0)
    {
        var session = _node?.Remote.SessionId;
        if (string.IsNullOrWhiteSpace(session) || !_controllingPhone)
        {
            return;
        }

        lock (_remoteGate)
        {
            if (_remoteOut.Count >= 40)
            {
                _remoteOut.Dequeue();
            }

            _remoteOut.Enqueue(new RemoteInput(kind, session, x, y, x2, y2, 0, dy));
        }
    }

    public void EnqueuePhoneText(string text)
    {
        var session = _node?.Remote.SessionId;
        if (string.IsNullOrWhiteSpace(session) || string.IsNullOrEmpty(text) || !_controllingPhone)
        {
            return;
        }

        lock (_remoteGate)
        {
            if (_remoteOut.Count >= 40)
            {
                _remoteOut.Dequeue();
            }

            _remoteOut.Enqueue(new RemoteInput("text", session, Text: text));
        }
    }

    public void EnqueuePhoneKey(string key)
    {
        var session = _node?.Remote.SessionId;
        if (string.IsNullOrWhiteSpace(session) || !_controllingPhone)
        {
            return;
        }

        lock (_remoteGate)
        {
            _remoteOut.Enqueue(new RemoteInput("key", session, Text: key.ToLowerInvariant()));
        }
    }

    private async Task ControlPhoneAsync()
    {
        if (_node is null)
        {
            return;
        }

        try
        {
            var asked = await _node.RequestRemoteAsync(RemoteControl.FromPc, _appCts.Token);
            if (!asked.Ok)
            {
                DetailText = asked.Message;
                return;
            }

            ControllingPhone = true;
            RemoteSessionOpen = true;
            PhoneScreenStatus = "You can tap and type on the phone.";
            _driveCts?.Cancel();
            _driveCts = new CancellationTokenSource();
            var token = _driveCts.Token;
            _ = Task.Run(async () =>
            {
                try
                {
                    await _node.DriveRemoteAsync(NextRemoteInput, token);
                }
                catch (Exception ex)
                {
                    OnUi(() => DetailText = ex.Message);
                }
                finally
                {
                    OnUi(() =>
                    {
                        ControllingPhone = false;
                        RemoteSessionOpen = _node?.Remote.State is RemoteSessionState.Pending or RemoteSessionState.Active;
                    });
                }
            }, token);
        }
        catch (Exception ex)
        {
            DetailText = ex.Message;
        }
    }

    private RemoteInput? NextRemoteInput()
    {
        lock (_remoteGate)
        {
            return _remoteOut.Count == 0 ? null : _remoteOut.Dequeue();
        }
    }

    private void StopRemote()
    {
        _driveCts?.Cancel();
        _node?.StopRemote();
        ControllingPhone = false;
        BeingControlled = false;
        RemoteSessionOpen = false;
        PhoneScreenStatus = HasPhoneScreen ? "Showing the paired phone." : PhoneScreenStatus;
    }

    private void OnUi(Action action)
    {
        if (Dispatcher.UIThread.CheckAccess())
        {
            action();
        }
        else
        {
            Dispatcher.UIThread.Post(action);
        }
    }

    private bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
        {
            return false;
        }

        field = value;
        Raise(name);
        return true;
    }

    private void Raise(string? name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

public sealed class TransferRow : INotifyPropertyChanged
{
    private string _title = "";
    private string _section = "";
    private string _detail = "";
    private int _percent;
    private bool _showProgress;
    private bool _canCancel;
    private bool _canRetry;

    public Guid TransferId { get; set; }
    public Func<CancellationToken, Task<TransferResult>> Action { get; set; } = _ => Task.FromResult(new TransferResult(TransferState.Failed, null, null, null, null, 0, false));
    public ICommand CancelCommand { get; set; } = new RelayCommand(() => { });
    public ICommand RetryCommand { get; set; } = new RelayCommand(() => { });
    public ICommand CopyCommand { get; set; } = new RelayCommand(() => { });
    public ICommand OpenCommand { get; set; } = new RelayCommand(() => { });
    private CancellationTokenSource? _cancel;
    public CancellationToken Token => _cancel?.Token ?? CancellationToken.None;
    public void Arm() => _cancel = new CancellationTokenSource();

    public string Title { get => _title; set => Set(ref _title, value); }
    public string Section { get => _section; set => Set(ref _section, value); }
    public string Detail { get => _detail; set => Set(ref _detail, value); }
    public int Percent { get => _percent; set => Set(ref _percent, value); }
    public bool ShowProgress { get => _showProgress; set => Set(ref _showProgress, value); }
    public bool CanCancel { get => _canCancel; set => Set(ref _canCancel, value); }
    public bool CanRetry { get => _canRetry; set => Set(ref _canRetry, value); }
    public bool CanCopy { get; set; }
    public bool CanOpen { get; set; }

    public void Cancel() => _cancel?.Cancel();

    public event PropertyChangedEventHandler? PropertyChanged;

    private void Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
        {
            return;
        }

        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}

public sealed class PeerRow
{
    public PeerRow(string name, string detail, ICommand unpair)
    {
        Name = name;
        Detail = detail;
        UnpairCommand = unpair;
    }

    public string Name { get; }
    public string Detail { get; }
    public ICommand UnpairCommand { get; }
}

public sealed class AllowRow
{
    public AllowRow(string command, ICommand remove)
    {
        Command = command;
        RemoveCommand = remove;
    }

    public string Command { get; }
    public ICommand RemoveCommand { get; }
}

public sealed class AppSettings
{
    public string? ManualHost { get; set; }
    public string? RelayUrl { get; set; }
    public bool RelayUrlSet { get; set; }
    public string? InboxDirectory { get; set; }
    public bool StartWithWindows { get; set; }
    public bool BringWindowToFrontOnTransfer { get; set; }
    public bool ShellMenu { get; set; }
    public bool TerminalEnabled { get; set; }
    public bool? TerminalAllowlist { get; set; }
    public List<string>? TerminalAllow { get; set; }
    [JsonPropertyName("remote_control")]
    public bool RemoteControlEnabled { get; set; }
    [JsonPropertyName("remote_desktop_always")]
    public List<string>? RemoteDesktopAlways { get; set; }
}

public sealed class MemoryProtector : IKeyProtector
{
    public byte[] Protect(byte[] plaintext) =>
        throw new PlatformNotSupportedException("Pairing keys are stored with Windows DPAPI. Run FlipSend on Windows to pair.");

    public byte[] Unprotect(byte[] ciphertext) => Protect(ciphertext);
}
