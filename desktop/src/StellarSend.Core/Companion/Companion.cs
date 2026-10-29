using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Serialization;
using StellarSend.Core.Protocol;

namespace StellarSend.Core.Companion;

public static class CompanionServices
{
    public const string VaultClipboard = "vault.clipboard";
    public const string RemoteBrowser = "remote.browser";
    public const string Terminal = "terminal";
    public const string ScreenShare = "screen.share";
    public const string RemoteControl = "remote.control";
    public const string InstallResult = "install.result";
    public const int ClearAfterSeconds = 45;
    public const int MemoClearAfterSeconds = 30;
    public const int MaxPasswordChars = 1024;
    public const int MaxUrlChars = 2048;
    public const int MaxFrameBytes = 200_000;
}

public sealed record ServiceRequest(string Id, string Service, string Op, JsonElement Body);

public sealed record ServiceResult(
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("ok")] bool Ok,
    [property: JsonPropertyName("code")] string Code,
    [property: JsonPropertyName("message")] string Message)
{
    public static ServiceResult Done(string id, string message) => new(id, true, "ok", message);

    public static ServiceResult Fail(string id, string code, string message) => new(id, false, code, message);
}

public static class ServiceCodec
{
    public static ServiceRequest ParseRequest(byte[] payload)
    {
        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(payload);
        }
        catch (JsonException)
        {
            throw new ProtocolException("Unreadable companion call.");
        }

        using (document)
        {
            var root = document.RootElement;
            var id = Required(root, "id");
            var service = Required(root, "service");
            var op = Required(root, "op");
            if (!Guid.TryParse(id, out _))
            {
                throw new ProtocolException("Companion call id is invalid.");
            }

            if (!IsToken(service, 64) || !IsToken(op, 32))
            {
                throw new ProtocolException("Companion call is invalid.");
            }

            if (!root.TryGetProperty("body", out var body) || body.ValueKind != JsonValueKind.Object)
            {
                throw new ProtocolException("Companion call is missing a body.");
            }

            return new ServiceRequest(id, service, op, body.Clone());
        }
    }

    public static ServiceResult ParseResult(byte[] payload) => ProtocolJson.Read<ServiceResult>(payload);

    public static string? ReadString(JsonElement body, string name)
    {
        if (!body.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.String)
        {
            return null;
        }

        return value.GetString();
    }

    public static int? ReadInt(JsonElement body, string name)
    {
        if (!body.TryGetProperty(name, out var value))
        {
            return null;
        }

        if (value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var number))
        {
            return number;
        }

        if (value.ValueKind == JsonValueKind.String && int.TryParse(value.GetString(), out var parsed))
        {
            return parsed;
        }

        return null;
    }

    private static string Required(JsonElement root, string name)
    {
        if (!root.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.String)
        {
            throw new ProtocolException("Companion call is invalid.");
        }

        return value.GetString() ?? "";
    }

    private static bool IsToken(string value, int max)
    {
        if (value.Length is 0 || value.Length > max)
        {
            return false;
        }

        foreach (var ch in value)
        {
            if (ch is not ((>= 'a' and <= 'z') or (>= '0' and <= '9') or '.'))
            {
                return false;
            }
        }

        return true;
    }
}

public interface ISecretClipboard
{
    void SetText(string text);
    string? GetText();
}

public interface IUserNotice
{
    void Show(string text);
}

public static class ClipboardPolicy
{
    public static bool TryAccept(string? password, out string error)
    {
        if (string.IsNullOrEmpty(password))
        {
            error = "Password is empty.";
            return false;
        }

        if (password.Length > CompanionServices.MaxPasswordChars)
        {
            error = "Password is too long.";
            return false;
        }

        error = "";
        return true;
    }

    public static bool ShouldClear(
        DateTimeOffset placedAt,
        DateTimeOffset now,
        string? current,
        string secret,
        TimeSpan? clearAfter = null)
    {
        var wait = clearAfter ?? TimeSpan.FromSeconds(CompanionServices.ClearAfterSeconds);
        if (now - placedAt < wait)
        {
            return false;
        }

        return string.Equals(current, secret, StringComparison.Ordinal);
    }
}

