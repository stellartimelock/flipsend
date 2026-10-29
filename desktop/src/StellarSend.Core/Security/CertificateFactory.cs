using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using StellarSend.Core.Protocol;

namespace StellarSend.Core.Security;

public static class CertificateFactory
{
    public const string ServerAuthOid = "1.3.6.1.5.5.7.3.1";
    public const string ClientAuthOid = "1.3.6.1.5.5.7.3.2";

    public static X509Certificate2 CreateDeviceCertificate(Guid deviceId)
    {
        using var ecdsa = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        return CreateDeviceCertificate(deviceId, ecdsa);
    }

    public static bool HasDeviceUsages(X509Certificate2 certificate)
    {
        foreach (var extension in certificate.Extensions)
        {
            if (extension is X509EnhancedKeyUsageExtension eku)
            {
                return eku.EnhancedKeyUsages[ServerAuthOid] is not null
                    && eku.EnhancedKeyUsages[ClientAuthOid] is not null;
            }
        }

        return false;
    }

    /// <summary>
    /// Re-issues the same key with Client/Server Auth so both sides present the cert.
    /// The pairing pin is the public key hash, so the existing pair still matches.
    /// </summary>
    public static X509Certificate2 EnsureDeviceUsages(X509Certificate2 existing)
    {
        if (HasDeviceUsages(existing))
        {
            return existing;
        }

        using var ecdsa = existing.GetECDsaPrivateKey()
            ?? throw new ProtocolException("Stored device identity is invalid.");
        var commonName = existing.GetNameInfo(X509NameType.SimpleName, false);
        if (!Guid.TryParse(commonName, out var deviceId))
        {
            throw new ProtocolException("Stored device identity is invalid.");
        }

        var replaced = CreateDeviceCertificate(deviceId, ecdsa);
        existing.Dispose();
        return replaced;
    }

    private static X509Certificate2 CreateDeviceCertificate(Guid deviceId, ECDsa ecdsa)
    {
        var request = new CertificateRequest($"CN={deviceId:D}", ecdsa, HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, critical: true));
        var eku = new OidCollection
        {
            new Oid(ServerAuthOid),
            new Oid(ClientAuthOid)
        };
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(eku, false));
        var issued = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(5));
        var pfx = issued.Export(X509ContentType.Pfx);
        try
        {
            return X509CertificateLoader.LoadPkcs12(pfx, ReadOnlySpan<char>.Empty, X509KeyStorageFlags.Exportable);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(pfx);
        }
    }
}
