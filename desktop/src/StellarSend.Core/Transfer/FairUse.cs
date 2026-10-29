using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace StellarSend.Core.Transfer;

/// <summary>
/// Relay caps. Free accounts get a small monthly allowance. Permanent Elite gets 1 GB.
/// Two owner emails can be unlimited, and only the server can grant that.
/// Local, LAN, and USB transfers never count.
/// </summary>
public static class FairUse
{
    public const long BytesPerMb = 1_000_000;
    public const long BytesPerGb = 1_000_000_000;
    public const long FreeMb = 100;
    public const long FreeBytes = FreeMb * BytesPerMb;
    public const long EliteBytes = 1 * BytesPerGb;
    public const long FreePoolGb = 50;
    public const long FreePoolBytes = FreePoolGb * BytesPerGb;
    public const long FreeRateMbPerMinute = 5;
    public const long FreeRateBytes = FreeRateMbPerMinute * BytesPerMb;
    public const string EnvGb = "RELAY_FAIR_USE_GB";
    public const string EnvEliteGb = "RELAY_ELITE_GB";
    public const string EnvPoolGb = "RELAY_FREE_POOL_GB";
    public const string EnvRateMb = "RELAY_FREE_RATE_MB_PER_MIN";
    public const string EnvElite = "RELAY_ELITE_ACCOUNTS";
    public const string EnvFamily = "RELAY_FAMILY_ELITE";
    public const string EnvUnlimitedAccounts = "RELAY_UNLIMITED_ACCOUNTS";
    public const string EnvUnlimitedSecret = "RELAY_UNLIMITED_SECRET";
    public const string PoolAccount = "free-pool";
    public const string ResetsOnTheFirst = "Resets on the 1st.";
    public const string BusyMessage = "Relay busy, Wi-Fi transfers still work";
    public const string RateMessage =
        "The relay is slowing this account for a minute. Wi-Fi transfers still work.";

    public static long DefaultBytes => FreeBytes;

    public static string PoolPeriod(string month) => "pool:" + month;

    public static string RateSlot(DateTimeOffset utc) =>
        utc.UtcDateTime.ToString("yyyy-MM-ddTHH:mm", CultureInfo.InvariantCulture);

    public static string Month(DateTimeOffset utc) =>
        utc.UtcDateTime.ToString("yyyy-MM", CultureInfo.InvariantCulture);

    /// <summary>Ledger period. Prefixed so it does not share a row with a plan period.</summary>
    public static string PeriodId(string month) => "fair:" + month;

    public static string FormatGb(long bytes)
    {
        if (bytes < 0)
        {
            bytes = 0;
        }

        var gb = decimal.Round(bytes / (decimal)BytesPerGb, 2, MidpointRounding.AwayFromZero);
        return gb.ToString("0.##", CultureInfo.InvariantCulture) + " GB";
    }

    public static string Label(long used, long limit) =>
        limit <= 0
            ? "Usage appears after the relay answers."
            : FormatGb(used) + " of " + FormatGb(limit) + " relay this month";

    public static string PauseMessage(long limitBytes) =>
        "This month's relay data is used up. Wi-Fi transfers still work.";

    /// <summary>Only relay egress counts. Direct Wi-Fi, LAN, and USB do not.</summary>
    public static bool Counts(string? path) => path == TransferPath.Relay;

    public static long? ParseGb(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            return null;
        }

        if (!decimal.TryParse(raw.Trim(), NumberStyles.Number, CultureInfo.InvariantCulture, out var gb) || gb <= 0)
        {
            return null;
        }

        var bytes = decimal.Round(gb * BytesPerGb, 0, MidpointRounding.AwayFromZero);
        if (bytes > long.MaxValue)
        {
            return null;
        }