public sealed class ClipboardHandoff
{
    private readonly ISecretClipboard _clipboard;
    private readonly IUserNotice _notice;
    private string? _secret;
    private DateTimeOffset _placedAt;
    private TimeSpan _clearAfter = TimeSpan.FromSeconds(CompanionServices.ClearAfterSeconds);

    public ClipboardHandoff(ISecretClipboard clipboard, IUserNotice notice)
    {
        _clipboard = clipboard;
        _notice = notice;
    }

    public ServiceResult Put(string id, string? password, DateTimeOffset now, int? clearAfterSeconds = null)
    {
        if (!ClipboardPolicy.TryAccept(password, out var error))
        {
            return ServiceResult.Fail(id, ErrorCodes.BadRequest, error);
        }

        var seconds = ClampClearAfter(clearAfterSeconds);
        _clipboard.SetText(password!);
        _secret = password;
        _placedAt = now;
        _clearAfter = TimeSpan.FromSeconds(seconds);
        var message = $"Copied to the PC clipboard. It will clear in {seconds} seconds.";
        _notice.Show(message);
        return ServiceResult.Done(id, message);
    }

    public bool Tick(DateTimeOffset now)
    {
        var secret = _secret;
        if (secret is null)
        {
            return false;
        }

        if (!ClipboardPolicy.ShouldClear(_placedAt, now, _clipboard.GetText(), secret, _clearAfter))
        {
            if (now - _placedAt >= _clearAfter)
            {
                _secret = null;
            }

            return false;
        }

        _clipboard.SetText("");
        _secret = null;
        return true;
    }

    private static int ClampClearAfter(int? raw)
    {
        if (raw is null)
        {
            return CompanionServices.ClearAfterSeconds;
        }

        return Math.Clamp(raw.Value, 5, 300);
    }
}

public interface IBrowserSurface
{
    bool CanOpen { get; }
    bool CanView { get; }
    bool CanInput { get; }
    void Open(string url);
    byte[]? Capture();
    void Input(string kind, double x, double y, string? text);
}

public sealed class UrlOnlyBrowserSurface : IBrowserSurface
{
    public bool CanOpen => true;
    public bool CanView => false;
    public bool CanInput => false;

    public void Open(string url)
    {
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
    }

    public byte[]? Capture() => null;

    public void Input(string kind, double x, double y, string? text) =>
        throw new InvalidOperationException("This browser surface does not take input.");
}

public static class BrowserUrls
{
    public static bool TryNormalize(string? raw, out string absolute, out string error)
    {
        absolute = "";
        if (string.IsNullOrWhiteSpace(raw) || raw.Length > CompanionServices.MaxUrlChars || raw.Any(char.IsControl))
        {
            error = "Enter an http or https link.";
            return false;
        }

        if (!Uri.TryCreate(raw.Trim(), UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttps && uri.Scheme != Uri.UriSchemeHttp)
            || !string.IsNullOrEmpty(uri.UserInfo))
        {
            error = "Only http and https links can be opened. Desktop control is not available.";
            return false;
        }

        absolute = uri.AbsoluteUri;
        error = "";
        return true;
    }
}

public sealed record RemotePluginInfo(string Id, string Title, string Summary, bool Open, bool View, bool Input);

public sealed class RemoteBrowserPlugin
{
    public const string ViewUnavailable = "browser_view_unavailable";
    public const string InputUnavailable = "browser_input_unavailable";

    private readonly IBrowserSurface _surface;

    public RemoteBrowserPlugin(IBrowserSurface surface)
    {
        _surface = surface;
    }

    public RemotePluginInfo Describe() => new(
        CompanionServices.RemoteBrowser,
        "Remote Browser",
        "Opens a link in a browser on this PC. Live view and typing stay inside that browser surface. The desktop is not controlled.",
        _surface.CanOpen,
        _surface.CanView,
        _surface.CanInput);

    public ServiceResult Handle(ServiceRequest request)
    {
        return request.Op switch
        {
            "status" => ServiceResult.Done(request.Id, StatusLine()),
            "open" => Open(request),
            "frame" => Frame(request),
            "input" => Input(request),
            _ => ServiceResult.Fail(request.Id, ErrorCodes.NotImplemented, "That browser action is not available.")
        };
    }

