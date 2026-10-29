using StellarSend.Core.Security;

namespace StellarSend.Core.Transfer;

/// <summary>
/// Log-only diagnostics for a key_changed reject. Comparisons and the thrown
/// exception stay the same.
/// </summary>
public static class KeyChangedLog
{
    public const string LocalCheck = "local check";
    public const string PhoneSent = "phone-sent key_changed";

    private static ILog _sink = NullLog.Instance;

    public static ILog Sink
    {
        get => _sink;
        set => _sink = value ?? NullLog.Instance;
    }

    public static string? LastPresented { get; private set; }

    public static void Write(string side, string? presented, string? pinned, string? deviceId)
    {
        LastPresented = presented;
        Sink.Warn(Format(side, presented, pinned, deviceId));
    }

    public static string Format(string side, string? presented, string? pinned, string? deviceId) =>
        "key_changed raised by " + side
        + ". presented=" + EightHex(presented)
        + " pinned=" + EightHex(pinned)
        + " device=" + EightHex(deviceId);

    public static string EightHex(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return "none";
        }

        Span<char> hex = stackalloc char[8];
        var n = 0;
        foreach (var c in value)
        {
            if (!Uri.IsHexDigit(c))
            {
                continue;
            }

            hex[n++] = char.ToLowerInvariant(c);
            if (n == 8)
            {
                break;
            }
        }

        return n == 0 ? "none" : new string(hex[..n]);
    }
}

public static class KeyChangeRetry
{
    public const int MaxConsecutive = 3;
    public static readonly TimeSpan RecentPin = TimeSpan.FromDays(7);
    public const string Retrying = "Retrying.";

    public static bool IsFallback(string? path)
    {
        var text = path ?? "";
        return text.Contains("relay", StringComparison.OrdinalIgnoreCase)
            || string.Equals(text, TransferPath.Bluetooth, StringComparison.Ordinal);
    }

    public static bool IsLan(string? path)
    {
        var text = path?.Trim() ?? "";
        if (text.Length == 0)
        {
            return true;
        }

        if (IsFallback(text))
        {
            return false;
        }

        return string.Equals(text, TransferPath.Direct, StringComparison.Ordinal)
            || string.Equals(text, TransferPath.Cross, StringComparison.Ordinal)
            || string.Equals(text, ConnectionState.Lan, StringComparison.Ordinal)
            || text.Contains("Wi-Fi", StringComparison.OrdinalIgnoreCase);
    }

    public static bool PinSucceededRecently(DateTimeOffset? lastPinOk, DateTimeOffset now) =>
        lastPinOk is { } ok && now - ok <= RecentPin;

    /// <summary>
    /// A single key_changed on relay or Bluetooth is retryable when this pin
    /// worked in the last 7 days. LAN, or 3 in a row, stays terminal.
    /// </summary>
    public static bool IsTransient(string? path, DateTimeOffset? lastPinOk, int consecutive, DateTimeOffset now)
    {
        if (consecutive >= MaxConsecutive)
        {
            return false;
        }

        if (IsLan(path) || !IsFallback(path))
        {
            return false;
        }

        return PinSucceededRecently(lastPinOk, now);
    }

    public static bool ShouldRetry(string? path, DateTimeOffset? lastPinOk, int consecutive, int attempt, DateTimeOffset now) =>
        RetryPolicy.ShouldRetry(false, false, attempt, IsTransient(path, lastPinOk, consecutive, now));

    /// <summary>
    /// Surfshark (and similar) can give the phone and PC the same tun address, so
    /// this computer dials itself. The presented cert is our own key, not a new
    /// phone pin. Classify that as a self-dial and retry the relay.
    /// </summary>
    public static bool IsOwnKey(Exception? ex, string? localFingerprint)
    {
        if (ex is not Protocol.PeerRejectedException rejected || rejected.Code != Protocol.ErrorCodes.KeyChanged)
        {
            return false;
        }

        return DeliveryFault.IsSelf(KeyChangedLog.LastPresented, localFingerprint);
    }

    public static Exception MapOwnKey(Exception ex, string? localFingerprint) =>
        IsOwnKey(ex, localFingerprint) ? new SelfDialException() : ex;
}

public static class PinOkStore
{
    public const string FileName = "last-pin-ok";

    public static DateTimeOffset? Load(string dataDirectory, DateTimeOffset? pairedAt)
    {
        var path = Path.Combine(dataDirectory, FileName);
        if (File.Exists(path))
        {
            var text = File.ReadAllText(path).Trim();
            if (DateTimeOffset.TryParse(text, out var stored))
            {
                return stored;
            }
        }

        return pairedAt;
    }

    public static void Save(string dataDirectory, DateTimeOffset when)
    {
        Directory.CreateDirectory(dataDirectory);
        File.WriteAllText(Path.Combine(dataDirectory, FileName), when.ToString("O"));
    }
}