        return (long)bytes;
    }

    public static long? ParseMb(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            return null;
        }

        if (!decimal.TryParse(raw.Trim(), NumberStyles.Number, CultureInfo.InvariantCulture, out var mb) || mb < 0)
        {
            return null;
        }

        var bytes = decimal.Round(mb * BytesPerMb, 0, MidpointRounding.AwayFromZero);
        if (bytes > long.MaxValue)
        {
            return null;
        }

        return (long)bytes;
    }

    public static long ResolveDefaultBytes(string? env, long? tiersBytes)
    {
        var fromEnv = ParseGb(env);
        if (fromEnv is > 0)
        {
            return fromEnv.Value;
        }

        if (tiersBytes is > 0)
        {
            return tiersBytes.Value;
        }

        return DefaultBytes;
    }

    public static long ResolveBytes(string? envGb, long? tiersBytes, long fallback)
    {
        var fromEnv = ParseGb(envGb);
        if (fromEnv is > 0)
        {
            return fromEnv.Value;
        }

        if (tiersBytes is > 0)
        {
            return tiersBytes.Value;
        }

        return fallback;
    }
}

/// <summary>
/// Owner emails that can be unlimited. The grant is a server check of an HMAC,
/// not a boolean the app can set.
/// </summary>
public static class UnlimitedAccounts
{
    public const string ProofPrefix = "stellarsend-unlimited-v1|";

    public static readonly string[] Defaults =
    [
        "stellartimelock@gmail.com"
    ];

    public static bool TryNormalize(string? raw, out string email)
    {
        email = "";
        if (string.IsNullOrWhiteSpace(raw))
        {
            return false;
        }

        var trimmed = raw.Trim().ToLowerInvariant();
        var at = trimmed.IndexOf('@');
        if (at <= 0 || at != trimmed.LastIndexOf('@') || at >= trimmed.Length - 1)
        {
            return false;
        }

        if (trimmed.Length > 320 || trimmed.Any(c => char.IsWhiteSpace(c) || char.IsControl(c)))
        {
            return false;
        }

        email = trimmed;
        return true;
    }

    public static IReadOnlySet<string> ParseList(string? raw, bool useDefaultsWhenEmpty)
    {
        var set = new HashSet<string>(StringComparer.Ordinal);
        if (string.IsNullOrWhiteSpace(raw))
        {
            if (useDefaultsWhenEmpty)
            {
                foreach (var email in Defaults)
                {
                    set.Add(email);
                }
            }

            return set;
        }

        foreach (var part in raw.Split([',', ';', '\n', '\r', '\t', ' '], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (TryNormalize(part, out var email))
            {
                set.Add(email);
            }
        }

        return set;
    }

    public static string Proof(string secret, string email)
    {
        var key = Encoding.UTF8.GetBytes(secret);
        var body = Encoding.UTF8.GetBytes(ProofPrefix + email);
        return Convert.ToHexString(HMACSHA256.HashData(key, body)).ToLowerInvariant();
    }

    public static bool ProofMatches(string secret, string email, string? presented)
    {
        if (string.IsNullOrEmpty(secret) || presented is not { Length: 64 })
        {
            return false;
        }

        var expected = Encoding.UTF8.GetBytes(Proof(secret, email));
        var actual = Encoding.UTF8.GetBytes(presented.Trim().ToLowerInvariant());
        return expected.Length == actual.Length && CryptographicOperations.FixedTimeEquals(expected, actual);
    }
}

public static class FairUseAccounts
{
    public const int MaxLength = 1024;

