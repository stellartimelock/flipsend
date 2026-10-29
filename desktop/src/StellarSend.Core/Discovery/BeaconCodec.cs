using System.Text;
using StellarSend.Core.Protocol;

namespace StellarSend.Core.Discovery;

public static class BeaconCodec
{
    public static byte[] Encode(Guid deviceId, int port, string name, string? code = null)
    {
        var body = new BeaconBody(ProtocolLimits.Version, "stellarsend", deviceId.ToString("D"), port, Validators.TrimName(name), string.IsNullOrWhiteSpace(code) ? null : code);
        var bytes = ProtocolJson.ToUtf8(body);
        if (bytes.Length > ProtocolLimits.MaxBeaconBytes)
        {
            throw new ProtocolException("Discovery beacon is too large.", ErrorCodes.Limit);
        }

        return bytes;
    }

    public static bool TryParse(ReadOnlySpan<byte> data, out BeaconBody? body)
    {
        body = null;
        if (data.Length is 0 or > ProtocolLimits.MaxBeaconBytes)
        {
            return false;
        }

        var text = Encoding.UTF8.GetString(data);
        var lower = text.ToLowerInvariant();
        if (lower.Contains("\"pk\"", StringComparison.Ordinal)
            || lower.Contains("private", StringComparison.Ordinal)
            || lower.Contains("secret", StringComparison.Ordinal))
        {
            return false;
        }

        try
        {
            var parsed = ProtocolJson.Read<BeaconBody>(data.ToArray());
            if (parsed.V != ProtocolLimits.Version || parsed.App != "stellarsend")
            {
                return false;
            }

            if (!Guid.TryParse(parsed.Id, out _) || parsed.Port is < 1 or > 65535)
            {
                return false;
            }

            body = parsed;
            return true;
        }
        catch (ProtocolException)
        {
            return false;
        }
    }
}
