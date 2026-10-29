using System.Security.Cryptography;

namespace StellarSend.Core.Security;

public interface IKeyProtector
{
    byte[] Protect(byte[] plaintext);
    byte[] Unprotect(byte[] ciphertext);
}

/// <summary>
/// Windows DPAPI protector. Pairing keys are the only bytes this app encrypts at rest.
/// </summary>
public sealed class DpapiKeyProtector : IKeyProtector
{
    private static readonly byte[] Entropy = "stellarsend-pairing-v1"u8.ToArray();

    public byte[] Protect(byte[] plaintext)
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException(
                "Pairing keys are protected with Windows DPAPI. This operating system cannot store them.");
        }

        return ProtectedData.Protect(plaintext, Entropy, DataProtectionScope.CurrentUser);
    }

    public byte[] Unprotect(byte[] ciphertext)
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException(
                "Pairing keys are protected with Windows DPAPI. This operating system cannot store them.");
        }

        return ProtectedData.Unprotect(ciphertext, Entropy, DataProtectionScope.CurrentUser);
    }
}
