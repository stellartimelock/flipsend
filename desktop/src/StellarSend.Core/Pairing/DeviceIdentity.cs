using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using StellarSend.Core.Files;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;

namespace StellarSend.Core.Pairing;

public sealed class DeviceIdentity : IDisposable
{
    public required Guid DeviceId { get; init; }
    public required string Name { get; set; }
    public required X509Certificate2 Certificate { get; init; }

    public string Fingerprint => Pin.Fingerprint(Certificate);

    public byte[] Spki => Certificate.PublicKey.ExportSubjectPublicKeyInfo();

    public void Dispose() => Certificate.Dispose();

    public static DeviceIdentity Create(string displayName)
    {
        var certificate = CertificateFactory.CreateDeviceCertificate(Guid.NewGuid());
        var deviceId = Guid.Parse(certificate.GetNameInfo(X509NameType.SimpleName, false));
        return new DeviceIdentity
        {
            DeviceId = deviceId,
            Name = Validators.TrimName(displayName),
            Certificate = certificate
        };
    }
}

public sealed class IdentityStore
{
    private readonly IKeyProtector _protector;

    public IdentityStore(IKeyProtector protector)
    {
        _protector = protector;
    }

    public DeviceIdentity LoadOrCreate(string path, string displayName)
    {
        if (File.Exists(path))
        {
            var protectedBytes = File.ReadAllBytes(path);
            var pfx = _protector.Unprotect(protectedBytes);
            try
            {
                var certificate = X509CertificateLoader.LoadPkcs12(pfx, ReadOnlySpan<char>.Empty, X509KeyStorageFlags.Exportable);
                var commonName = certificate.GetNameInfo(X509NameType.SimpleName, false);
                if (!Guid.TryParse(commonName, out var id))
                {
                    throw new ProtocolException("Stored device identity is invalid.");
                }

                if (!CertificateFactory.HasDeviceUsages(certificate))
                {
                    certificate = CertificateFactory.EnsureDeviceUsages(certificate);
                    Save(path, certificate);
                }

                return new DeviceIdentity
                {
                    DeviceId = id,
                    Name = Validators.TrimName(displayName),
                    Certificate = certificate
                };
            }
            finally
            {
                CryptographicOperations.ZeroMemory(pfx);
            }
        }

        var deviceId = Guid.NewGuid();
        var created = CertificateFactory.CreateDeviceCertificate(deviceId);
        Save(path, created);
        return new DeviceIdentity
        {
            DeviceId = deviceId,
            Name = Validators.TrimName(displayName),
            Certificate = created
        };
    }

    public void Replace(string path, DeviceIdentity identity) => Save(path, identity.Certificate);

    public byte[] Protect(DeviceIdentity identity)
    {
        var pfx = identity.Certificate.Export(X509ContentType.Pfx);
        try
        {
            return _protector.Protect(pfx);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(pfx);
        }
    }

    public static void Delete(string path) => DurableFile.DeleteAll(path);

    private void Save(string path, X509Certificate2 certificate)
    {
        var pfx = certificate.Export(X509ContentType.Pfx);
        try
        {
            var protectedBytes = _protector.Protect(pfx);
            try
            {
                DurableFile.WriteOrThrow(path, protectedBytes, allowShrink: true);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(protectedBytes);
            }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(pfx);
        }
    }
}
