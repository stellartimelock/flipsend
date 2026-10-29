using System.Text;
using StellarSend.Core.Protocol;

namespace StellarSend.Core.Tests;

public sealed class ProtocolTests
{
    [Fact]
    public void FrameVectorsMatchTheSharedFixture()
    {
        var fixture = Repo.ReadJson<VectorFile>("protocol-vectors.json");
        Assert.Equal(ProtocolLimits.Version, fixture.Version);
        Assert.Equal(ProtocolLimits.MaxFramePayload, fixture.MaxFramePayload);
        Assert.Equal(ProtocolLimits.MaxChunkData, fixture.MaxChunkData);
        Assert.Equal(ProtocolLimits.MaxTextBytes, fixture.MaxTextBytes);
        Assert.Equal(ProtocolLimits.MaxFileBytes, fixture.MaxFileBytes);
        Assert.Equal(ProtocolLimits.DefaultTcpPort, fixture.TcpPort);
        Assert.Equal(ProtocolLimits.DiscoveryUdpPort, fixture.UdpPort);

        foreach (var frame in fixture.Frames)
        {
            var payload = Convert.FromHexString(frame.PayloadHex);
            var encoded = FrameCodec.Encode((MessageType)frame.Type, payload, (ushort)frame.Flags);
            Assert.Equal(frame.FrameHex, Convert.ToHexString(encoded).ToLowerInvariant());
        }

        var chunk = Assert.Single(fixture.Chunks);
        var encodedChunk = ChunkCodec.Encode(Guid.Parse(chunk.TransferId), (ulong)chunk.Offset, Convert.FromHexString(chunk.DataHex));
        Assert.Equal(chunk.PayloadHex, Convert.ToHexString(encodedChunk).ToLowerInvariant());
        var decoded = ChunkCodec.Decode(encodedChunk);
        Assert.Equal(Guid.Parse(chunk.TransferId), decoded.TransferId);
        Assert.Equal((ulong)chunk.Offset, decoded.Offset);
        Assert.Equal(chunk.DataHex, Convert.ToHexString(decoded.Data).ToLowerInvariant());
    }

    [Fact]
    public void HelloJsonMatchesTheFixturePayload()
    {
        var fixture = Repo.ReadJson<VectorFile>("protocol-vectors.json");
        var hello = fixture.Frames.Single(f => f.Name == "hello");
        var fingerprint = string.Concat(Enumerable.Repeat("ab", 32));
        var body = new HelloBody(1, "11111111-1111-1111-1111-111111111111", "Desk", fingerprint);
        var json = ProtocolJson.ToUtf8(body);
        Assert.Equal(hello.PayloadHex, Convert.ToHexString(json).ToLowerInvariant());
    }

    [Fact]
    public async Task OversizedLengthIsRejectedWithoutAllocatingThePayload()
    {
        var header = new byte[12];
        "SSND"u8.CopyTo(header);
        header[4] = 1;
        header[5] = 1;
        header[8] = 0x7F;
        await using var stream = new MemoryStream(header);
        var ex = await Assert.ThrowsAsync<ProtocolException>(() => FrameCodec.ReadAsync(stream, CancellationToken.None));
        Assert.Equal(ErrorCodes.Limit, ex.Code);
    }

    [Fact]
    public void WrongMagicAndVersionFail()
    {
        var bad = FrameCodec.Encode(MessageType.Hello, "{\"v\":1}"u8.ToArray());
        bad[0] = (byte)'X';
        Assert.Throws<ProtocolException>(() => FrameCodec.ReadAsync(new MemoryStream(bad), CancellationToken.None).GetAwaiter().GetResult());
        var version = FrameCodec.Encode(MessageType.Hello, "{\"v\":1}"u8.ToArray());
        version[4] = 9;
        Assert.Throws<ProtocolException>(() => FrameCodec.ReadAsync(new MemoryStream(version), CancellationToken.None).GetAwaiter().GetResult());
    }

    [Fact]
    public void ChunkOverTheLimitIsRejected()
    {
        Assert.Throws<ProtocolException>(() => ChunkCodec.Encode(Guid.NewGuid(), 0, new byte[ProtocolLimits.MaxChunkData + 1]));
    }

    [Fact]
    public void OfferLimitsRejectHugeText()
    {
        var offer = new OfferBody(Guid.NewGuid().ToString("D"), Kinds.Text, "message.txt", ProtocolLimits.MaxTextBytes + 1, new string('a', 64), "text/plain");
        var ex = Assert.Throws<ProtocolException>(() => Validators.ValidateOffer(offer));
        Assert.Equal(ErrorCodes.Limit, ex.Code);
    }

    [Fact]
    public async Task RoundTripFrameSurvivesAStream()
    {
        var payload = Encoding.UTF8.GetBytes("{\"ok\":true}");
        await using var stream = new MemoryStream();
        await FrameCodec.WriteAsync(stream, MessageType.Accept, payload, CancellationToken.None);
        stream.Position = 0;
        var frame = await FrameCodec.ReadAsync(stream, CancellationToken.None);
        Assert.Equal(MessageType.Accept, frame.Type);
        Assert.Equal(payload, frame.Payload);
    }

    private sealed class VectorFile
    {
        public int Version { get; set; }
        public int MaxFramePayload { get; set; }
        public int MaxChunkData { get; set; }
        public long MaxTextBytes { get; set; }
        public long MaxFileBytes { get; set; }
        public int TcpPort { get; set; }
        public int UdpPort { get; set; }
        public List<FrameVector> Frames { get; set; } = [];
        public List<ChunkVector> Chunks { get; set; } = [];
    }

    private sealed class FrameVector
    {
        public string Name { get; set; } = "";
        public int Type { get; set; }
        public int Flags { get; set; }
        public string PayloadHex { get; set; } = "";
        public string FrameHex { get; set; } = "";
    }

    private sealed class ChunkVector
    {
        public string TransferId { get; set; } = "";
        public long Offset { get; set; }
        public string DataHex { get; set; } = "";
        public string PayloadHex { get; set; } = "";
    }
}
