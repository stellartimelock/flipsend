using System.Collections.Concurrent;
using System.Net;
using System.Net.WebSockets;
using System.Text;
using StellarSend.Core.Transfer;
using StellarSend.Relay;

var started = DateTime.UtcNow;
var usage = UsageStoreFactory.Create(out var flushSeconds);
var privacyHtml = Encoding.UTF8.GetBytes(RelayPages.LoadPrivacyHtml());
var port = Environment.GetEnvironmentVariable("PORT");
if (string.IsNullOrWhiteSpace(port))
{
    port = "8080";
}

var tiersPath = Environment.GetEnvironmentVariable("TIERS_PATH");
if (string.IsNullOrWhiteSpace(tiersPath))
{
    tiersPath = Path.Combine(AppContext.BaseDirectory, "tiers.json");
}

var tiers = TierTable.Load(tiersPath);
var fairUse = FairUsePolicy.FromEnvironment(tiers);
var account = PlayServiceAccount.TryLoad(Environment.GetEnvironmentVariable("PLAY_SERVICE_ACCOUNT_JSON"));
IPlaySubscriptionVerifier? verifier = account is null ? null : new GooglePlayVerifier(account, tiers);
var usagePath = Environment.GetEnvironmentVariable("RELAY_USAGE_PATH");
if (string.IsNullOrWhiteSpace(usagePath))
{
    usagePath = Path.Combine(AppContext.BaseDirectory, "relay-usage.json");
}

var ledger = new QuotaLedger(usagePath);
var admission = new RelayAdmission(tiers, verifier, ledger, fairUse);
Console.WriteLine(account is null
    ? "Play service account is not set. Plan caps are not checked. The monthly relay fair-use cap is still enforced."
    : "Play service account is set. Relay checks subscriptionsv2 and enforces the monthly data cap. The fair-use cap applies to every account.");
Console.WriteLine(
    "Free relay is " + FairUse.FormatGb(fairUse.DefaultBytes) +
    " per account each month. Permanent Elite is " + FairUse.FormatGb(fairUse.EliteBytes) +
    ". The free pool is " + FairUse.FormatGb(fairUse.PoolBytes) +
    ". Same-Wi-Fi and USB are not counted.");

var listener = new HttpListener();
listener.Prefixes.Add($"http://+:{port}/");
listener.Start();
Console.WriteLine($"FlipSend relay listening on port {port}. Usage store: {usage.StoreName}. Put TLS on 443 in front of this process.");
var hub = new RelayHub((listen, dial) => admission.Combine(listen, dial));
var mailbox = new BotMailbox();
var stopping = new CancellationTokenSource();
var flushLoop = Task.Run(async () =>
{
    using var timer = new PeriodicTimer(TimeSpan.FromSeconds(flushSeconds));
    try
    {
        while (await timer.WaitForNextTickAsync(stopping.Token).ConfigureAwait(false))
        {
            try
            {
                await usage.FlushAsync(CancellationToken.None).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is IOException or HttpRequestException or InvalidDataException)
            {
            }
        }
    }
    catch (OperationCanceledException)
    {
    }
});
Console.CancelKeyPress += (_, eventArgs) =>
{
    eventArgs.Cancel = true;
    stopping.Cancel();
    listener.Stop();
};

while (!stopping.IsCancellationRequested)
{
    HttpListenerContext context;
    try
    {
        context = await listener.GetContextAsync().ConfigureAwait(false);
    }
    catch (Exception ex) when (ex is HttpListenerException or ObjectDisposedException or InvalidOperationException)
    {
        break;
    }

    _ = Task.Run(() => ServeAsync(hub, mailbox, usage, privacyHtml, started, admission, ledger, fairUse, context, stopping.Token));
}

stopping.Cancel();
try
{
    await usage.FlushAsync(CancellationToken.None).ConfigureAwait(false);
}
catch (Exception ex) when (ex is IOException or HttpRequestException or InvalidDataException)
{
}

try
{
    await flushLoop.ConfigureAwait(false);
}
catch (OperationCanceledException)
{
}

