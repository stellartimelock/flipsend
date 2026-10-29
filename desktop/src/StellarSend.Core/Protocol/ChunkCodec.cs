using System.Buffers.Binary;

namespace StellarSend.Core.Protocol;

public static class UuidBytes
{
    public static void Write(Guid id, Span<byte> destination)
    {
        if (destination.Length < 16)
        {
            throw new ArgumentException("Destination must hold 16 bytes.", nameof(destination));
        }

        var hex = id.ToString("N");
        Convert.FromHexString(hex).CopyTo(destination);
    }

    public static Guid Read(ReadOnlySpan<byte> source)
    {
        if (source.Length < 16)
        {
            throw new ProtocolException("Short UUID.");
        }

        var hex = Convert.ToHexString(source[..16]).ToLowerInvariant();
        return Guid.ParseExact(hex, "N");
    }
}

public readonly record struct Chunk(Guid TransferId, ulong Offset, byte[] Data);

public static class ChunkCodec
{
    public const int Prefix = 24;

    public static byte[] Encode(Guid transferId, ulong offset, ReadOnlySpan<byte> data)
    {
        if (data.Length > ProtocolLimits.MaxChunkData)
        {
            throw new ProtocolException("Chunk exceeds the limit.", ErrorCodes.Limit);
        }

        var payload = new byte[Prefix + data.Length];
        UuidBytes.Write(transferId, payload);
        BinaryPrimitives.WriteUInt64BigEndian(payload.AsSpan(16, 8), offset);
        data.CopyTo(payload.AsSpan(Prefix));
        return payload;
    }

    public static Chunk Decode(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < Prefix)
        {
            throw new ProtocolException("Chunk is too short.");
        }

        var dataLength = payload.Length - Prefix;
        if (dataLength > ProtocolLimits.MaxChunkData)
        {
            throw new ProtocolException("Chunk exceeds the limit.", ErrorCodes.Limit);
        }

        var id = UuidBytes.Read(payload);
        var offset = BinaryPrimitives.ReadUInt64BigEndian(payload.Slice(16, 8));
        var data = payload.Slice(Prefix).ToArray();
        return new Chunk(id, offset, data);
    }
}
