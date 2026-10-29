using System.Net.WebSockets;
using System.Runtime.ExceptionServices;
using System.Text;
using System.Threading.Channels;

namespace StellarSend.Core.Transfer;

public static class RelayClient
{
    public static Task<Stream> OpenAsync(string url, string room, RelayRole role, CancellationToken ct) =>
        OpenAsync(url, room, role, null, null, ct);

    public static async Task<bool> WakeAsync(string url, Action? onWaiting, CancellationToken ct)
    {
        if (!RelayAddress.TryParse(url, out var endpoint))
        {
            return false;
        }

        foreach (var attempt in RelayWake.Attempts())
        {
            ct.ThrowIfCancellationRequested();
            if (attempt.Delay > TimeSpan.Zero)
            {
                onWaiting?.Invoke();
                await Task.Delay(attempt.Delay, ct).ConfigureAwait(false);
            }

            if (await HealthAwakeAsync(endpoint, attempt.Timeout, ct).ConfigureAwait(false))
            {
                return true;
            }

            onWaiting?.Invoke();
        }

        return false;
    }

    public static async Task<Stream> OpenAsync(
        string url,
        string room,
        RelayRole role,
        RelayCredential? credential,
        Action? onWaiting,
        CancellationToken ct,
        string? deviceId = null)
    {
        if (!RelayAddress.TryParse(url, out var endpoint))
        {
            throw new InvalidOperationException("Relay address must be a wss URL.");
        }

        Exception? last = null;
        var relayAwake = false;
        if (role == RelayRole.Listen && await HealthAwakeAsync(endpoint, TimeSpan.FromSeconds(5), ct).ConfigureAwait(false))
        {
            relayAwake = true;
            try
            {
                return await OpenOnceAsync(endpoint, room, role, credential, RelayWake.ListenWait, ct, deviceId).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex) when (ex is IOException or TimeoutException or InvalidOperationException or WebSocketException or HttpRequestException or OperationCanceledException)
            {
                throw new IOException("No peer in the room yet.", ex);
            }
        }

        foreach (var attempt in RelayWake.Attempts())
        {
            ct.ThrowIfCancellationRequested();
            if (attempt.Delay > TimeSpan.Zero)
            {
                onWaiting?.Invoke();
                await Task.Delay(attempt.Delay, ct).ConfigureAwait(false);
            }

            var slow = new CancellationTokenSource();
            var announced = false;
            var timer = Task.Delay(TimeSpan.FromMilliseconds(1500), slow.Token).ContinueWith(
                task =>
                {
                    if (!task.IsCanceled && !announced)
                    {
                        announced = true;
                        onWaiting?.Invoke();
                    }
                },
                CancellationToken.None,
                TaskContinuationOptions.ExecuteSynchronously,
                TaskScheduler.Default);
            try
            {
                if (!await HealthAwakeAsync(endpoint, attempt.Timeout, ct).ConfigureAwait(false))
                {
                    last = new IOException("The relay has not woken up yet.");
                    onWaiting?.Invoke();
                    continue;
                }

                relayAwake = true;
                return await OpenOnceAsync(endpoint, room, role, credential, attempt.Timeout, ct, deviceId).ConfigureAwait(false);
            }
            catch (RelayQuotaException)
            {
                throw;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex) when (ex is IOException or TimeoutException or InvalidOperationException or WebSocketException or HttpRequestException or OperationCanceledException)
            {
                last = ex;
                onWaiting?.Invoke();
            }
            finally
            {
                slow.Cancel();
                try
                {
                    await timer.ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                }

                slow.Dispose();
            }
        }

        throw new RelayWakeException(RelayWake.AfterAttempts(relayAwake), last);
    }

    public static Task<string> BotGetAsync(string url, string room, RelayCredential? credential, Action? onWaiting, CancellationToken ct) =>
        BotCallAsync(url, room, BotOp.Get, followUp: null, credential, onWaiting, ct);

    public static Task<string> BotPutAsync(string url, string room, string id, string blob, RelayCredential? credential, Action? onWaiting, CancellationToken ct) =>
        BotCallAsync(url, room, BotOp.Put, BotHub.PutBody(id, blob), credential, onWaiting, ct);

