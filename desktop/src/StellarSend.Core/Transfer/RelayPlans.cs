using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using StellarSend.Core.Security;

namespace StellarSend.Core.Transfer;

public sealed record RelayTier(
    string Id,
    string Title,
    string? ProductId,
    string? BasePlanId,
    decimal PriceUsd,
    decimal RelayGb,
    long RelayBytes,
    string Local,
    string PcIncluded,
    string Speed,
    bool Recommended)
{
    public bool Paid => PriceUsd > 0;

    public string RelayLabel => RelayGb.ToString("0.##", CultureInfo.InvariantCulture) + " GB";

    public string FallbackPrice =>
        Paid ? "$" + PriceUsd.ToString("0.00", CultureInfo.InvariantCulture) + " / month" : "Included";

    public decimal EgressCost(decimal perGb) => RelayGb * perGb;

    public decimal NetRevenue(decimal playFeeRate) => PriceUsd * (1 - playFeeRate);

    public bool StaysProfitable(decimal perGb, decimal playFeeRate) =>
        !Paid || EgressCost(perGb) <= NetRevenue(playFeeRate);
}

public sealed class TierTable
{
    public required decimal EgressUsdPerGb { get; init; }
    public required decimal PlayFeeRate { get; init; }
    public required long BytesPerGb { get; init; }
    public required decimal AppPriceUsd { get; init; }
    public required string PackageName { get; init; }
    public required IReadOnlyList<RelayTier> Tiers { get; init; }
    public decimal FairUseGb { get; init; } = FairUse.FreeBytes / (decimal)FairUse.BytesPerGb;
    public long FairUseBytes { get; init; } = FairUse.FreeBytes;
    public long EliteRelayBytes { get; init; } = FairUse.EliteBytes;
    public long FreePoolBytes { get; init; } = FairUse.FreePoolBytes;
    public long FreeRateBytes { get; init; } = FairUse.FreeRateBytes;

    public RelayTier Free => Tiers.First(tier => tier.Id == "free");
    public RelayTier? Recommended => Tiers.FirstOrDefault(tier => tier.Recommended);

    public RelayTier? ById(string? id) => Tiers.FirstOrDefault(tier => tier.Id == id);

    public RelayTier? ByProduct(string? productId) =>
        string.IsNullOrWhiteSpace(productId) ? null : Tiers.FirstOrDefault(tier => tier.ProductId == productId);

    public static TierTable Parse(string json)
    {
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        var bytesPerGb = root.GetProperty("bytesPerGb").GetInt64();
        if (bytesPerGb <= 0)
        {
            throw new InvalidOperationException("bytesPerGb must be positive.");
        }

        var tiers = new List<RelayTier>();
        foreach (var item in root.GetProperty("tiers").EnumerateArray())
        {
            var gb = item.GetProperty("relayGb").GetDecimal();
            var bytes = decimal.Round(gb * bytesPerGb, 0, MidpointRounding.AwayFromZero);
            tiers.Add(new RelayTier(
                item.GetProperty("id").GetString() ?? "",
                item.GetProperty("title").GetString() ?? "",
                TextOrNull(item, "productId"),
                TextOrNull(item, "basePlanId"),
                item.GetProperty("priceUsd").GetDecimal(),
                gb,
                (long)bytes,
                Text(item, "local", "Unlimited"),
                Text(item, "pcIncluded", "Included"),
                Text(item, "speed", "Full speed"),
                item.TryGetProperty("recommended", out var recommended) && recommended.ValueKind == JsonValueKind.True));
        }

        if (tiers.All(tier => tier.Id != "free"))
        {
            throw new InvalidOperationException("The plan table needs a free tier.");
        }

        var fairGb = FairUse.FreeBytes / (decimal)FairUse.BytesPerGb;
        if (root.TryGetProperty("fairUseGb", out var fairNode) && fairNode.ValueKind == JsonValueKind.Number)
        {
            var parsed = fairNode.GetDecimal();
            if (parsed > 0)
            {
                fairGb = parsed;
            }
        }

        var fairBytes = (long)decimal.Round(fairGb * bytesPerGb, 0, MidpointRounding.AwayFromZero);
        var eliteBytes = PositiveBytes(root, "eliteRelayGb", bytesPerGb, FairUse.EliteBytes);
        var poolBytes = PositiveBytes(root, "freePoolGb", bytesPerGb, FairUse.FreePoolBytes);
        var rateBytes = PositiveMb(root, "freeRateMbPerMin", FairUse.FreeRateBytes);
        return new TierTable
        {
            EgressUsdPerGb = root.GetProperty("egressUsdPerGb").GetDecimal(),
            PlayFeeRate = root.GetProperty("playFeeRate").GetDecimal(),
            BytesPerGb = bytesPerGb,
            AppPriceUsd = root.GetProperty("appPriceUsd").GetDecimal(),
            PackageName = Text(root, "packageName", RelayPlans.Package),
            Tiers = tiers,
            FairUseGb = fairGb,
            FairUseBytes = fairBytes,
            EliteRelayBytes = eliteBytes,
            FreePoolBytes = poolBytes,
            FreeRateBytes = rateBytes
        };
    }

