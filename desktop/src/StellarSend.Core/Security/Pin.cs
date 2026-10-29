using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace StellarSend.Core.Security;

public static class Pin
{
    public static string Fingerprint(X509Certificate2 certificate) =>
        Fingerprint(certificate.PublicKey.ExportSubjectPublicKeyInfo());

    public static string Fingerprint(ReadOnlySpan<byte> spki)
    {
        var hash = SHA256.HashData(spki);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    public static bool FixedEquals(string? left, string? right)
    {
        if (!Protocol.PinFormat.IsWellFormed(left) || !Protocol.PinFormat.IsWellFormed(right))
        {
            return false;
        }

        var a = Encoding.ASCII.GetBytes(left!);
        var b = Encoding.ASCII.GetBytes(right!);
        return CryptographicOperations.FixedTimeEquals(a, b);
    }
}
