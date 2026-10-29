using StellarSend.Core.Security;

namespace StellarSend.Core.Shell;

/// <summary>
/// Names the running FlipSend process. Opening the mutex does not wait for the
/// current owner to exit. Requesting initial ownership blocks the second launch
/// for as long as the first process holds the mutex.
/// </summary>
public sealed class SingleInstanceClaim : IDisposable
{
    public const string DefaultName = @"Local\StellarSend";

    private readonly Mutex? _mutex;
    private int _disposed;

    private SingleInstanceClaim(Mutex? mutex, bool isFirst)
    {
        _mutex = mutex;
        IsFirst = isFirst;
    }

    public bool IsFirst { get; }

    public static SingleInstanceClaim Acquire(string name, ILog log)
    {
        try
        {
            var mutex = new Mutex(initiallyOwned: false, name, out var created);
            if (created)
            {
                log.Info("This is the first FlipSend instance.");
                return new SingleInstanceClaim(mutex, true);
            }

            log.Info("FlipSend is already running.");
            return new SingleInstanceClaim(mutex, false);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or WaitHandleCannotBeOpenedException or ArgumentException)
        {
            log.Error("Could not open the single-instance lock. This launch will continue.");
            return new SingleInstanceClaim(null, true);
        }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0)
        {
            return;
        }

        _mutex?.Dispose();
    }
}
