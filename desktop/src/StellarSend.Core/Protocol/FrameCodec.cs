using System.Buffers.Binary;

namespace StellarSend.Core.Protocol;

public enum MessageType : byte
{
    Hello = 1,
    HelloAck = 2,
    Offer = 3,
    Accept = 4,
    Reject = 5,
    Chunk = 6,
    Cancel = 7,
    Complete = 8,
    Error = 9,
    Service = 10,
    ServiceResult = 11,
    TerminalData = 12,
    ScreenFrame = 13,
    RemoteInput = 14,
    Ack = 15
}

public sealed class Frame
{
    public required byte Version { get; init; }
    public required MessageType Type { get; init; }
    public required ushort Flags { get; init; }
    public required byte[] Payload { get; init; }
}

public static class FrameCodec
{
    private static ReadOnlySpan<byte> Magic => "SSND"u8;

    public static byte[] Encode(MessageType type, ReadOnlySpan<byte> payload, ushort flags = 0, byte version = ProtocolLimits.Version)
    {
        if (version != ProtocolLimits.Version)
        {
            throw new ProtocolException($"Unsupported protocol version {version}.");
        }

        if (flags != 0)
        {
            throw new ProtocolException("Unsupported frame flags.");
        }

        if (!Enum.IsDefined(type))
        {
            throw new ProtocolException("Unknown message type.");
        }

        if (payload.Length > ProtocolLimits.MaxFramePayload)
        {
            throw new ProtocolException("Frame payload exceeds the limit.", ErrorCodes.Limit);
        }

        var buffer = new byte[ProtocolLimits.HeaderSize + payload.Length];
        Magic.CopyTo(buffer);
        buffer[4] = version;
        buffer[5] = (byte)type;
        BinaryPrimitives.WriteUInt16BigEndian(buffer.AsSpan(6, 2), flags);
        BinaryPrimitives.WriteUInt32BigEndian(buffer.AsSpan(8, 4), (uint)payload.Length);
        payload.CopyTo(buffer.AsSpan(ProtocolLimits.HeaderSize));
        return buffer;
    }

    public static async Task WriteAsync(Stream stream, MessageType type, ReadOnlyMemory<byte> payload, CancellationToken ct, ushort flags = 0)
    {
        var bytes = Encode(type, payload.Span, flags);
        await stream.WriteAsync(bytes, ct).ConfigureAwait(false);
        await stream.FlushAsync(ct).ConfigureAwait(false);
    }

    public static Task WriteJsonAsync<T>(Stream stream, MessageType type, T body, CancellationToken ct) =>
        WriteAsync(stream, type, ProtocolJson.ToUtf8(body), ct);

    public static async Task<Frame> ReadAsync(Stream stream, CancellationToken ct, TimeSpan? timeout = null)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct);
        if (timeout is { } limit)
        {
            linked.CancelAfter(limit);
        }

        try
        {
            return await ReadFrameAsync(stream, linked.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            throw new TimeoutException("The paired device stopped responding.");
        }
    }

    private static async Task<Frame> ReadFrameAsync(Stream stream, CancellationToken ct)
    {
        var header = new byte[ProtocolLimits.HeaderSize];
        await ReadExactlyAsync(stream, header, ct).ConfigureAwait(false);
        if (!header.AsSpan(0, 4).SequenceEqual(Magic))
        {
            throw new ProtocolException("Unrecognized protocol header.");
        }

        var version = header[4];
        if (version != ProtocolLimits.Version)
        {
            throw new ProtocolException($"Unsupported protocol version {version}.");
        }

        if (!Enum.IsDefined(typeof(MessageType), header[5]))
        {
            throw new ProtocolException("Unknown message type.");
        }

        var flags = BinaryPrimitives.ReadUInt16BigEndian(header.AsSpan(6, 2));
        if (flags != 0)
        {
            throw new ProtocolException("Unsupported frame flags.");
        }

        var length = BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(8, 4));
        if (length > ProtocolLimits.MaxFramePayload)
        {
            throw new ProtocolException("Frame payload exceeds the limit.", ErrorCodes.Limit);
        }

        var payload = new byte[length];
        if (length > 0)
        {
            await ReadExactlyAsync(stream, payload, ct).ConfigureAwait(false);
        }

        return new Frame
        {
            Version = version,
            Type = (MessageType)header[5],
            Flags = flags,
            Payload = payload
        };
    }

    public static async Task ReadExactlyAsync(Stream stream, Memory<byte> buffer, CancellationToken ct)
    {
        var read = 0;
        while (read < buffer.Length)
        {
            var n = await stream.ReadAsync(buffer[read..], ct).ConfigureAwait(false);
            if (n == 0)
            {
                throw new EndOfStreamException("Connection closed.");
            }

            read += n;
        }
    }
}
