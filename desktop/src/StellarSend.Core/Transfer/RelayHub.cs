using System.Buffers;
using System.Threading.Channels;
using StellarSend.Core.Discovery;

namespace StellarSend.Core.Transfer;

public static class RelayBuffer
{
    public const int SliceBytes = 8 * 1024;
    public const int BudgetBytes = 2 * 1024 * 1024;
    public const int Capacity = BudgetBytes / SliceBytes;
}

public sealed class RelayLink : IAsyncDisposable
{
    private readonly ChunkStream _stream;

    internal RelayLink(ChunkStream stream)
        : this(stream, RelaySeat.Unenforced)
    {
    }

    internal RelayLink(ChunkStream stream, RelaySeat seat)
    {
        _stream = stream;
        Seat = seat;
    }

    public Stream Stream => _stream;
    public RelaySeat Seat { get; }

    public long OutgoingQueuedBytes => _stream.OutgoingQueuedBytes;

    public ValueTask DisposeAsync()
    {
        _stream.Dispose();
        return ValueTask.CompletedTask;
    }
}

/// <summary>
/// Pairs two outbound peers and forwards opaque chunks. The hub does not inspect those bytes.
/// </summary>
public sealed class RelayHub
{
    private readonly object _gate = new();
    private readonly Dictionary<string, Waiting> _rooms = new(StringComparer.Ordinal);
    private readonly Func<RelaySeat, RelaySeat, RelaySeat>? _combine;

    public RelayHub(Func<RelaySeat, RelaySeat, RelaySeat>? combine = null)
    {
        _combine = combine;
    }

    public Task<RelayLink> JoinAsync(string room, RelayRole role, CancellationToken ct) =>
        JoinAsync(room, role, RelaySeat.Unenforced, deviceId: null, ct);

    public Task<RelayLink> JoinAsync(string room, RelayRole role, RelaySeat seat, CancellationToken ct) =>
        JoinAsync(room, role, seat, deviceId: null, ct);