    private static async Task<string> BotCallAsync(
        string url,
        string room,
        BotOp op,
        string? followUp,
        RelayCredential? credential,
        Action? onWaiting,
        CancellationToken ct)
    {
        if (!RelayAddress.TryParse(url, out var endpoint))
        {
            throw new InvalidOperationException("Relay address must be a wss URL.");
        }

        Exception? last = null;
        foreach (var attempt in RelayWake.Attempts())
        {
            ct.ThrowIfCancellationRequested();
            if (attempt.Delay > TimeSpan.Zero)
            {
                onWaiting?.Invoke();
                await Task.Delay(attempt.Delay, ct).ConfigureAwait(false);
            }

            var socket = new ClientWebSocket();
            socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(10);
            try
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
                timeout.CancelAfter(attempt.Timeout);
                if (!await HealthAwakeAsync(endpoint, attempt.Timeout, ct).ConfigureAwait(false))
                {
                    last = new IOException("The relay has not woken up yet.");
                    onWaiting?.Invoke();
                    continue;
                }

                await socket.ConnectAsync(endpoint, timeout.Token).ConfigureAwait(false);
                var hello = Encoding.UTF8.GetBytes(RelayJoin.Bot(room, op, credential));
                await socket.SendAsync(hello, WebSocketMessageType.Text, endOfMessage: true, timeout.Token).ConfigureAwait(false);
                var ready = await ReceiveTextAsync(socket, timeout.Token).ConfigureAwait(false);
                if (RelayJoin.IsQuota(ready))
                {
                    throw new RelayQuotaException(RelayJoin.QuotaMessage(ready));
                }

                if (!RelayJoin.IsReady(ready))
                {
                    throw new IOException("The relay did not connect.");
                }

                if (followUp is not null)
                {
                    await socket.SendAsync(Encoding.UTF8.GetBytes(followUp), WebSocketMessageType.Text, endOfMessage: true, timeout.Token).ConfigureAwait(false);
                }

                return await ReceiveTextAsync(socket, timeout.Token).ConfigureAwait(false);
            }
            catch (RelayQuotaException)
            {
                throw;
            }
            catch (Exception ex) when (ex is IOException or TimeoutException or WebSocketException or TaskCanceledException && !ct.IsCancellationRequested)
            {
                last = ex;
                onWaiting?.Invoke();
            }
            finally
            {
                socket.Dispose();
            }
        }

        throw new RelayWakeException(RelayWake.GaveUp, last);
    }

    public static async Task<string> UsageAsync(string url, RelayCredential? credential, Action? onWaiting, CancellationToken ct)
    {
        if (!RelayAddress.TryParse(url, out var endpoint))
        {
            throw new InvalidOperationException("Relay address must be a wss URL.");
        }

        Exception? last = null;
        foreach (var attempt in RelayWake.Attempts())
        {
            ct.ThrowIfCancellationRequested();
            if (attempt.Delay > TimeSpan.Zero)
            {
                onWaiting?.Invoke();
                await Task.Delay(attempt.Delay, ct).ConfigureAwait(false);
            }

            var socket = new ClientWebSocket();
            socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(10);
            try
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
                timeout.CancelAfter(attempt.Timeout);
                await socket.ConnectAsync(endpoint, timeout.Token).ConfigureAwait(false);
                var hello = Encoding.UTF8.GetBytes(RelayJoin.Usage(credential));
                await socket.SendAsync(hello, WebSocketMessageType.Text, endOfMessage: true, timeout.Token).ConfigureAwait(false);
                return await ReceiveTextAsync(socket, timeout.Token).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is IOException or TimeoutException or WebSocketException or TaskCanceledException && !ct.IsCancellationRequested)
            {
                last = ex;
                onWaiting?.Invoke();
            }
            finally
            {
                socket.Dispose();
            }
        }

        throw new RelayWakeException(RelayWake.GaveUp, last);
    }

    private static async Task<bool> HealthAwakeAsync(Uri endpoint, TimeSpan timeout, CancellationToken ct)
    {
        try
        {
            var health = new UriBuilder(endpoint.Scheme == "wss" ? "https" : "http", endpoint.Host, endpoint.Port, "/healthz").Uri;
            using var http = new HttpClient { Timeout = timeout };
            using var response = await http.GetAsync(health, ct).ConfigureAwait(false);
            return (int)response.StatusCode is 200 or 404;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or IOException)
        {
            return false;
        }
    }

    private static async Task<Stream> OpenOnceAsync(Uri endpoint, string room, RelayRole role, RelayCredential? credential, TimeSpan timeout, CancellationToken ct, string? deviceId = null)
    {
        var socket = new ClientWebSocket();
        socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(10);
        try
        {
            using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
            limit.CancelAfter(timeout);
            await socket.ConnectAsync(endpoint, limit.Token).ConfigureAwait(false);
            var hello = Encoding.UTF8.GetBytes(RelayJoin.Write(room, role, credential, deviceId));
            await socket.SendAsync(hello, WebSocketMessageType.Text, endOfMessage: true, limit.Token).ConfigureAwait(false);
            var reply = await ReceiveTextAsync(socket, limit.Token).ConfigureAwait(false);
            if (RelayJoin.IsQuota(reply))
            {
                throw new RelayQuotaException(RelayJoin.QuotaMessage(reply));
            }

            if (!RelayJoin.IsReady(reply))
            {
                throw new IOException("The relay did not connect.");
            }

            return new WebSocketByteStream(socket, DirectLink.ReadSeen(reply));
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }

    private static async Task<string> ReceiveTextAsync(ClientWebSocket socket, CancellationToken ct)
    {
        var buffer = new byte[4096];
        using var message = new MemoryStream();
        while (true)
        {
            var result = await socket.ReceiveAsync(buffer, ct).ConfigureAwait(false);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                throw new IOException("The relay closed the connection.");
            }

            if (result.MessageType != WebSocketMessageType.Text)
            {
                throw new IOException("The relay did not accept the room.");
            }

            message.Write(buffer, 0, result.Count);
            if (result.EndOfMessage)
            {
                return Encoding.UTF8.GetString(message.ToArray());
            }
        }
    }
}