static async Task ServeAsync(
    RelayHub hub,
    BotMailbox mailbox,
    UsageCounter usage,
    byte[] privacyHtml,
    DateTime started,
    RelayAdmission admission,
    QuotaLedger ledger,
    FairUsePolicy fairUse,
    HttpListenerContext context,
    CancellationToken ct)
{
    try
    {
        RelayCors.Apply(context.Response.Headers);
        if (RelayCors.IsPreflight(context.Request.HttpMethod))
        {
            context.Response.StatusCode = 204;
            context.Response.ContentLength64 = 0;
            context.Response.Close();
            return;
        }

        var path = context.Request.Url?.AbsolutePath ?? "";
        if (IsRead(context.Request) && RelayPages.IsHealth(path))
        {
            await WriteBytesAsync(context, 200, "application/json; charset=utf-8", RelayPages.HealthJson(usage.StoreName), cache: false).ConfigureAwait(false);
            return;
        }

        if (IsRead(context.Request) && RelayPages.IsPrivacy(path))
        {
            await WriteBytesAsync(context, 200, "text/html; charset=utf-8", privacyHtml, cache: true).ConfigureAwait(false);
            return;
        }

        if (IsRead(context.Request) && AdminAuth.IsPath(path))
        {
            var code = AdminAuth.Authorize(context.Request.Headers["Authorization"], Environment.GetEnvironmentVariable("RELAY_ADMIN_TOKEN"));
            if (code != 200)
            {
                if (code == 401)
                {
                    await WriteBytesAsync(context, 401, "application/json; charset=utf-8", """{"ok":false}"""u8.ToArray(), cache: false).ConfigureAwait(false);
                }
                else
                {
                    context.Response.StatusCode = 404;
                }

                return;
            }

            var report = await usage.ReportJsonAsync(DateTime.UtcNow, DateTime.UtcNow - started, ct, fairUse.DefaultBytes).ConfigureAwait(false);
            await WriteBytesAsync(context, 200, "application/json; charset=utf-8", Encoding.UTF8.GetBytes(report), cache: false).ConfigureAwait(false);
            return;
        }

        if (FairUseAdmin.IsPath(path))
        {
            var code = AdminAuth.Authorize(context.Request.Headers["Authorization"], Environment.GetEnvironmentVariable("RELAY_ADMIN_TOKEN"));
            if (code != 200)
            {
                if (code == 401)
                {
                    await WriteBytesAsync(context, 401, "application/json; charset=utf-8", """{"ok":false}"""u8.ToArray(), cache: false).ConfigureAwait(false);
                }
                else
                {
                    context.Response.StatusCode = 404;
                }

                return;
            }

            if (!context.Request.HttpMethod.Equals("POST", StringComparison.OrdinalIgnoreCase))
            {
                context.Response.StatusCode = 405;
                return;
            }

            var body = await ReadBodyAsync(context.Request, 8192).ConfigureAwait(false);
            var result = FairUseAdmin.Apply(ledger, fairUse, body, DateTimeOffset.UtcNow);
            await WriteBytesAsync(context, result.Status, "application/json; charset=utf-8", Encoding.UTF8.GetBytes(result.Json), cache: false).ConfigureAwait(false);
            return;
        }

        if (RelayRoutes.Decide(context.Request.HttpMethod, path, context.Request.IsWebSocketRequest) != RelayRoute.WebSocket)
        {
            context.Response.StatusCode = 404;
            context.Response.Close();
            return;
        }

        var socket = (await context.AcceptWebSocketAsync(subProtocol: null, RelaySession.KeepAliveInterval).ConfigureAwait(false)).WebSocket;
        var helloText = await ReceiveTextAsync(socket, ct).ConfigureAwait(false);
        if (!RelayJoin.TryParse(helloText, out var hello))
        {
            await socket.CloseAsync(WebSocketCloseStatus.PolicyViolation, "bad join", CancellationToken.None).ConfigureAwait(false);
            return;
        }

        var seat = await admission.AdmitAsync(hello.Credential, ct).ConfigureAwait(false);
        if (hello.Usage)
        {
            var report = Encoding.UTF8.GetBytes(RelayMessages.Usage(seat));
            await socket.SendAsync(report, WebSocketMessageType.Text, endOfMessage: true, ct).ConfigureAwait(false);
            await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "usage", CancellationToken.None).ConfigureAwait(false);
            return;
        }

        if (hello.Bot != BotOp.None)
        {
            await ServeBotAsync(mailbox, usage, hello, socket, seat, ct).ConfigureAwait(false);
            return;
        }

        var slot = PurchaseSlots.Map.GetOrAdd(hello.Room, static _ => new PurchaseSlot());
        lock (slot)
        {
            slot.Refs++;
            var id = UsageId(hello);
            if (!string.IsNullOrEmpty(id) && slot.Id is null)
            {
                slot.Id = id;
            }

            var plan = UsagePlan(hello) ?? seat.TierId;
            if (!string.IsNullOrEmpty(plan))
            {
                slot.Plan = plan;
            }
        }

        try
        {
            await RelaySession.ForwardAsync(socket, hub, hello, seat, ledger, ct, n => Count(usage, slot, n), Observed(context.Request)).ConfigureAwait(false);
        }
        catch (RelayQuotaException ex)
        {
            var denied = Encoding.UTF8.GetBytes(RelayMessages.Quota(seat with { Blocked = true, Message = ex.Message }));
            await socket.SendAsync(denied, WebSocketMessageType.Text, endOfMessage: true, CancellationToken.None).ConfigureAwait(false);
            await socket.CloseAsync(WebSocketCloseStatus.PolicyViolation, "quota", CancellationToken.None).ConfigureAwait(false);
            Console.WriteLine("Relay closed: quota. " + ex.Message);
        }
        finally
        {
            Release(hello.Room, slot);
        }
    }
    catch (Exception ex) when (ex is OperationCanceledException or WebSocketException or IOException or HttpListenerException or ArgumentException)
    {
        Console.WriteLine(ex is OperationCanceledException
            ? "Relay closed: cancelled."
            : $"Relay closed: {ex.GetType().Name}: {ex.Message}");
    }
    finally
    {
        context.Response.Close();
    }
}

