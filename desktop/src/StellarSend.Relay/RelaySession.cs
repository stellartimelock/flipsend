using System.Buffers;
using System.Net.WebSockets;
using StellarSend.Core.Transfer;

namespace StellarSend.Relay;

public static class RelaySession
{
    public static readonly TimeSpan KeepAliveInterval = TimeSpan.FromSeconds(10);

    public static async Task ForwardAsync(
        WebSocket socket,
        RelayHub hub,
        RelayHello hello,
        RelaySeat seat,
        QuotaLedger ledger,
        CancellationToken ct,
        Action<int>? onInboundBytes = null,
        string? seen = null)
    {
        using var joinCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var join = hub.JoinAsync(hello.Room, hello.Role, seat, hello.Device, joinCts.Token);
        using var drainCts = CancellationTokenSource.CreateLinkedTokenSource(joinCts.Token);
        _ = join.ContinueWith(_ =>
        {
            try
            {
                drainCts.Cancel();
            }
            catch (ObjectDisposedException)
            {
            }
        }, TaskScheduler.Default);
        var drainBuf = ArrayPool<byte>.Shared.Rent(256);
        try
        {
            while (!join.IsCompleted)
            {
                if (socket.State != WebSocketState.Open)
                {
                    joinCts.Cancel();
                    Log($"Relay dropped a waiting {RoleName(hello.Role)} because its socket closed. {Describe(socket, null)}");
                    await ObserveAsync(join).ConfigureAwait(false);
                    return;
                }

                ValueWebSocketReceiveResult result;
                try
                {
                    result = await socket.ReceiveAsync(drainBuf.AsMemory(0, drainBuf.Length), drainCts.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception ex) when (ex is WebSocketException or IOException)
                {
                    joinCts.Cancel();
                    Log($"Relay dropped a waiting {RoleName(hello.Role)}. {Describe(socket, ex)}");
                    await ObserveAsync(join).ConfigureAwait(false);
                    return;
                }

                if (result.MessageType == WebSocketMessageType.Close)
                {
                    joinCts.Cancel();
                    Log($"Relay dropped a waiting {RoleName(hello.Role)}. {Describe(socket, null)}");
                    await ObserveAsync(join).ConfigureAwait(false);
                    return;
                }
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(drainBuf);
        }

        RelayLink link;
        try
        {
            link = await join.ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            Log($"Relay dropped a waiting {RoleName(hello.Role)}. {Describe(socket, null)}");
            return;
        }
        catch (RelayQuotaException)
        {
            throw;
        }

        if (socket.State != WebSocketState.Open)
        {
            await link.DisposeAsync().ConfigureAwait(false);
            Log($"Relay closed a paired {RoleName(hello.Role)} before forwarding. {Describe(socket, null)}");
            return;
        }

        try
        {
            await using (link)
            {
                var ready = System.Text.Encoding.UTF8.GetBytes(RelayMessages.Ready(link.Seat, seen));
                await socket.SendAsync(ready, WebSocketMessageType.Text, endOfMessage: true, ct).ConfigureAwait(false);
                Log("Paired two peers.");
                using var pumpCancel = CancellationTokenSource.CreateLinkedTokenSource(ct);
                var inbound = PumpSocketToStreamAsync(socket, link.Stream, pumpCancel.Token, onInboundBytes);
                var outbound = PumpStreamToSocketAsync(link.Stream, socket, link.Seat, ledger, pumpCancel.Token);
                var ended = await Task.WhenAny(inbound, outbound).ConfigureAwait(false);
                pumpCancel.Cancel();
                await ObserveAsync(inbound).ConfigureAwait(false);
                await ObserveAsync(outbound).ConfigureAwait(false);
                if (ended.IsFaulted)
                {
                    var error = ended.Exception?.GetBaseException();
                    Log($"Relay session ended: {error?.GetType().Name}: {error?.Message}");
                }
                else
                {
                    Log($"Relay session ended: {RoleName(hello.Role)} peer closed. {Describe(socket, null)}");
                }
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException or IOException)
        {
            Log(Describe(socket, ex));
        }
    }

    public static async Task PumpSocketToStreamAsync(WebSocket socket, Stream stream, CancellationToken ct, Action<int>? onBytes)
    {
        var rented = ArrayPool<byte>.Shared.Rent(RelayBuffer.SliceBytes);
        try
        {
            while (!ct.IsCancellationRequested && socket.State == WebSocketState.Open)
            {
                ValueWebSocketReceiveResult result;
                try
                {
                    result = await socket.ReceiveAsync(rented.AsMemory(0, RelayBuffer.SliceBytes), ct).ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is WebSocketException or IOException or OperationCanceledException)
                {
                    Log(Describe(socket, ex));
                    return;
                }

                if (result.MessageType == WebSocketMessageType.Close)
                {
                    Log(Describe(socket, null));
                    return;
                }

                if (result.MessageType != WebSocketMessageType.Binary || result.Count == 0)
                {
                    continue;
                }

                try
                {
                    await stream.WriteAsync(rented.AsMemory(0, result.Count), ct).ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is IOException or OperationCanceledException or InvalidOperationException)
                {
                    Log($"Relay forward stopped: {ex.GetType().Name}: {ex.Message}");
                    return;
                }

                onBytes?.Invoke(result.Count);
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(rented);
        }
    }

    public static async Task PumpStreamToSocketAsync(Stream stream, WebSocket socket, RelaySeat seat, QuotaLedger ledger, CancellationToken ct)
    {
        var rented = ArrayPool<byte>.Shared.Rent(RelayBuffer.SliceBytes);
        try
        {
            while (!ct.IsCancellationRequested && socket.State == WebSocketState.Open)
            {
                int read;
                try
                {
                    read = await stream.ReadAsync(rented.AsMemory(0, RelayBuffer.SliceBytes), ct).ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is IOException or OperationCanceledException or InvalidOperationException)
                {
                    Log($"Relay forward stopped: {ex.GetType().Name}: {ex.Message}");
                    return;
                }

                if (read == 0)
                {
                    Log("Relay closed: peer stream ended.");
                    return;
                }

                var take = ledger.TryForward(seat, read);
                if (!take.Ok)
                {
                    var message = take.PoolBlocked
                        ? FairUse.BusyMessage
                        : take.RateBlocked
                            ? FairUse.RateMessage
                            : take.PlanBlocked
                                ? RelayPlans.QuotaMessage
                                : FairUse.PauseMessage(seat.FairLimit);
                    var notice = System.Text.Encoding.UTF8.GetBytes(RelayMessages.Quota(seat with
                    {
                        Message = message,
                        PlanBlocked = take.PlanBlocked,
                        FairUseBlocked = take.FairBlocked,
                        Blocked = true
                    }));
                    try
                    {
                        await socket.SendAsync(notice, WebSocketMessageType.Text, endOfMessage: true, CancellationToken.None).ConfigureAwait(false);
                    }
                    catch (Exception ex) when (ex is WebSocketException or IOException or OperationCanceledException)
                    {
                        Log(Describe(socket, ex));
                    }

                    Log("Relay closed: quota. " + message);
                    return;
                }

                try
                {
                    await socket.SendAsync(rented.AsMemory(0, read), WebSocketMessageType.Binary, endOfMessage: true, ct).ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is WebSocketException or IOException or OperationCanceledException)
                {
                    Log(Describe(socket, ex));
                    return;
                }
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(rented);
        }
    }

    private static async Task ObserveAsync(Task task)
    {
        try
        {
            await task.ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException or IOException or InvalidOperationException)
        {
        }
    }

    private static string RoleName(RelayRole role) => role == RelayRole.Listen ? "listener" : "dialer";

    private static void Log(string line) => Console.WriteLine(line);

    public static string Describe(WebSocket socket, Exception? ex)
    {
        var status = socket.CloseStatus?.ToString() ?? "none";
        var description = socket.CloseStatusDescription;
        var close = string.IsNullOrWhiteSpace(description) ? status : status + ": " + description;
        if (ex is null || ex is OperationCanceledException)
        {
            return "Relay closed: " + close + ".";
        }

        return $"Relay closed: {close}. {ex.GetType().Name}: {ex.Message}";
    }
}