    public static bool TryNormalize(string? raw, out string id)
    {
        id = "";
        if (string.IsNullOrWhiteSpace(raw))
        {
            return false;
        }

        var trimmed = raw.Trim();
        if (trimmed.Length is 0 or > MaxLength)
        {
            return false;
        }

        foreach (var c in trimmed)
        {
            if (c is '|' or '\r' or '\n' or '\0' || char.IsControl(c))
            {
                return false;
            }
        }

        if (trimmed.Length == 64 && trimmed.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f' or >= 'A' and <= 'F'))
        {
            id = trimmed.ToLowerInvariant();
            return true;
        }

        id = trimmed;
        return true;
    }

    public static IReadOnlySet<string> ParseList(string? raw)
    {
        var set = new HashSet<string>(StringComparer.Ordinal);
        if (string.IsNullOrWhiteSpace(raw))
        {
            return set;
        }

        foreach (var part in raw.Split([',', ';', '\n', '\r', '\t', ' '], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (TryNormalize(part, out var id))
            {
                set.Add(id);
            }
        }

        return set;
    }
}

/// <summary>
/// Server-side relay caps. <see cref="DefaultBytes"/> is the free monthly allowance.
/// Permanent Elite uses <see cref="EliteBytes"/>. Unlimited is a separate server grant.
/// </summary>
public sealed class FairUsePolicy
{
    public FairUsePolicy(
        long defaultBytes,
        IEnumerable<string>? elite,
        IEnumerable<string>? family,
        long eliteBytes = FairUse.EliteBytes,
        long poolBytes = FairUse.FreePoolBytes,
        long ratePerMinute = FairUse.FreeRateBytes,
        IEnumerable<string>? unlimitedAccounts = null,
        string? unlimitedSecret = null)
    {
        DefaultBytes = defaultBytes > 0 ? defaultBytes : FairUse.DefaultBytes;
        EliteBytes = eliteBytes > 0 ? eliteBytes : FairUse.EliteBytes;
        PoolBytes = poolBytes > 0 ? poolBytes : FairUse.FreePoolBytes;
        RatePerMinute = ratePerMinute >= 0 ? ratePerMinute : FairUse.FreeRateBytes;
        Elite = Copy(elite);
        Family = Copy(family);
        Unlimited = unlimitedAccounts is null
            ? UnlimitedAccounts.ParseList(null, useDefaultsWhenEmpty: true)
            : UnlimitedAccounts.ParseList(string.Join(',', unlimitedAccounts), useDefaultsWhenEmpty: false);
        UnlimitedSecret = unlimitedSecret?.Trim() ?? "";
    }

    public static FairUsePolicy Default { get; } = new(FairUse.DefaultBytes, null, null);

    public long DefaultBytes { get; }

    public long EliteBytes { get; }

    public long PoolBytes { get; }

    public long RatePerMinute { get; }

    public IReadOnlySet<string> Elite { get; }

    /// <summary>Permanent friends-and-family Elite entitlement keys. They get the Elite allowance, not unlimited.</summary>
    public IReadOnlySet<string> Family { get; }

    public IReadOnlySet<string> Unlimited { get; }

    public string UnlimitedSecret { get; }

    public bool IsElite(string? key) => key is not null && (Elite.Contains(key) || Family.Contains(key));

    public bool IsPermanentFamily(string? key) => key is not null && Family.Contains(key);

    public bool GrantsUnlimited(string? account, string? proof)
    {
        if (string.IsNullOrEmpty(UnlimitedSecret))
        {
            return false;
        }

        if (!UnlimitedAccounts.TryNormalize(account, out var email) || !Unlimited.Contains(email))
        {
            return false;
        }

        return UnlimitedAccounts.ProofMatches(UnlimitedSecret, email, proof);
    }

    public static FairUsePolicy FromEnvironment(TierTable? tiers = null)
    {
        var free = FairUse.ResolveBytes(
            Environment.GetEnvironmentVariable(FairUse.EnvGb),
            tiers?.FairUseBytes,
            FairUse.FreeBytes);
        var elite = FairUse.ResolveBytes(
            Environment.GetEnvironmentVariable(FairUse.EnvEliteGb),
            tiers?.EliteRelayBytes,
            FairUse.EliteBytes);
        var pool = FairUse.ResolveBytes(
            Environment.GetEnvironmentVariable(FairUse.EnvPoolGb),
            tiers?.FreePoolBytes,
            FairUse.FreePoolBytes);
        var rateEnv = Environment.GetEnvironmentVariable(FairUse.EnvRateMb);
        var rate = string.IsNullOrWhiteSpace(rateEnv)
            ? tiers?.FreeRateBytes ?? FairUse.FreeRateBytes
            : FairUse.ParseMb(rateEnv) ?? FairUse.FreeRateBytes;
        var accountEnv = Environment.GetEnvironmentVariable(FairUse.EnvUnlimitedAccounts);
        var accounts = UnlimitedAccounts.ParseList(accountEnv, useDefaultsWhenEmpty: true);
        return new FairUsePolicy(
            free,
            FairUseAccounts.ParseList(Environment.GetEnvironmentVariable(FairUse.EnvElite)),
            FairUseAccounts.ParseList(Environment.GetEnvironmentVariable(FairUse.EnvFamily)),
            elite,
            pool,
            rate,
            accounts,
            Environment.GetEnvironmentVariable(FairUse.EnvUnlimitedSecret));
    }

    private static HashSet<string> Copy(IEnumerable<string>? keys)
    {
        var set = new HashSet<string>(StringComparer.Ordinal);
        if (keys is null)
        {
            return set;
        }

        foreach (var key in keys)
        {
            if (FairUseAccounts.TryNormalize(key, out var id))
            {
                set.Add(id);
            }
        }

        return set;
    }
}

public sealed record FairUseAdminResult(int Status, string Json);

public static class FairUseAdmin
{
    public const long MaxCapBytes = 100_000L * FairUse.BytesPerGb;

    public static bool IsPath(string? path) => path is "/v1/admin/cap" or "/v1/admin/cap/";

    public static FairUseAdminResult Apply(QuotaLedger ledger, FairUsePolicy policy, string? json, DateTimeOffset utc)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            return Error(400, "Send an account and an action.");
        }

        string account;
        string action;
        decimal? capGb;
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                return Error(400, "Send an account and an action.");
            }

