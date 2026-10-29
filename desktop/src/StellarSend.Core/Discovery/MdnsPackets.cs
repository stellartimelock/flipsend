using System.Buffers.Binary;
using System.Net;
using System.Text;
using StellarSend.Core.Protocol;

namespace StellarSend.Core.Discovery;

public sealed record MdnsAnnouncement(Guid DeviceId, int Port, IPAddress Address);

public static class MdnsPackets
{
    public static byte[] BuildQuery()
    {
        var name = EncodeName(ProtocolLimits.ServiceName);
        var packet = new byte[12 + name.Length + 4];
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(4, 2), 1);
        name.CopyTo(packet, 12);
        var question = 12 + name.Length;
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(question, 2), 12);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(question + 2, 2), 1);
        return packet;
    }

    public static byte[] BuildAnnouncement(Guid deviceId, int port, IPAddress address)
    {
        if (address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork)
        {
            throw new ArgumentException("mDNS announcement needs an IPv4 address.", nameof(address));
        }

        var instanceLabel = "d" + deviceId.ToString("N");
        var instance = instanceLabel + "." + ProtocolLimits.ServiceName;
        var host = instanceLabel + ".local";
        var service = EncodeName(ProtocolLimits.ServiceName);
        var instanceName = EncodeName(instance);
        var hostName = EncodeName(host);
        var txt = Encoding.ASCII.GetBytes("id=" + deviceId.ToString("D"));
        if (txt.Length > 255)
        {
            throw new ProtocolException("TXT record is too long.");
        }

        using var body = new MemoryStream();
        WriteRecord(body, service, 12, 120, EncodeName(instance));
        var srv = new byte[6 + hostName.Length];
        BinaryPrimitives.WriteUInt16BigEndian(srv.AsSpan(0, 2), 0);
        BinaryPrimitives.WriteUInt16BigEndian(srv.AsSpan(2, 2), 0);
        BinaryPrimitives.WriteUInt16BigEndian(srv.AsSpan(4, 2), (ushort)port);
        hostName.CopyTo(srv, 6);
        WriteRecord(body, instanceName, 33, 120, srv);
        var txtRdata = new byte[txt.Length + 1];
        txtRdata[0] = (byte)txt.Length;
        txt.CopyTo(txtRdata, 1);
        WriteRecord(body, instanceName, 16, 120, txtRdata);
        WriteRecord(body, hostName, 1, 120, address.GetAddressBytes());

        var records = body.ToArray();
        var packet = new byte[12 + records.Length];
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(2, 2), 0x8400);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(6, 2), 1);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(10, 2), 3);
        records.CopyTo(packet, 12);
        return packet;
    }

    public static bool IsQueryForUs(ReadOnlySpan<byte> packet)
    {
        var needle = EncodeName(ProtocolLimits.ServiceName);
        return packet.IndexOf(needle) >= 0 && packet.Length >= 12 && (packet[2] & 0x80) == 0;
    }

    public static bool TryParseAnnouncement(ReadOnlySpan<byte> packet, out MdnsAnnouncement? announcement)
    {
        announcement = null;
        if (packet.Length < 12)
        {
            return false;
        }

        try
        {
            var offset = 12;
            var questions = BinaryPrimitives.ReadUInt16BigEndian(packet.Slice(4, 2));
            var answers = BinaryPrimitives.ReadUInt16BigEndian(packet.Slice(6, 2));
            var authorities = BinaryPrimitives.ReadUInt16BigEndian(packet.Slice(8, 2));
            var additional = BinaryPrimitives.ReadUInt16BigEndian(packet.Slice(10, 2));
            for (var i = 0; i < questions; i++)
            {
                SkipName(packet, ref offset);
                offset += 4;
            }

            Guid? id = null;
            int? port = null;
            IPAddress? address = null;
            var total = answers + authorities + additional;
            for (var i = 0; i < total; i++)
            {
                SkipName(packet, ref offset);
                var type = BinaryPrimitives.ReadUInt16BigEndian(packet.Slice(offset, 2));
                offset += 8;
                var length = BinaryPrimitives.ReadUInt16BigEndian(packet.Slice(offset, 2));
                offset += 2;
                var data = packet.Slice(offset, length);
                offset += length;
                if (type == 16 && data.Length > 1)
                {
                    var txtLength = data[0];
                    if (txtLength == data.Length - 1)
                    {
                        var text = Encoding.ASCII.GetString(data.Slice(1));
                        if (text.StartsWith("id=", StringComparison.Ordinal) && Guid.TryParse(text[3..], out var parsed))
                        {
                            id = parsed;
                        }
                    }
                }
                else if (type == 33 && data.Length >= 6)
                {
                    port = BinaryPrimitives.ReadUInt16BigEndian(data.Slice(4, 2));
                }
                else if (type == 1 && data.Length == 4)
                {
                    address = new IPAddress(data);
                }
            }

            if (id is null || port is null || address is null)
            {
                return false;
            }

            announcement = new MdnsAnnouncement(id.Value, port.Value, address);
            return true;
        }
        catch (Exception ex) when (ex is ProtocolException or ArgumentOutOfRangeException or OverflowException)
        {
            return false;
        }
    }

    private static void WriteRecord(Stream stream, byte[] name, ushort type, uint ttl, byte[] data)
    {
        stream.Write(name);
        Span<byte> meta = stackalloc byte[10];
        BinaryPrimitives.WriteUInt16BigEndian(meta[..2], type);
        BinaryPrimitives.WriteUInt16BigEndian(meta.Slice(2, 2), 1);
        BinaryPrimitives.WriteUInt32BigEndian(meta.Slice(4, 4), ttl);
        BinaryPrimitives.WriteUInt16BigEndian(meta.Slice(8, 2), (ushort)data.Length);
        stream.Write(meta);
        stream.Write(data);
    }

    public static byte[] EncodeName(string name)
    {
        var labels = name.TrimEnd('.').Split('.');
        using var stream = new MemoryStream();
        foreach (var label in labels)
        {
            var bytes = Encoding.ASCII.GetBytes(label);
            if (bytes.Length is 0 or > 63)
            {
                throw new ProtocolException("Invalid DNS label.");
            }

            stream.WriteByte((byte)bytes.Length);
            stream.Write(bytes);
        }

        stream.WriteByte(0);
        return stream.ToArray();
    }

    private static void SkipName(ReadOnlySpan<byte> packet, ref int offset)
    {
        var hops = 0;
        var end = -1;
        var index = offset;
        while (index < packet.Length && hops < 16)
        {
            var length = packet[index];
            if (length == 0)
            {
                index++;
                break;
            }

            if ((length & 0xC0) == 0xC0)
            {
                if (index + 1 >= packet.Length)
                {
                    throw new ProtocolException("Bad DNS pointer.");
                }

                if (end < 0)
                {
                    end = index + 2;
                }

                index = ((length & 0x3F) << 8) | packet[index + 1];
                hops++;
                continue;
            }

            index++;
            index += length;
        }

        offset = end < 0 ? index : end;
    }
}