static async Task ServeBotAsync(
    BotMailbox mailbox,
    UsageCounter usage,
    RelayHello hello,
    WebSocket socket,
    RelaySeat seat,
    CancellationToken ct)
{
    if (seat.Blocked)
    {
        var denied = Encoding.UTF8.GetBytes(RelayMessages.Quota(seat));
        await socket.SendAsync(denied, WebSocketMessageType.Text, endOfMessage: true, CancellationToken.None).ConfigureAwait(false);
        await socket.CloseAsync(WebSocketCloseStatus.PolicyViolation, "quota", CancellationToken.None).ConfigureAwait(false);
        return;
    }

    var ready = Encoding.UTF8.GetBytes(RelayMessages.Ready(seat));
    await socket.SendAsync(ready, WebSocketMessageType.Text, endOfMessage: true, ct).ConfigureAwait(false);
    var now = DateTimeOffset.UtcNow;
    string reply;
    if (hello.Bot == BotOp.Get)
    {
        reply = mailbox.GetJson(hello.Room, now);
    }
    else
    {
        var body = await ReceiveTextAsync(socket, ct, 16_384).ConfigureAwait(false);
        if (!BotMailbox.TryParsePut(body, out var id, out var blob))
        {
            await socket.CloseAsync(WebSocketCloseStatus.PolicyViolation, "bad put", CancellationToken.None).ConfigureAwait(false);
            return;
        }

        var result = mailbox.Put(hello.Room, id, blob, now);
        reply = mailbox.PutJson(result);
        if (result.Ok)
        {
            var idKey = UsageId(hello);
            if (!string.IsNullOrEmpty(idKey))
            {
                usage.Add(idKey, Encoding.UTF8.GetByteCount(blob), DateTime.UtcNow, UsagePlan(hello) ?? seat.TierId);
            }
        }
    }

    await socket.SendAsync(Encoding.UTF8.GetBytes(reply), WebSocketMessageType.Text, endOfMessage: true, ct).ConfigureAwait(false);
    await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "bot", CancellationToken.None).ConfigureAwait(false);
}

static string? UsageId(RelayHello hello)
{
    if (!string.IsNullOrEmpty(hello.Purchase))
    {
        return hello.Purchase;
    }

    return hello.Credential?.EntitlementKey;
}