    private static long PositiveBytes(JsonElement root, string name, long bytesPerGb, long fallback)
    {
        if (!root.TryGetProperty(name, out var node) || node.ValueKind != JsonValueKind.Number)
        {
            return fallback;
        }

        var gb = node.GetDecimal();
        if (gb <= 0)
        {
            return fallback;
        }

        return (long)decimal.Round(gb * bytesPerGb, 0, MidpointRounding.AwayFromZero);
    }

    private static long PositiveMb(JsonElement root, string name, long fallback)
    {
        if (!root.TryGetProperty(name, out var node) || node.ValueKind != JsonValueKind.Number)
        {
            return fallback;
        }

        var mb = node.GetDecimal();
        if (mb < 0)
        {
            return fallback;
        }

        return (long)decimal.Round(mb * FairUse.BytesPerMb, 0, MidpointRounding.AwayFromZero);
    }

    public static TierTable Load(string path) => Parse(File.ReadAllText(path));

    private static string? TextOrNull(JsonElement item, string name)
    {
        if (!item.TryGetProperty(name, out var value) || value.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined)
        {
            return null;
        }

        var text = value.GetString();
        return string.IsNullOrWhiteSpace(text) ? null : text;
    }

    private static string Text(JsonElement item, string name, string fallback)
    {
        if (!item.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.String)
        {
            return fallback;
        }

        var text = value.GetString();
        return string.IsNullOrWhiteSpace(text) ? fallback : text;
    }
}

public static class RelayPlans
{
    public const string Package = "org.stellartimelock.stellarsend";
    public const string DefaultRelay = "wss://stellar-send-relay.onrender.com/v1";
    public const string Quota = "quota";
    public const string QuotaMessage =
        "This month's relay data is used up. Wi-Fi transfers still work.";

    public static bool LocalTransfersAllowed() => true;

    public static bool OverCap(long used, long limit, bool enforced) =>
        enforced && limit > 0 && used >= limit;

    public static long BytesThisPeriod(string? storedPeriod, long storedBytes, string period) =>
        storedPeriod == period ? Math.Max(0, storedBytes) : 0;

    public static string CalendarPeriod(DateTimeOffset utc) => utc.UtcDateTime.ToString("yyyy-MM", CultureInfo.InvariantCulture);

    public static string EntitlementKey(string deviceId)
    {
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes("stellarsend-quota-v1|" + deviceId));
        return Convert.ToHexString(bytes).ToLowerInvariant();
    }
}

public readonly record struct WakeAttempt(TimeSpan Delay, TimeSpan Timeout);

public static class RelayWake
{
    public const string Status = "Waking relay…";
    public const string GaveUp = "The relay is still waking up. Same-Wi-Fi transfers stay free. Try again in a minute.";
    /// <summary>Relay up, phone not in the room. The phone is offline or asleep; the pairing is fine.</summary>
    public const string PhoneOffline = "Phone is offline. FlipSend will send it when the phone is back.";
    public const string PhoneMissing = PhoneOffline;
    public static bool IsPhoneOffline(string? message) => string.Equals(message?.Trim(), PhoneOffline, StringComparison.Ordinal);
    /// <summary>How often the PC checks whether a queued send is due.</summary>
    public static readonly TimeSpan OfflineCheckEvery = TimeSpan.FromSeconds(30);
    private static readonly int[] OfflineRetryMinutes = [1, 2, 5];

    /// <summary>Wait before trying a send that hit an offline phone again: 1, 2, then every 5 minutes.
    /// The phone coming back (an incoming connection) retries at once.</summary>
    public static TimeSpan OfflineRetryDelay(int misses) =>
        TimeSpan.FromMinutes(OfflineRetryMinutes[Math.Clamp(misses - 1, 0, OfflineRetryMinutes.Length - 1)]);
    public static readonly TimeSpan ListenWait = TimeSpan.FromMinutes(2);
    public static readonly TimeSpan Budget = TimeSpan.FromSeconds(90);