    public Task<RelayLink> JoinAsync(string room, RelayRole role, RelaySeat seat, string? deviceId, CancellationToken ct)
    {
        if (!RelayRoom.IsToken(room))
        {
            throw new ArgumentException("Relay room is invalid.");
        }

        var mine = new TaskCompletionSource<RelayLink>(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (_gate)
        {
            if (!_rooms.TryGetValue(room, out var waiting))
            {
                waiting = new Waiting();
                _rooms[room] = waiting;
            }

            Prune(waiting);
            if (role == RelayRole.Listen)
            {
                waiting.Listen?.TrySetCanceled();
                waiting.Listen = mine;
                waiting.ListenSeat = seat;
                waiting.ListenDevice = deviceId;
            }
            else
            {
                waiting.Dial?.TrySetCanceled();
                waiting.Dial = mine;
                waiting.DialSeat = seat;
                waiting.DialDevice = deviceId;
            }

            TryPair(room, waiting);
        }

        var registration = ct.Register(() =>
        {
            lock (_gate)
            {
                if (_rooms.TryGetValue(room, out var waiting))
                {
                    if (role == RelayRole.Listen && ReferenceEquals(waiting.Listen, mine))
                    {
                        waiting.Listen = null;
                    }
                    else if (role == RelayRole.Dial && ReferenceEquals(waiting.Dial, mine))
                    {
                        waiting.Dial = null;
                    }

                    if (waiting.Listen is null && waiting.Dial is null)
                    {
                        _rooms.Remove(room);
                    }
                }
            }

            mine.TrySetCanceled(ct);
        });
        return mine.Task.ContinueWith(task =>
        {
            registration.Dispose();
            return task.GetAwaiter().GetResult();
        }, CancellationToken.None, TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
    }

    private void TryPair(string room, Waiting waiting)
    {
        Prune(waiting);
        if (waiting.Listen is null || waiting.Dial is null)
        {
            if (waiting.Listen is null && waiting.Dial is null)
            {
                _rooms.Remove(room);
            }

            return;
        }

        var listen = waiting.Listen;
        var dial = waiting.Dial;
        if (RelaySelf.SameDevice(waiting.ListenDevice, waiting.DialDevice))
        {
            // Same device in both seats. Keep the listener; the dialer is this computer.
            waiting.Dial = null;
            waiting.DialDevice = null;
            waiting.DialSeat = RelaySeat.Unenforced;
            dial?.TrySetException(new SelfDialException());
            return;
        }

        var combined = _combine?.Invoke(waiting.ListenSeat, waiting.DialSeat) ?? RelaySeat.Unenforced;
        _rooms.Remove(room);
        if (combined.Blocked)
        {
            var rejected = new RelayQuotaException(combined.Message ?? RelayPlans.QuotaMessage);
            listen.TrySetException(rejected);
            dial.TrySetException(rejected);
            return;
        }

        var (listenStream, dialStream) = ChunkStream.Pair();
        listen.TrySetResult(new RelayLink(listenStream, combined));
        dial.TrySetResult(new RelayLink(dialStream, combined));
    }

    private static void Prune(Waiting waiting)
    {
        if (waiting.Listen?.Task.IsCompleted == true)
        {
            waiting.Listen = null;
        }

        if (waiting.Dial?.Task.IsCompleted == true)
        {
            waiting.Dial = null;
        }
    }

    private sealed class Waiting
    {
        public TaskCompletionSource<RelayLink>? Listen { get; set; }
        public TaskCompletionSource<RelayLink>? Dial { get; set; }
        public RelaySeat ListenSeat { get; set; } = RelaySeat.Unenforced;
        public RelaySeat DialSeat { get; set; } = RelaySeat.Unenforced;
        public string? ListenDevice { get; set; }
        public string? DialDevice { get; set; }
    }
}

internal sealed class ChunkStream : Stream
{
    private readonly Channel<PooledSlice> _incoming;
    private readonly Channel<PooledSlice> _outgoing;
    private readonly QueueMeter _incomingMeter;
    private readonly QueueMeter _outgoingMeter;
    private PooledSlice _current;
    private int _offset;
    private bool _hasCurrent;
    private bool _disposed;

    private ChunkStream(Channel<PooledSlice> incoming, Channel<PooledSlice> outgoing, QueueMeter incomingMeter, QueueMeter outgoingMeter)
    {
        _incoming = incoming;
        _outgoing = outgoing;
        _incomingMeter = incomingMeter;
        _outgoingMeter = outgoingMeter;
    }

    public long OutgoingQueuedBytes => Interlocked.Read(ref _outgoingMeter.Bytes);

    public static (ChunkStream Listen, ChunkStream Dial) Pair()
    {
        var options = new BoundedChannelOptions(RelayBuffer.Capacity)
        {
            FullMode = BoundedChannelFullMode.Wait,
            SingleReader = true,
            SingleWriter = true
        };
        var listenToDial = Channel.CreateBounded<PooledSlice>(options);
        var dialToListen = Channel.CreateBounded<PooledSlice>(options);
        var listenOut = new QueueMeter();
        var dialOut = new QueueMeter();
        return (
            new ChunkStream(dialToListen, listenToDial, dialOut, listenOut),
            new ChunkStream(listenToDial, dialToListen, listenOut, dialOut));
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
        while (!_hasCurrent || _offset >= _current.Length)
        {
            ReleaseCurrent();
            if (!await _incoming.Reader.WaitToReadAsync(cancellationToken).ConfigureAwait(false))
            {
                return 0;
            }

            if (!_incoming.Reader.TryRead(out var chunk))
            {
                return 0;
            }

            Interlocked.Add(ref _incomingMeter.Bytes, -chunk.Length);
            if (chunk.Length == 0)
            {
                chunk.Return();
                return 0;
            }

            _current = chunk;
            _offset = 0;
            _hasCurrent = true;
        }

        var n = Math.Min(buffer.Length, _current.Length - _offset);
        _current.Buffer.AsSpan(_offset, n).CopyTo(buffer.Span);
        _offset += n;
        if (_offset >= _current.Length)
        {
            ReleaseCurrent();
        }

        return n;
    }

    public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        var offset = 0;
        while (offset < buffer.Length)
        {
            var n = Math.Min(RelayBuffer.SliceBytes, buffer.Length - offset);
            var rented = ArrayPool<byte>.Shared.Rent(n);
            buffer.Span.Slice(offset, n).CopyTo(rented);
            var slice = new PooledSlice(rented, n);
            Interlocked.Add(ref _outgoingMeter.Bytes, n);
            try
            {
                await _outgoing.Writer.WriteAsync(slice, cancellationToken).ConfigureAwait(false);
            }
            catch (ChannelClosedException ex)
            {
                Interlocked.Add(ref _outgoingMeter.Bytes, -n);
                slice.Return();
                throw new IOException("The relay closed the connection.", ex);
            }
            catch
            {
                Interlocked.Add(ref _outgoingMeter.Bytes, -n);
                slice.Return();
                throw;
            }

            offset += n;
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
        _outgoing.Writer.TryComplete();
        _incoming.Writer.TryComplete();
        ReleaseCurrent();
        while (_incoming.Reader.TryRead(out var pending))
        {
            Interlocked.Add(ref _incomingMeter.Bytes, -pending.Length);
            pending.Return();
        }

        base.Dispose(disposing);
    }

    private void ReleaseCurrent()
    {
        if (!_hasCurrent)
        {
            return;
        }

        _current.Return();
        _hasCurrent = false;
        _offset = 0;
    }

    private readonly struct PooledSlice
    {
        public PooledSlice(byte[] buffer, int length)
        {
            Buffer = buffer;
            Length = length;
        }

        public byte[] Buffer { get; }
        public int Length { get; }

        public void Return()
        {
            if (Buffer is { Length: > 0 })
            {
                ArrayPool<byte>.Shared.Return(Buffer);
            }
        }
    }

    private sealed class QueueMeter
    {
        public long Bytes;
    }
}
