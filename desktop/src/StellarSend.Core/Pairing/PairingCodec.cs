using System.Security.Cryptography;
using StellarSend.Core.Discovery;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;

namespace StellarSend.Core.Pairing;

public sealed record PairingAdvert(
    int Version,
    Guid DeviceId,
    string PublicKeySpkiBase64Url,
    string Fingerprint,
    string Host,
    int Port,
    string Name,
    IReadOnlyList<string>? Hosts = null)
{
    public IReadOnlyList<string> Candidates()
    {
        if (Hosts is { Count: > 0 })
        {
            return Hosts;
        }

        return string.IsNullOrWhiteSpace(Host) ? [] : [Host];
    }
}

public static class PairingCodec
{
    private static readonly string[] ForbiddenKeys =
    [
        "secret", "private", "priv", "sk", "password", "token", "seed", "mnemonic", "pkey"
    ];

    public static string ToQr(PairingAdvert advert)
    {
        Validate(advert);
        var candidates = advert.Candidates().Where(AdapterRank.IsLanCandidate).Distinct(StringComparer.Ordinal).Take(AdapterRank.MaxCandidates).ToArray();
        var host = candidates.Length > 0 ? candidates[0] : "";
        var parts = new List<string>
        {
            "v=" + advert.Version.ToString(),
            "id=" + Uri.EscapeDataString(advert.DeviceId.ToString("D")),
            "pk=" + Uri.EscapeDataString(advert.PublicKeySpkiBase64Url),
            "fp=" + Uri.EscapeDataString(advert.Fingerprint),
            "host=" + Uri.EscapeDataString(host),
            "port=" + advert.Port.ToString(),
            "name=" + Uri.EscapeDataString(advert.Name)
        };
        if (candidates.Length > 0)
        {
            parts.Add("ips=" + Uri.EscapeDataString(string.Join(",", candidates)));
        }

        return ProtocolLimits.QrScheme + "?" + string.Join("&", parts);
    }

    public static string ToQr(DeviceIdentity identity, string? host, int port) =>
        ToQr(identity, string.IsNullOrWhiteSpace(host) ? [] : [host], port);

    public static string ToQr(DeviceIdentity identity, IReadOnlyList<string> hosts, int port)
    {
        var primary = hosts.FirstOrDefault(AdapterRank.IsLanCandidate) ?? "";
        var advert = new PairingAdvert(
            ProtocolLimits.Version,
            identity.DeviceId,
            Base64Url.Encode(identity.Spki),
            identity.Fingerprint,
            primary,
            port,
            Validators.TrimName(identity.Name),
            hosts);
        return ToQr(advert);
    }

    public static PairingAdvert Parse(string payload)
    {
        if (string.IsNullOrWhiteSpace(payload))
        {
            throw new ProtocolException("Pairing code is empty.");
        }

        var trimmed = payload.Trim();
        if (!trimmed.StartsWith(ProtocolLimits.QrScheme + "?", StringComparison.Ordinal))
        {
            throw new ProtocolException("This is not a FlipSend pairing code.");
        }

        var fields = ParseQuery(trimmed);
        foreach (var key in fields.Keys)
        {
            if (ForbiddenKeys.Contains(key, StringComparer.OrdinalIgnoreCase))
            {
                throw new ProtocolException("Pairing code contains a forbidden field.");
            }
        }

        if (!fields.TryGetValue("v", out var versionText) || versionText != "1")
        {
            throw new ProtocolException("Unsupported pairing code.");
        }

        if (!fields.TryGetValue("id", out var idText) || !Guid.TryParse(idText, out var id))
        {
            throw new ProtocolException("Pairing code is missing a device id.");
        }

        if (!fields.TryGetValue("pk", out var pk) || !fields.TryGetValue("fp", out var fp))
        {
            throw new ProtocolException("Pairing code is missing a public key.");
        }

        if (pk.Contains("PRIVATE", StringComparison.OrdinalIgnoreCase)
            || pk.Contains("BEGIN", StringComparison.OrdinalIgnoreCase))
        {
            throw new ProtocolException("Pairing code must not contain a private key.");
        }

        byte[] spki;
        try
        {
            spki = Base64Url.Decode(pk);
        }
        catch (FormatException)
        {
            throw new ProtocolException("Pairing public key is not valid.");
        }

        try
        {
            using var key = ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(spki, out _);
        }
        catch (CryptographicException)
        {
            throw new ProtocolException("Pairing public key is not valid.");
        }

        var actual = Pin.Fingerprint(spki);
        if (!Pin.FixedEquals(actual, fp.ToLowerInvariant()))
        {
            throw new ProtocolException("Pairing key does not match its fingerprint.");
        }

        var host = fields.GetValueOrDefault("host") ?? "";
        if (host.Length > 0 && !LocalAddressPolicy.IsAllowedHost(host, allowLoopback: false))
        {
            throw new ProtocolException("Pairing address must be a local IP.");
        }

        var hosts = new List<string>();
        if (fields.TryGetValue("ips", out var ipsText))
        {
            foreach (var part in ipsText.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
            {
                if (!LocalAddressPolicy.IsAllowedHost(part, allowLoopback: false))
                {
                    throw new ProtocolException("Pairing address must be a local IP.");
                }

                if (!hosts.Contains(part, StringComparer.Ordinal))
                {
                    hosts.Add(part);
                }
            }
        }

        if (host.Length > 0 && !hosts.Contains(host, StringComparer.Ordinal))
        {
            hosts.Insert(0, host);
        }

        if (!fields.TryGetValue("port", out var portText) || !int.TryParse(portText, out var port) || port is < 1 or > 65535)
        {
            throw new ProtocolException("Pairing port is invalid.");
        }

        var name = Validators.TrimName(fields.GetValueOrDefault("name") ?? "Device");
        var primary = hosts.Count > 0 ? hosts[0] : host;
        var advert = new PairingAdvert(1, id, pk, actual, primary, port, name, hosts);
        Validate(advert);
        return advert;
    }

    public static void Validate(PairingAdvert advert)
    {
        if (advert.Version != ProtocolLimits.Version)
        {
            throw new ProtocolException("Unsupported pairing code.");
        }

        if (!PinFormat.IsWellFormed(advert.Fingerprint))
        {
            throw new ProtocolException("Invalid fingerprint.");
        }
    }

    private static Dictionary<string, string> ParseQuery(string url)
    {
        var queryIndex = url.IndexOf('?', StringComparison.Ordinal);
        var query = url[(queryIndex + 1)..];
        var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var part in query.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var eq = part.IndexOf('=', StringComparison.Ordinal);
            var rawKey = eq < 0 ? part : part[..eq];
            var rawValue = eq < 0 ? "" : part[(eq + 1)..];
            var key = Uri.UnescapeDataString(rawKey);
            if (dict.ContainsKey(key))
            {
                throw new ProtocolException("Pairing code has a duplicate field.");
            }

            dict[key] = Uri.UnescapeDataString(rawValue);
        }

        return dict;
    }
}

public static class Base64Url
{
    public static string Encode(ReadOnlySpan<byte> data)
    {
        return Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }

    public static byte[] Decode(string value)
    {
        var text = value.Replace('-', '+').Replace('_', '/');
        switch (text.Length % 4)
        {
            case 2:
                text += "==";
                break;
            case 3:
                text += "=";
                break;
            case 1:
                throw new FormatException("Invalid base64url length.");
        }

        return Convert.FromBase64String(text);
    }
}