static string? UsagePlan(RelayHello hello)
{
    if (!string.IsNullOrEmpty(hello.Plan))
    {
        return hello.Plan;
    }

    var product = hello.Credential?.ProductId;
    if (string.IsNullOrWhiteSpace(product))
    {
        return null;
    }

    if (product.Contains("plus", StringComparison.OrdinalIgnoreCase))
    {
        return "plus";
    }

    if (product.Contains("max", StringComparison.OrdinalIgnoreCase))
    {
        return "max";
    }

    if (product.Contains("pro", StringComparison.OrdinalIgnoreCase))
    {
        return "pro";
    }

    return null;
}

static void Release(string room, PurchaseSlot slot)
{
    lock (slot)
    {
        slot.Refs--;
        if (slot.Refs <= 0)
        {
            PurchaseSlots.Map.TryRemove(room, out _);
        }
    }
}

static void Count(UsageCounter usage, PurchaseSlot slot, int bytes)
{
    if (bytes <= 0)
    {
        return;
    }

    string? id;
    string? plan;
    lock (slot)
    {
        id = slot.Id;
        plan = slot.Plan;
    }

    if (id is not null)
    {
        usage.Add(id, bytes, DateTime.UtcNow, plan);
    }
}

static bool IsRead(HttpListenerRequest request) =>
    request.HttpMethod.Equals("GET", StringComparison.OrdinalIgnoreCase)
    || request.HttpMethod.Equals("HEAD", StringComparison.OrdinalIgnoreCase);

static async Task<string?> ReadBodyAsync(HttpListenerRequest request, int maxBytes)
{
    if (request.ContentLength64 > maxBytes)
    {
        return null;
    }

    using var reader = new StreamReader(request.InputStream, Encoding.UTF8, detectEncodingFromByteOrderMarks: false, bufferSize: 1024, leaveOpen: true);
    var buffer = new char[maxBytes + 1];
    var read = await reader.ReadBlockAsync(buffer.AsMemory(0, maxBytes + 1)).ConfigureAwait(false);
    if (read > maxBytes)
    {
        return null;
    }

    return new string(buffer, 0, read);
}

static async Task WriteBytesAsync(HttpListenerContext context, int status, string contentType, byte[] body, bool cache)
{
    context.Response.StatusCode = status;
    context.Response.ContentType = contentType;
    context.Response.Headers["Cache-Control"] = cache ? "public, max-age=3600" : "no-store";
    context.Response.ContentLength64 = body.Length;
    if (!context.Request.HttpMethod.Equals("HEAD", StringComparison.OrdinalIgnoreCase))
    {
        await context.Response.OutputStream.WriteAsync(body).ConfigureAwait(false);
    }
}

static async Task<string> ReceiveTextAsync(WebSocket socket, CancellationToken ct, int maxBytes = 4096)
{
    var buffer = new byte[4096];
    using var message = new MemoryStream();
    while (true)
    {
        var result = await socket.ReceiveAsync(buffer, ct).ConfigureAwait(false);
        if (result.MessageType == WebSocketMessageType.Close)
        {
            throw new IOException("Closed.");
        }

        message.Write(buffer, 0, result.Count);
        if (message.Length > maxBytes)
        {
            throw new IOException("Relay frame is too large.");
        }

        if (result.EndOfMessage)
        {
            return Encoding.UTF8.GetString(message.ToArray());
        }
    }
}

static string? Observed(HttpListenerRequest request)
{
    string? host = null;
    var forwarded = request.Headers["X-Forwarded-For"];
    if (!string.IsNullOrWhiteSpace(forwarded))
    {
        var first = forwarded.Split(',')[0].Trim();
        if (IPAddress.TryParse(first, out var forwardedIp) && forwardedIp.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
        {
            host = forwardedIp.ToString();
        }
    }

    var remote = request.RemoteEndPoint;
    if (host is null && remote?.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
    {
        host = remote.Address.ToString();
    }

    var portHeader = request.Headers["X-Forwarded-Port"];
    if (!int.TryParse(portHeader, out var port) || port is < 1 or > 65535)
    {
        port = remote?.Port ?? 0;
    }

    if (host is null || port is < 1 or > 65535)
    {
        return null;
    }

    var text = host + ":" + port;
    return DirectLink.IsEndpoint(text) ? text : null;
}

internal static class PurchaseSlots
{
    public static readonly ConcurrentDictionary<string, PurchaseSlot> Map = new(StringComparer.Ordinal);
}

internal sealed class PurchaseSlot
{
    public string? Id;
    public string? Plan;
    public int Refs;
}
