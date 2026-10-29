using System.Text;
using StellarSend.Core.Protocol;

namespace StellarSend.Core.Pairing;

public static class ShortCode
{
    public static string Format(string fingerprint)
    {
        if (!PinFormat.IsWellFormed(fingerprint))
        {
            throw new ProtocolException("Invalid fingerprint.");
        }

        return fingerprint[..4].ToUpperInvariant() + "-" + fingerprint.Substring(4, 4).ToUpperInvariant();
    }

    public static bool LooksLike(string? typed)
    {
        var compact = Compact(typed);
        if (compact.Length != 8)
        {
            return false;
        }

        foreach (var c in compact)
        {
            if (c is < '0' or > '9' and < 'a' or > 'f')
            {
                return false;
            }
        }

        return true;
    }

    public static bool Matches(string? typed, string fingerprint)
    {
        if (!PinFormat.IsWellFormed(fingerprint))
        {
            return false;
        }

        var compact = Compact(typed);
        return compact.Length == 8 && fingerprint.StartsWith(compact, StringComparison.Ordinal);
    }

    public static bool Same(string? left, string? right)
    {
        var a = Compact(left);
        var b = Compact(right);
        return a.Length == 8 && string.Equals(a, b, StringComparison.Ordinal);
    }

    private static string Compact(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return "";
        }

        var builder = new StringBuilder(value.Length);
        foreach (var c in value.Trim())
        {
            if (c is '-' or ' ')
            {
                continue;
            }

            builder.Append(char.ToLowerInvariant(c));
        }

        return builder.ToString();
    }
}
