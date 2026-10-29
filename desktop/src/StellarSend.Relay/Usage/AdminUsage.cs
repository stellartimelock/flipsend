using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using StellarSend.Core.Transfer;

namespace StellarSend.Relay;

public sealed class UsageDocument
{
    public Dictionary<string, long> Counts { get; } = new(StringComparer.Ordinal);
    public Dictionary<string, long> Days { get; } = new(StringComparer.Ordinal);
    public Dictionary<string, string> Tiers { get; } = new(StringComparer.Ordinal);

    public UsageDocument Clone()
    {
        var copy = new UsageDocument();
        foreach (var pair in Counts)
        {
            copy.Counts[pair.Key] = pair.Value;
        }

        foreach (var pair in Days)
        {
            copy.Days[pair.Key] = pair.Value;
        }

        foreach (var pair in Tiers)
        {
            copy.Tiers[pair.Key] = pair.Value;
        }

        return copy;
    }
}

public readonly record struct PlanCap(string Id, decimal RelayGb, bool Paid);

public interface IUsageAdminStore
{
    Task<UsageDocument> LoadAdminAsync(CancellationToken cancellationToken);
    Task SaveAdminAsync(UsageDocument document, CancellationToken cancellationToken);
}

public static class RelayTiers
{
    public const decimal EgressUsdPerGb = 0.15m;
    public const long BytesPerGb = 1_000_000_000;

    public static IReadOnlyList<PlanCap> Load()
    {
        using var stream = typeof(UsageCounter).Assembly.GetManifestResourceStream("tiers.json");
        if (stream is null)
        {
            return Defaults();
        }

        using var doc = JsonDocument.Parse(stream);
        if (!doc.RootElement.TryGetProperty("plans", out var plans) || plans.ValueKind != JsonValueKind.Array)
        {
            return Defaults();
        }

        var list = new List<PlanCap>();
        foreach (var plan in plans.EnumerateArray())
        {
            var id = plan.GetProperty("id").GetString()?.Trim().ToLowerInvariant();
            if (id is not ("free" or "pro" or "plus" or "max"))
            {
                continue;
            }

            var gb = plan.GetProperty("relayGb").GetDecimal();
            var paid = plan.TryGetProperty("includedWithApp", out var included) && included.ValueKind == JsonValueKind.False;
            list.Add(new PlanCap(id, gb, paid));
        }

        return list.Count == 0 ? Defaults() : list;
    }

    public static IReadOnlyList<PlanCap> Defaults() =>
    [
        new("free", 0.1m, false),
        new("pro", 10m, true),
        new("plus", 30m, true),
        new("max", 60m, true)
    ];

    public static string Normalize(string? plan)
    {
        var text = plan?.Trim().ToLowerInvariant();
        return text is "pro" or "plus" or "max" or "free" or "elite" ? text : "free";
    }
}

public static class AdminAuth
{
    public static bool IsPath(string? path) => path is "/v1/admin/usage" or "/v1/admin/usage/";

    /// <summary>404 hides the route when the token is unset. 401 is a wrong token. 200 is allowed.</summary>
    public static int Authorize(string? authorizationHeader, string? configuredToken)
    {
        if (string.IsNullOrWhiteSpace(configuredToken))
        {
            return 404;
        }

        var expected = configuredToken.Trim();
        if (string.IsNullOrEmpty(expected))
        {
            return 404;
        }

        if (authorizationHeader is null || !authorizationHeader.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase))
        {
            return 401;
        }

        var presented = authorizationHeader["Bearer ".Length..].Trim();
        var left = Encoding.UTF8.GetBytes(presented);
        var right = Encoding.UTF8.GetBytes(expected);
        if (left.Length != right.Length || !CryptographicOperations.FixedTimeEquals(left, right))
        {
            return 401;
        }

        return 200;
    }
}

public static class AdminReport
{
    public static string ToJson(UsageDocument document, DateTime utc, TimeSpan uptime, string storeName, IReadOnlyList<PlanCap> plans, long fairUseBytes = 0)
    {
        var month = utc.ToUniversalTime().ToString("yyyy-MM", CultureInfo.InvariantCulture);
        var day = utc.ToUniversalTime().ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        var caps = plans.ToDictionary(plan => plan.Id, plan => plan, StringComparer.Ordinal);
        long total = 0;
        long today = 0;
        if (fairUseBytes <= 0)
        {
            fairUseBytes = FairUse.DefaultBytes;
        }

        var subscribers = new Dictionary<string, int>(StringComparer.Ordinal)
        {
            ["pro"] = 0,
            ["plus"] = 0,
            ["max"] = 0,
            ["elite"] = 0
        };
        var free = 0;
        var near = 0;
        foreach (var pair in document.Counts)
        {
            if (!TrySplit(pair.Key, out var id, out var suffix) || suffix != month || pair.Value <= 0)
            {
                continue;
            }

            total += pair.Value;
            var tier = document.Tiers.TryGetValue(id, out var recorded) ? RelayTiers.Normalize(recorded) : "free";
            if (tier == "free" || !subscribers.ContainsKey(tier))
            {
                free++;
                tier = "free";
            }
            else
            {
                subscribers[tier]++;
            }

            var allowance = tier == "elite"
                ? (decimal)fairUseBytes
                : (caps.TryGetValue(tier, out var cap) ? cap.RelayGb : 0.1m) * RelayTiers.BytesPerGb;
            if (allowance > 0 && pair.Value / allowance > 0.8m)
            {
                near++;
            }
        }

        foreach (var pair in document.Days)
        {
            if (!TrySplit(pair.Key, out _, out var suffix) || suffix != day || pair.Value <= 0)
            {
                continue;
            }

            today += pair.Value;
        }

        var egress = decimal.Round(total / (decimal)RelayTiers.BytesPerGb * RelayTiers.EgressUsdPerGb, 4, MidpointRounding.AwayFromZero);
        var uptimeSeconds = uptime < TimeSpan.Zero ? 0 : (long)uptime.TotalSeconds;
        var store = storeName == "upstash" ? "upstash" : "file";
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteString("month", month);
            writer.WriteNumber("totalBytes", total);
            writer.WriteNumber("todayBytes", today);
            writer.WritePropertyName("subscribers");
            writer.WriteStartObject();
            writer.WriteNumber("pro", subscribers["pro"]);
            writer.WriteNumber("plus", subscribers["plus"]);
            writer.WriteNumber("max", subscribers["max"]);
            writer.WriteNumber("elite", subscribers["elite"]);
            writer.WriteEndObject();
            writer.WriteNumber("fairUseGb", decimal.Round(fairUseBytes / (decimal)RelayTiers.BytesPerGb, 4, MidpointRounding.AwayFromZero));
            writer.WriteNumber("freeUsers", free);
            writer.WriteNumber("nearCap", near);
            writer.WriteNumber("egressUsd", egress);
            writer.WriteNumber("uptimeSeconds", uptimeSeconds);
            writer.WriteString("store", store);
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    private static bool TrySplit(string key, out string id, out string suffix)
    {
        id = "";
        suffix = "";
        var split = key.LastIndexOf('|');
        if (split <= 0 || split == key.Length - 1)
        {
            return false;
        }

        id = key[..split];
        suffix = key[(split + 1)..];
        return true;
    }
}