internal sealed class WebSocketByteStream : Stream, IInputPending
{
    private readonly ClientWebSocket _socket;
    private readonly SemaphoreSlim _send = new(1, 1);
    private readonly Channel<byte[]> _incoming;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly Task _pump;
    private readonly object _failGate = new();
    private byte[] _pending = [];
    private int _offset;
    private Exception? _failure;
    private bool _disposed;

    public string? Seen { get; }

    public bool InputPending
    {
        get
        {
            if (_offset < _pending.Length) return true;
            return !_disposed && _incoming.Reader.TryPeek(out _);
        }
    }

    public WebSocketByteStream(ClientWebSocket socket, string? seen = null)
    {
        _socket = socket;
        Seen = seen;
        _incoming = Channel.CreateBounded<byte[]>(new BoundedChannelOptions(64)
        {
            FullMode = BoundedChannelFullMode.Wait,
            SingleReader = true,
            SingleWriter = true
        });
        _pump = Task.Run(PumpAsync);
    }

    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => true;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override void Flush()
    {
    }

    public override int Read(byte[] buffer, int offset, int count) =>
        ReadAsync(buffer.AsMemory(offset, count), CancellationToken.None).AsTask().GetAwaiter().GetResult();

    public override void Write(byte[] buffer, int offset, int count) =>
        WriteAsync(buffer.AsMemory(offset, count), CancellationToken.None).AsTask().GetAwaiter().GetResult();

    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
    {
        while (_offset >= _pending.Length)
        {
            ThrowIfFailed();
            if (!await _incoming.Reader.WaitToReadAsync(cancellationToken).ConfigureAwait(false))
            {
                ThrowIfFailed();
                return 0;
            }

            if (!_incoming.Reader.TryRead(out var chunk) || chunk.Length == 0)
            {
                ThrowIfFailed();
                return 0;
            }

            _pending = chunk;
            _offset = 0;
        }

        var n = Math.Min(buffer.Length, _pending.Length - _offset);
        _pending.AsSpan(_offset, n).CopyTo(buffer.Span);
        _offset += n;
        return n;
    }

    public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
    {
        if (buffer.Length == 0)
        {
            await WriteSliceAsync(buffer, cancellationToken).ConfigureAwait(false);
            return;
        }

        var offset = 0;
        while (offset < buffer.Length)
        {
            var count = Math.Min(RelayBuffer.SliceBytes, buffer.Length - offset);
            await WriteSliceAsync(buffer.Slice(offset, count), cancellationToken).ConfigureAwait(false);
            offset += count;
        }
    }