    private ServiceResult Open(ServiceRequest request)
    {
        if (!_surface.CanOpen)
        {
            return ServiceResult.Fail(request.Id, ErrorCodes.NotImplemented, "Opening links is not available.");
        }

        if (!BrowserUrls.TryNormalize(ServiceCodec.ReadString(request.Body, "url"), out var url, out var error))
        {
            return ServiceResult.Fail(request.Id, ErrorCodes.BadRequest, error);
        }

        _surface.Open(url);
        return ServiceResult.Done(request.Id, "Opened on this PC.");
    }

    private ServiceResult Frame(ServiceRequest request)
    {
        if (!_surface.CanView)
        {
            return ServiceResult.Fail(request.Id, ViewUnavailable, "Live view is not available on this PC. Remote Browser can open a link, and it does not control the desktop.");
        }

        var frame = _surface.Capture();
        if (frame is null || frame.Length == 0 || frame.Length > CompanionServices.MaxFrameBytes)
        {
            return ServiceResult.Fail(request.Id, ErrorCodes.Limit, "The browser view was empty or too large.");
        }

        return ServiceResult.Done(request.Id, "Browser view updated.");
    }

    private ServiceResult Input(ServiceRequest request)
    {
        if (!_surface.CanInput)
        {
            return ServiceResult.Fail(request.Id, InputUnavailable, "Typing and taps are not available until a browser surface is attached. Desktop control is not supported.");
        }

        var kind = ServiceCodec.ReadString(request.Body, "kind") ?? "";
        if (kind is not ("tap" or "text"))
        {
            return ServiceResult.Fail(request.Id, ErrorCodes.BadRequest, "Browser input must be a tap or text.");
        }

        var text = ServiceCodec.ReadString(request.Body, "text");
        if (kind == "text" && (string.IsNullOrEmpty(text) || text.Length > 512))
        {
            return ServiceResult.Fail(request.Id, ErrorCodes.BadRequest, "Type a short string.");
        }

        _surface.Input(kind, Number(request.Body, "x"), Number(request.Body, "y"), text);
        return ServiceResult.Done(request.Id, "Sent to the browser.");
    }

    private string StatusLine() =>
        $"Open links: {On(_surface.CanOpen)}. Live view: {On(_surface.CanView)}. Typing: {On(_surface.CanInput)}.";

    private static string On(bool value) => value ? "on" : "off";

    private static double Number(JsonElement body, string name)
    {
        if (!body.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.Number)
        {
            return 0;
        }

        var number = value.GetDouble();
        if (number < 0 || number > 1)
        {
            return 0;
        }

        return number;
    }
}

public sealed class CompanionHost
{
    private readonly ClipboardHandoff _clipboard;
    private readonly RemoteBrowserPlugin _browser;

    public CompanionHost(ISecretClipboard clipboard, IUserNotice notice, IBrowserSurface browser, Func<DateTimeOffset>? clock = null)
    {
        Clock = clock ?? (() => DateTimeOffset.UtcNow);
        _clipboard = new ClipboardHandoff(clipboard, notice);
        _browser = new RemoteBrowserPlugin(browser);
    }

    public Func<DateTimeOffset> Clock { get; }

    public IReadOnlyList<RemotePluginInfo> Plugins() => [_browser.Describe()];

    public event Action<ServiceRequest, ServiceResult>? Completed;

    public ServiceResult Handle(ServiceRequest request)
    {
        var result = request.Service switch
        {
            CompanionServices.VaultClipboard when request.Op == "put" =>
                _clipboard.Put(
                    request.Id,
                    ServiceCodec.ReadString(request.Body, "password"),
                    Clock(),
                    ServiceCodec.ReadInt(request.Body, "clearAfterSeconds")),
            CompanionServices.VaultClipboard =>
                ServiceResult.Fail(request.Id, ErrorCodes.NotImplemented, "That Vault action is not available."),
            CompanionServices.RemoteBrowser => _browser.Handle(request),
            _ => ServiceResult.Fail(request.Id, ErrorCodes.NotImplemented, "That companion feature is not available.")
        };

        try
        {
            Completed?.Invoke(request, result);
        }
        catch (Exception)
        {
            // A toast or status line must not replace the protocol result.
        }

        return result;
    }

    public bool Tick() => _clipboard.Tick(Clock());
}