    /// <summary>Health succeeded, so the relay is not asleep. A missing peer is a different sentence.</summary>
    public static string AfterAttempts(bool relayAwake) => relayAwake ? PhoneMissing : GaveUp;
    public static readonly TimeSpan AttemptTimeout = TimeSpan.FromSeconds(20);
    private static readonly long[] DelaysMs = [0, 2_000, 5_000, 10_000, 15_000, 20_000];

    public static IReadOnlyList<WakeAttempt> Attempts(TimeSpan? budget = null, TimeSpan? attemptTimeout = null)
    {
        var budgetMs = (long)(budget ?? Budget).TotalMilliseconds;
        var attemptMs = (long)(attemptTimeout ?? AttemptTimeout).TotalMilliseconds;
        var result = new List<WakeAttempt>();
        long spent = 0;
        foreach (var delay in DelaysMs)
        {
            if (spent + delay >= budgetMs)
            {
                break;
            }

            var timeout = Math.Min(attemptMs, budgetMs - spent - delay);
            if (timeout < 1_000)
            {
                break;
            }

            result.Add(new WakeAttempt(TimeSpan.FromMilliseconds(delay), TimeSpan.FromMilliseconds(timeout)));
            spent += delay + timeout;
        }

        return result;
    }

    public static TimeSpan BudgetOf(IReadOnlyList<WakeAttempt> plan) =>
        new(plan.Aggregate(TimeSpan.Zero, (sum, attempt) => sum + attempt.Delay + attempt.Timeout).Ticks);
}

public enum RelayRoute
{
    Health,
    WebSocket,
    NotFound
}

public static class RelayCors
{
    public const string AllowOrigin = "*";
    public const string AllowMethods = "GET, HEAD, POST, OPTIONS";
    public const string AllowHeaders = "*";

    public static bool IsPreflight(string? method) =>
        string.Equals(method?.Trim(), "OPTIONS", StringComparison.OrdinalIgnoreCase);

    public static void Apply(System.Net.WebHeaderCollection headers)
    {
        headers["Access-Control-Allow-Origin"] = AllowOrigin;
        headers["Access-Control-Allow-Methods"] = AllowMethods;
        headers["Access-Control-Allow-Headers"] = AllowHeaders;
        headers["Access-Control-Max-Age"] = "86400";
    }
}

public static class RelayRoutes
{
    public static RelayRoute Decide(string? method, string? path, bool isWebSocket)
    {
        var verb = method?.Trim() ?? "";
        var route = path ?? "";
        if (verb.Equals("GET", StringComparison.OrdinalIgnoreCase) && route is "/healthz" or "/healthz/")
        {
            return RelayRoute.Health;
        }

        if (isWebSocket && route is "/v1" or "/v1/")
        {
            return RelayRoute.WebSocket;
        }

        return RelayRoute.NotFound;
    }
}

public sealed class RelayQuotaException : Exception
{
    public RelayQuotaException(string message) : base(message)
    {
    }
}

public sealed class RelayWakeException : Exception
{
    public RelayWakeException(string message, Exception? inner = null) : base(message, inner)
    {
    }
}

/// <summary>Why a send did not finish on Wi-Fi or on the relay. The words match the failure.</summary>
public static class DeliveryFault
{
    public const string SelfAnswered = "That address answered, but it was this computer, not the phone.";
    public const string LanUnfinished = "The phone answered on Wi-Fi, but the secure handshake did not finish.";
    public const string RelaySelf = "The relay paired this computer with itself. Leaving this computer out of the room and trying again.";

    public static bool IsSelf(string? seenFingerprint, string? localFingerprint) =>
        Pin.FixedEquals(seenFingerprint, localFingerprint);

    public static bool IsOwnAddress(string? ip, IEnumerable<string>? ownAddresses) =>
        !string.IsNullOrWhiteSpace(ip) &&
        ownAddresses is not null &&
        ownAddresses.Any(item => string.Equals(item, ip, StringComparison.OrdinalIgnoreCase));
}

/// <summary>The socket that answered presented this device's own certificate.</summary>
public sealed class SelfDialException : IOException
{
    public SelfDialException() : base(DeliveryFault.SelfAnswered)
    {
    }
}