    private async ValueTask WriteSliceAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        ThrowIfFailed();
        await _send.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ThrowIfFailed();
            await _socket.SendAsync(buffer, WebSocketMessageType.Binary, endOfMessage: true, cancellationToken).ConfigureAwait(false);
            ThrowIfFailed();
        }
        catch (Exception ex) when (ex is WebSocketException or IOException or OperationCanceledException && !cancellationToken.IsCancellationRequested)
        {
            await Task.WhenAny(_pump, Task.Delay(400, CancellationToken.None)).ConfigureAwait(false);
            ThrowIfFailed();
            throw new IOException(ex.Message, ex);
        }
        finally
        {
            _send.Release();
        }
    }

    private async Task PumpAsync()
    {
        var chunk = new byte[8192];
        try
        {
            while (!_lifetime.IsCancellationRequested && _socket.State == WebSocketState.Open)
            {
                using var message = new MemoryStream();
                var isText = false;
                while (true)
                {
                    var result = await _socket.ReceiveAsync(chunk, _lifetime.Token).ConfigureAwait(false);
                    if (result.MessageType == WebSocketMessageType.Close || result.Count == 0 && result.EndOfMessage && result.MessageType != WebSocketMessageType.Binary)
                    {
                        Fail(new IOException(DescribeClose(result)));
                        return;
                    }

                    if (result.MessageType == WebSocketMessageType.Text)
                    {
                        isText = true;
                        message.Write(chunk, 0, result.Count);
                        if (!result.EndOfMessage)
                        {
                            continue;
                        }

                        var text = Encoding.UTF8.GetString(message.ToArray());
                        if (RelayJoin.IsQuota(text))
                        {
                            Fail(new RelayQuotaException(RelayJoin.QuotaMessage(text)));
                            return;
                        }

                        break;
                    }

                    if (result.MessageType != WebSocketMessageType.Binary)
                    {
                        if (result.EndOfMessage)
                        {
                            break;
                        }

                        continue;
                    }

                    message.Write(chunk, 0, result.Count);
                    if (result.EndOfMessage)
                    {
                        break;
                    }
                }

                if (isText)
                {
                    continue;
                }

                var payload = message.ToArray();
                if (payload.Length == 0)
                {
                    continue;
                }

                await _incoming.Writer.WriteAsync(payload, _lifetime.Token).ConfigureAwait(false);
            }
        }
        catch (Exception ex) when (ex is not RelayQuotaException)
        {
            if (_lifetime.IsCancellationRequested)
            {
                return;
            }

            Fail(ex is IOException ? ex : new IOException(DescribeFailure(ex), ex));
        }
        finally
        {
            _incoming.Writer.TryComplete();
        }
    }

    private static string DescribeClose(WebSocketReceiveResult result)
    {
        if (!string.IsNullOrWhiteSpace(result.CloseStatusDescription))
        {
            return result.CloseStatusDescription;
        }

        return result.CloseStatus is { } status
            ? "The relay closed the connection (" + status + ")."
            : "The relay closed the connection.";
    }

    private static string DescribeFailure(Exception ex)
    {
        var message = string.IsNullOrWhiteSpace(ex.Message) ? "The relay closed the connection." : ex.Message;
        return message;
    }

    private void Fail(Exception ex)
    {
        lock (_failGate)
        {
            _failure ??= ex;
        }

        _incoming.Writer.TryComplete(ex);
    }

    private void ThrowIfFailed()
    {
        Exception? ex;
        lock (_failGate)
        {
            ex = _failure;
        }

        if (ex is not null)
        {
            ExceptionDispatchInfo.Capture(ex).Throw();
        }
    }

    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        if (disposing)
        {
            _lifetime.Cancel();
            try
            {
                if (_socket.State == WebSocketState.Open)
                {
                    _socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None).GetAwaiter().GetResult();
                }
            }
            catch (Exception ex) when (ex is WebSocketException or IOException or ObjectDisposedException or OperationCanceledException)
            {
            }

            _socket.Dispose();
            _send.Dispose();
            _lifetime.Dispose();
        }

        base.Dispose(disposing);
    }
}
