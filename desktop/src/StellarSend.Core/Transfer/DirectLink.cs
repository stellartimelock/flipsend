using System.Net;
using System.Net.Sockets;
using System.Text.Json;

namespace StellarSend.Core.Transfer;

/// <summary>
/// Cross-network direct link. The relay only introduces the two public addresses.
/// File bytes use a punched socket with the existing pairing certificates, or stay on the relay.
/// </summary>
public static class DirectLink
{
    public const int BudgetMs = 4000;
    public const string WifiDirectNote = "Wi-Fi Direct is not started. A miss stays on the punch or the relay.";

    public static string Choose(bool punched, bool wifiDirectUp) =>
        punched || wifiDirectUp ? TransferPath.Cross : TransferPath.Relay;

    public static bool IAmClient(string localDeviceId, string remoteDeviceId) =>
        string.Compare(localDeviceId, remoteDeviceId, StringComparison.OrdinalIgnoreCase) < 0;

    public static string? ReadSeen(string? json)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            return null;
        }

        try
        {
            using var doc = JsonDocument.Parse(json);
            if (!doc.RootElement.TryGetProperty("seen", out var seen) || seen.ValueKind != JsonValueKind.String)
            {
                return null;
            }

            var text = seen.GetString();
            return IsEndpoint(text) ? text : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public static bool IsEndpoint(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return false;
        }

        var cut = text.LastIndexOf(':');
        if (cut <= 0 || cut == text.Length - 1)
        {
            return false;
        }

        return IPAddress.TryParse(text[..cut], out var ip)
            && ip.AddressFamily == AddressFamily.InterNetwork
            && int.TryParse(text[(cut + 1)..], out var port)
            && port is > 0 and <= 65535;
    }

    public static byte[] Packet(Guid token)
    {
        var packet = new byte[20];
        "SSND"u8.CopyTo(packet);
        Convert.FromHexString(token.ToString("N")).CopyTo(packet, 4);
        return packet;
    }

    public static bool TryReadPacket(ReadOnlySpan<byte> bytes, out Guid token)
    {
        token = default;
        if (bytes.Length < 20 || bytes[0] != (byte)'S' || bytes[1] != (byte)'S' || bytes[2] != (byte)'N' || bytes[3] != (byte)'D')
        {
            return false;
        }

        return Guid.TryParse(Convert.ToHexString(bytes.Slice(4, 16)), out token);
    }

    public static IReadOnlyList<PunchTarget> Targets(DirectOffer local, DirectOffer remote)
    {
        _ = local;
        var list = new List<PunchTarget>();
        void Add(string? host, int tcpPort)
        {
            if (string.IsNullOrWhiteSpace(host) || tcpPort is < 1 or > 65535 || remote.Udp is < 1 or > 65535)
            {
                return;
            }

            if (!IPAddress.TryParse(host, out var ip) || ip.AddressFamily != AddressFamily.InterNetwork)
            {
                return;
            }

            var target = new PunchTarget(ip.ToString(), remote.Udp, tcpPort);
            if (list.Contains(target))
            {
                return;
            }

            list.Add(target);
        }

        if (TrySplit(remote.Seen, out var publicHost, out var seenPort))
        {
            Add(publicHost, remote.Tcp);
            Add(publicHost, seenPort);
        }

        if (remote.Lan is not null)
        {
            foreach (var host in remote.Lan)
            {
                Add(host, remote.Tcp);
            }
        }

        return list;
    }

    public static bool TrySplit(string? endpoint, out string host, out int port)
    {
        host = "";
        port = 0;
        if (!IsEndpoint(endpoint) || endpoint is null)
        {
            return false;
        }

        var cut = endpoint.LastIndexOf(':');
        host = endpoint[..cut];
        return int.TryParse(endpoint[(cut + 1)..], out port);
    }
}

public readonly record struct PunchTarget(string Host, int UdpPort, int TcpPort);

public sealed record DirectOffer(
    [property: System.Text.Json.Serialization.JsonPropertyName("token")] string Token,
    [property: System.Text.Json.Serialization.JsonPropertyName("udp")] int Udp,
    [property: System.Text.Json.Serialization.JsonPropertyName("tcp")] int Tcp,
    [property: System.Text.Json.Serialization.JsonPropertyName("seen")]
    [property: System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)]
    string? Seen = null,
    [property: System.Text.Json.Serialization.JsonPropertyName("lan")]
    [property: System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)]
    string[]? Lan = null);
