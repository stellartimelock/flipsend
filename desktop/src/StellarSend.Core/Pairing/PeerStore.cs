using System.Security.Cryptography;
using System.Text.Json;
using StellarSend.Core.Files;
using StellarSend.Core.Security;

namespace StellarSend.Core.Pairing;

public sealed record PeerRecord(
    Guid DeviceId,
    string Name,
    string Fingerprint,
    string PublicKeySpkiBase64Url,
    string? LastHost,
    int Port,
    DateTimeOffset PairedAt,
    IReadOnlyList<string>? Hosts = null,
    string? PhonePlan = null,
    string? PhoneQuotaKey = null,
    long? PhoneRelayUsed = null,
    long? PhoneRelayLimit = null);

public sealed class PeerStore
{
    private readonly IKeyProtector _protector;

    public PeerStore(IKeyProtector protector)
    {
        _protector = protector;
    }

    public PeerRecord? Load(string path)
    {
        if (!File.Exists(path))
        {
            return null;
        }

        var protectedBytes = File.ReadAllBytes(path);
        var json = _protector.Unprotect(protectedBytes);
        try
        {
            return JsonSerializer.Deserialize<PeerRecord>(json);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(json);
        }
    }

    public byte[] Protect(PeerRecord record)
    {
        var json = JsonSerializer.SerializeToUtf8Bytes(record);
        try
        {
            return _protector.Protect(json);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(json);
        }
    }

    public void Save(string path, PeerRecord record)
    {
        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var protectedBytes = Protect(record);
        try
        {
            DurableFile.WriteOrThrow(path, protectedBytes, allowShrink: true);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(protectedBytes);
        }
    }

    public static void Delete(string path) => DurableFile.DeleteAll(path);
}