            var rawAccount = root.TryGetProperty("account", out var accountNode) && accountNode.ValueKind == JsonValueKind.String
                ? accountNode.GetString()
                : null;
            if (!FairUseAccounts.TryNormalize(rawAccount, out account))
            {
                return Error(400, "account is required.");
            }

            action = root.TryGetProperty("action", out var actionNode) && actionNode.ValueKind == JsonValueKind.String
                ? actionNode.GetString()?.Trim().ToLowerInvariant() ?? ""
                : "";
            capGb = null;
            if (root.TryGetProperty("capGb", out var capNode) && capNode.ValueKind == JsonValueKind.Number)
            {
                capGb = capNode.GetDecimal();
            }
        }
        catch (JsonException)
        {
            return Error(400, "Send an account and an action.");
        }

        var month = FairUse.Month(utc);
        var period = FairUse.PeriodId(month);
        var current = ledger.EffectiveCap(account, policy.DefaultBytes);
        switch (action)
        {
            case "raise":
            case "lower":
            {
                if (capGb is null)
                {
                    return Error(400, "capGb is required.");
                }

                var bytes = GbToBytes(capGb.Value);
                if (bytes is null)
                {
                    return Error(400, "capGb must be a positive number of gigabytes.");
                }

                if (action == "raise" && bytes.Value <= current)
                {
                    return Error(400, "capGb must be higher than the current cap.");
                }

                if (action == "lower" && bytes.Value >= current)
                {
                    return Error(400, "capGb must be lower than the current cap.");
                }

                ledger.SetCap(account, bytes.Value);
                break;
            }
            case "reset":
                ledger.ClearCap(account);
                ledger.ResetPeriod(account, period);
                break;
            default:
                return Error(400, "action must be raise, lower, or reset.");
        }

        var cap = ledger.EffectiveCap(account, policy.DefaultBytes);
        var used = ledger.Used(account, period);
        return Ok(action, cap, used, month, policy.DefaultBytes);
    }

    private static long? GbToBytes(decimal gb)
    {
        if (gb <= 0)
        {
            return null;
        }

        var bytes = decimal.Round(gb * FairUse.BytesPerGb, 0, MidpointRounding.AwayFromZero);
        if (bytes > MaxCapBytes || bytes > long.MaxValue)
        {
            return null;
        }

        return (long)bytes;
    }

    private static FairUseAdminResult Ok(string action, long capBytes, long usedBytes, string month, long defaultBytes)
    {
        var capGb = capBytes / (decimal)FairUse.BytesPerGb;
        var defaultGb = defaultBytes / (decimal)FairUse.BytesPerGb;
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteBoolean("ok", true);
            writer.WriteString("action", action);
            writer.WriteNumber("capGb", capGb);
            writer.WriteNumber("capBytes", capBytes);
            writer.WriteNumber("usedBytes", usedBytes);
            writer.WriteString("month", month);
            writer.WriteNumber("defaultGb", defaultGb);
            writer.WriteString("resets", "1st");
            writer.WriteEndObject();
        }

        return new FairUseAdminResult(200, System.Text.Encoding.UTF8.GetString(stream.ToArray()));
    }

    private static FairUseAdminResult Error(int status, string error)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteBoolean("ok", false);
            writer.WriteString("error", error);
            writer.WriteEndObject();
        }

        return new FairUseAdminResult(status, System.Text.Encoding.UTF8.GetString(stream.ToArray()));
    }
}
