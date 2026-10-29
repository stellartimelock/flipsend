using StellarSend.Core.Protocol;
using StellarSend.Core.Security;

namespace StellarSend.Core.Pairing;

/// <summary>
/// Trust is only for a presented cert that is different from the saved pin.
/// A blank presented pin is a closed socket, not a new key.
/// </summary>
public static class TrustNewKey
{
    public const string Title = "Trust new key";
    public const string Action = "Trust new key";

    public static string Message(string? phoneName)
    {
        var name = string.IsNullOrWhiteSpace(phoneName) ? "your phone" : phoneName.Trim();
        return name + " has a new key. This is the phone you already paired. Trust the new key to keep using it.";
    }

    public static bool Applies(bool hasPairedPeer, string? code, string? message)
    {
        if (!hasPairedPeer)
        {
            return false;
        }

        var text = message?.ToLowerInvariant() ?? "";
        return code == ErrorCodes.KeyChanged
            || text.Contains("key changed", StringComparison.Ordinal)
            || HandshakeFailed(message);
    }

    public static bool HandshakeFailed(string? message)
    {
        var text = message?.ToLowerInvariant() ?? "";
        if (text.Length == 0)
        {
            return false;
        }

        return text.Contains("key changed", StringComparison.Ordinal)
            || text.Contains("has a new key", StringComparison.Ordinal)
            || text.Contains("secure connection closed", StringComparison.Ordinal)
            || text.Contains("handshake did not finish", StringComparison.Ordinal)
            || text.Contains("did not match the saved pin", StringComparison.Ordinal);
    }

    public static bool ShouldPrompt(
        bool hasPairedPeer,
        string? code,
        string? message,
        string? presentedPin,
        string? storedPin)
    {
        if (!Applies(hasPairedPeer, code, message))
        {
            return false;
        }

        if (string.IsNullOrWhiteSpace(presentedPin) || !PinFormat.IsWellFormed(presentedPin))
        {
            return false;
        }

        if (PinFormat.IsWellFormed(storedPin) && Pin.FixedEquals(presentedPin, storedPin))
        {
            return false;
        }

        return true;
    }

    /// <summary>KEY_CHANGED is only for a well-formed TLS pin that does not match the saved pin.</summary>
    public static bool ShouldRaiseKeyChanged(string? presentedPin, string? storedPin)
    {
        if (!PinFormat.IsWellFormed(storedPin) || !PinFormat.IsWellFormed(presentedPin))
        {
            return false;
        }

        return !Pin.FixedEquals(presentedPin, storedPin);
    }

    public static string ShortPin(string? fingerprint)
    {
        var pin = fingerprint?.Trim() ?? "";
        if (pin.Length == 0)
        {
            return "none";
        }

        return pin.Length <= 8 ? pin.ToLowerInvariant() : pin[..8].ToLowerInvariant();
    }
}
