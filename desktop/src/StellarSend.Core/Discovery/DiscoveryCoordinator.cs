namespace StellarSend.Core.Discovery;

public static class DiscoveryCoordinator
{
    public static async Task<DiscoveryHit?> RunAsync(
        DiscoveryPlan plan,
        Func<int, CancellationToken, Task<DiscoveryHit?>> listenMdns,
        Func<int, CancellationToken, Task<DiscoveryHit?>> listenBroadcast,
        Func<ProbeTarget, CancellationToken, Task<bool>> probe,
        CancellationToken ct)
    {
        using var run = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var tasks = new List<Task<DiscoveryHit?>>();
        if (plan.UseMdns)
        {
            tasks.Add(Guard(listenMdns(plan.MdnsTimeoutMs, run.Token)));
        }

        if (plan.UseBroadcast)
        {
            tasks.Add(Guard(listenBroadcast(plan.BroadcastTimeoutMs, run.Token)));
        }

        var probes = ProbeAllAsync(plan.Probes, probe, run.Token);
        tasks.Add(probes);
        // A Windows TCP connect can ignore CancelAfter. This budget task
        // must end the search so the sender can wake the hosted relay.
        // A connect that already answered is kept: that phone is on Wi-Fi.
        var deadline = DeadlineAsync(plan.TotalBudgetMs);
        tasks.Add(deadline);
        try
        {
            while (tasks.Count > 0)
            {
                var finished = await Task.WhenAny(tasks).ConfigureAwait(false);
                tasks.Remove(finished);
                if (ReferenceEquals(finished, deadline))
                {
                    return await TakeAnswerAsync(tasks, probes).ConfigureAwait(false);
                }

                var hit = await finished.ConfigureAwait(false);
                if (hit is not null)
                {
                    return hit;
                }

                if (ct.IsCancellationRequested || run.IsCancellationRequested)
                {
                    return await TakeAnswerAsync(tasks, probes).ConfigureAwait(false);
                }
            }

            return null;
        }
        finally
        {
            run.Cancel();
        }
    }

    private static async Task<DiscoveryHit?> TakeAnswerAsync(List<Task<DiscoveryHit?>> tasks, Task<DiscoveryHit?> probes)
    {
        foreach (var task in tasks)
        {
            if (!task.IsCompleted)
            {
                continue;
            }

            var ready = await Guard(task).ConfigureAwait(false);
            if (ready is not null)
            {
                return ready;
            }
        }

        var grace = Task.Delay(LanPresence.AnswerGraceMs);
        var finished = await Task.WhenAny(probes, grace).ConfigureAwait(false);
        if (ReferenceEquals(finished, probes))
        {
            var answered = await Guard(probes).ConfigureAwait(false);
            if (answered is not null)
            {
                return answered;
            }
        }

        foreach (var task in tasks)
        {
            if (!task.IsCompleted)
            {
                continue;
            }

            var ready = await Guard(task).ConfigureAwait(false);
            if (ready is not null)
            {
                return ready;
            }
        }

        return null;
    }

    private static async Task<DiscoveryHit?> ProbeAllAsync(
        IReadOnlyList<ProbeTarget> probes,
        Func<ProbeTarget, CancellationToken, Task<bool>> probe,
        CancellationToken ct)
    {
        if (probes.Count == 0)
        {
            return null;
        }

        using var first = CancellationTokenSource.CreateLinkedTokenSource(ct);
        using var gate = new SemaphoreSlim(4);
        var winner = new TaskCompletionSource<DiscoveryHit?>(TaskCreationOptions.RunContinuationsAsynchronously);
        var workers = new List<Task>();
        try
        {
        foreach (var target in probes)
        {
            await gate.WaitAsync(first.Token).ConfigureAwait(false);
            if (first.IsCancellationRequested)
            {
                gate.Release();
                break;
            }

            workers.Add(Task.Run(async () =>
            {
                try
                {
                    using var timeout = CancellationTokenSource.CreateLinkedTokenSource(first.Token);
                    timeout.CancelAfter(target.TimeoutMs);
                    if (await probe(target, timeout.Token).ConfigureAwait(false))
                    {
                        winner.TrySetResult(new DiscoveryHit(target.Ip, target.Port, target.Reason.ToString()));
                        first.Cancel();
                    }
                }
                catch (OperationCanceledException)
                {
                }
                catch
                {
                    // A refused probe is a miss, not a fatal discovery error.
                }
                finally
                {
                    gate.Release();
                }
            }, CancellationToken.None));
        }
        }
        catch (OperationCanceledException)
        {
        }

        try
        {
            var done = Task.WhenAll(workers);
            var stop = Task.Delay(Timeout.Infinite, ct);
            await Task.WhenAny(done, stop).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
        }

        winner.TrySetResult(null);
        return await winner.Task.ConfigureAwait(false);
    }

    private static async Task<DiscoveryHit?> DeadlineAsync(int budgetMs)
    {
        await Task.Delay(Math.Max(1, budgetMs)).ConfigureAwait(false);
        return null;
    }

    private static async Task<DiscoveryHit?> Guard(Task<DiscoveryHit?> task)
    {
        try
        {
            return await task.ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return null;
        }
        catch
        {
            return null;
        }
    }
}
