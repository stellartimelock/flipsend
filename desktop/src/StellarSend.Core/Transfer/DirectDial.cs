using System.Net;
using System.Net.Sockets;
using StellarSend.Core.Discovery;

namespace StellarSend.Core.Transfer;

public static class DirectDial
{
    public static async Task<T> FirstAsync<T>(
        IReadOnlyList<string> hosts,
        int port,
        Func<IPEndPoint, CancellationToken, Task<T>> connect,
        CancellationToken ct) where T : IAsyncDisposable
    {
        var unique = hosts
            .Where(host => LocalAddressPolicy.IsAllowedHost(host, allowLoopback: false))
            .Distinct(StringComparer.Ordinal)
            .ToArray();
        if (unique.Length == 0)
        {
            throw new DiscoveryException(Reachability.Other);
        }

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct);
        linked.CancelAfter(TimeSpan.FromSeconds(8));
        var tasks = unique.Select(host => Attempt(host, port, connect, linked.Token)).ToArray();
        var pending = tasks.ToList();
        T? winner = default;
        while (pending.Count > 0)
        {
            var done = await Task.WhenAny(pending).ConfigureAwait(false);
            pending.Remove(done);
            var value = await done.ConfigureAwait(false);
            if (value is not null)
            {
                winner = value;
                linked.Cancel();
                break;
            }
        }

        foreach (var task in tasks)
        {
            if (!task.IsCompletedSuccessfully || task.Result is null || ReferenceEquals(task.Result, winner))
            {
                continue;
            }

            await task.Result.DisposeAsync().ConfigureAwait(false);
        }

        ct.ThrowIfCancellationRequested();
        if (winner is null)
        {
            throw new DiscoveryException(Reachability.Other);
        }

        return winner;
    }

    private static async Task<T?> Attempt<T>(
        string host,
        int port,
        Func<IPEndPoint, CancellationToken, Task<T>> connect,
        CancellationToken ct) where T : IAsyncDisposable
    {
        try
        {
            if (!IPAddress.TryParse(host, out var address))
            {
                return default;
            }

            return await connect(new IPEndPoint(address, port), ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return default;
        }
        catch (Exception)
        {
            return default;
        }
    }
}
