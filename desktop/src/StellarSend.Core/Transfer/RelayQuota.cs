using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace StellarSend.Core.Transfer;

public sealed record RelayCredential(
    string? PurchaseToken,
    string? ProductId,
    string? BasePlanId,
    string? PackageName,
    string? EntitlementKey,
    string? Account = null,
    string? AccountProof = null)
{
    public static RelayCredential? Sanitize(RelayCredential? credential)
    {
        if (credential is null)
        {
            return null;
        }

        var token = string.IsNullOrWhiteSpace(credential.PurchaseToken) || credential.PurchaseToken.Length > 4096
            ? null
            : credential.PurchaseToken.Trim();
        var key = credential.EntitlementKey is { Length: 64 } hex && hex.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f')
            ? hex
            : null;
        var account = UnlimitedAccounts.TryNormalize(credential.Account, out var email) ? email : null;
        var proof = credential.AccountProof is { Length: 64 } mac && mac.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f')
            ? credential.AccountProof.ToLowerInvariant()
            : null;
        if (token is null && key is null && account is null && string.IsNullOrWhiteSpace(credential.ProductId))
        {
            return null;
        }

        return credential with
        {
            PurchaseToken = token,
            EntitlementKey = key,
            Account = account,
            AccountProof = account is null ? null : proof,
            PackageName = string.IsNullOrWhiteSpace(credential.PackageName) ? RelayPlans.Package : credential.PackageName
        };
    }
}

public sealed record RelaySeat(
    bool Enforced,
    string? Key,
    string? Period,
    string TierId,
    long Used,
    long Limit,
    bool Blocked,
    string? Message,
    bool GrantElite = false,
    bool PermanentFamily = false,
    bool FairUseEnforced = false,
    long FairUsed = 0,
    long FairLimit = 0,
    string? FairPeriod = null,
    bool FairUseBlocked = false,
    bool PlanBlocked = false,
    bool Unlimited = false,
    bool FreePool = false,
    long PoolLimit = 0,
    string? PoolPeriod = null,
    long RatePerMinute = 0)
{
    public static RelaySeat Unenforced { get; } = new(false, null, null, "free", 0, 0, false, null);
}

public readonly record struct QuotaTake(
    bool Ok,
    bool FairBlocked,
    bool PlanBlocked,
    bool PoolBlocked = false,
    bool RateBlocked = false)
{
    public static QuotaTake Allowed() => new(true, false, false);
}

public sealed class QuotaLedger
{
    private readonly string _path;
    private readonly object _gate = new();
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);
    private readonly Dictionary<string, long> _caps = new(StringComparer.Ordinal);
    private readonly Dictionary<string, long> _rates = new(StringComparer.Ordinal);
    private long _dirtyBytes;
    private bool _loaded;

    public QuotaLedger(string path)
    {
        _path = path;
    }

    public long Used(string key, string period)
    {
        lock (_gate)
        {
            Load();
            return Current(key, period).Bytes;
        }
    }

    public long EffectiveCap(string key, long fallback)
    {
        lock (_gate)
        {
            Load();
            return _caps.TryGetValue(key, out var cap) && cap > 0 ? cap : fallback;
        }
    }

    public void SetCap(string key, long bytes)
    {
        if (bytes <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(bytes));
        }

        lock (_gate)
        {
            Load();
            _caps[key] = bytes;
            Flush(force: true);
        }
    }

    public void ClearCap(string key)
    {
        lock (_gate)
        {
            Load();
            _caps.Remove(key);
            Flush(force: true);
        }
    }

    public void ResetPeriod(string key, string period)
    {
        lock (_gate)
        {
            Load();
            Current(key, period).Bytes = 0;
            Flush(force: true);
        }
    }

    /// <summary>
    /// Counts one relay frame toward the plan cap and the fair-use cap.
    /// Returns false once either cap is already reached, without adding the bytes.
    /// A single write may land a few kilobytes past a cap; the next write stops.
    /// </summary>
    public QuotaTake TryForward(RelaySeat seat, long bytes, DateTimeOffset? utc = null)
    {
        if (bytes < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(bytes));
        }

        if (bytes == 0 || seat.Unlimited)
        {
            return QuotaTake.Allowed();
        }

        var now = utc ?? DateTimeOffset.UtcNow;
        var planOn = seat.Enforced && !string.IsNullOrEmpty(seat.Key) && !string.IsNullOrEmpty(seat.Period) && seat.Limit > 0;
        var fairOn = seat.FairUseEnforced && !string.IsNullOrEmpty(seat.Key) && !string.IsNullOrEmpty(seat.FairPeriod) && seat.FairLimit > 0;
        var poolOn = seat.FreePool && seat.PoolLimit > 0 && !string.IsNullOrEmpty(seat.PoolPeriod);
        var rateOn = seat.RatePerMinute > 0 && !string.IsNullOrEmpty(seat.Key);
        lock (_gate)
        {
            Load();
            if (planOn && Current(seat.Key!, seat.Period!).Bytes >= seat.Limit)
            {
                return new QuotaTake(false, false, true);
            }

            var fairPeriod = fairOn ? FairUse.PeriodId(seat.FairPeriod!) : null;
            if (fairOn && Current(seat.Key!, fairPeriod!).Bytes >= seat.FairLimit)
            {
                return new QuotaTake(false, true, false);
            }

            if (poolOn && Current(FairUse.PoolAccount, seat.PoolPeriod!).Bytes >= seat.PoolLimit)
            {
                return new QuotaTake(false, false, false, true, false);
            }

            var rateId = rateOn ? seat.Key + "|" + FairUse.RateSlot(now) : null;
            if (rateOn && RateUsed(rateId!) >= seat.RatePerMinute)
            {
                return new QuotaTake(false, false, false, false, true);
            }

            if (fairOn)
            {
                var fair = Current(seat.Key!, fairPeriod!);
                fair.Bytes += bytes;
                _dirtyBytes += bytes;
            }

            if (planOn)
            {
                var plan = Current(seat.Key!, seat.Period!);
                plan.Bytes += bytes;
                _dirtyBytes += bytes;
            }

            if (poolOn)
            {
                var pool = Current(FairUse.PoolAccount, seat.PoolPeriod!);
                pool.Bytes += bytes;
                _dirtyBytes += bytes;
            }

            if (rateOn)
            {
                _rates[rateId!] = RateUsed(rateId!) + bytes;
            }

            var fairFull = fairOn && Current(seat.Key!, fairPeriod!).Bytes >= seat.FairLimit;
            var planFull = planOn && Current(seat.Key!, seat.Period!).Bytes >= seat.Limit;
            var poolFull = poolOn && Current(FairUse.PoolAccount, seat.PoolPeriod!).Bytes >= seat.PoolLimit;
            Flush(force: fairFull || planFull || poolFull);
            return QuotaTake.Allowed();
        }
    }

    private long RateUsed(string id) => _rates.TryGetValue(id, out var used) ? used : 0;

    /// <summary>
    /// Counts egress toward the cap. Returns false once the cap is already reached.
    /// A single write may land a few kilobytes past the cap; the next write stops.
    /// </summary>
    public bool TryConsume(string key, string period, long bytes, long limit)
    {
        if (bytes < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(bytes));
        }

        lock (_gate)
        {
            Load();
            var entry = Current(key, period);
            if (limit > 0 && entry.Bytes >= limit)
            {
                Flush(force: true);
                return false;
            }

            entry.Bytes += bytes;
            _dirtyBytes += bytes;
            Flush(force: limit > 0 && entry.Bytes >= limit);
            return true;
        }
    }

    private Entry Current(string key, string period)
    {
        var id = key + "|" + period;
        if (!_entries.TryGetValue(id, out var entry))
        {
            entry = new Entry(key, period, 0);
            _entries[id] = entry;
        }

        return entry;
    }

    private void Load()
    {
        if (_loaded)
        {
            return;
        }

        _loaded = true;
        if (!File.Exists(_path))
        {
            return;
        }

        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(_path));
            if (doc.RootElement.TryGetProperty("entries", out var entries) && entries.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in entries.EnumerateArray())
                {
                    var key = item.GetProperty("key").GetString();
                    var period = item.GetProperty("period").GetString();
                    if (string.IsNullOrEmpty(key) || string.IsNullOrEmpty(period))
                    {
                        continue;
                    }

                    var bytes = item.GetProperty("bytes").GetInt64();
                    _entries[key + "|" + period] = new Entry(key, period, Math.Max(0, bytes));
                }
            }

            if (doc.RootElement.TryGetProperty("caps", out var caps) && caps.ValueKind == JsonValueKind.Object)
            {
                foreach (var property in caps.EnumerateObject())
                {
                    if (property.Value.ValueKind == JsonValueKind.Number && property.Value.TryGetInt64(out var cap) && cap > 0)
                    {
                        _caps[property.Name] = cap;
                    }
                }
            }
        }
        catch (Exception ex) when (ex is JsonException or IOException)
        {
        }
    }

    private void Flush(bool force)
    {
        if (!force && _dirtyBytes < 1_000_000)
        {
            return;
        }

        _dirtyBytes = 0;
        var directory = Path.GetDirectoryName(_path);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteStartArray("entries");
            foreach (var entry in _entries.Values)
            {
                writer.WriteStartObject();
                writer.WriteString("key", entry.Key);
                writer.WriteString("period", entry.Period);
                writer.WriteNumber("bytes", entry.Bytes);
                writer.WriteEndObject();
            }

            writer.WriteEndArray();
            writer.WritePropertyName("caps");
            writer.WriteStartObject();
            foreach (var pair in _caps)
            {
                writer.WriteNumber(pair.Key, pair.Value);
            }

            writer.WriteEndObject();
            writer.WriteEndObject();
        }

        var temp = _path + ".tmp";
        File.WriteAllBytes(temp, stream.ToArray());
        File.Move(temp, _path, overwrite: true);
    }

    private sealed class Entry(string key, string period, long bytes)
    {
        public string Key { get; } = key;
        public string Period { get; } = period;
        public long Bytes { get; set; } = bytes;
    }
}

public sealed class RelayAdmission
{
    private readonly TierTable _tiers;
    private readonly IPlaySubscriptionVerifier? _verifier;
    private readonly QuotaLedger _ledger;
    private readonly FairUsePolicy _fairUse;
    private readonly Func<DateTimeOffset> _clock;

    public RelayAdmission(TierTable tiers, IPlaySubscriptionVerifier? verifier, QuotaLedger ledger, FairUsePolicy? fairUse = null, Func<DateTimeOffset>? clock = null)
    {
        _tiers = tiers;
        _verifier = verifier;
        _ledger = ledger;
        _fairUse = fairUse ?? FairUsePolicy.Default;
        _clock = clock ?? (() => DateTimeOffset.UtcNow);
    }

    public bool Enforced => _verifier is not null;

    public async Task<RelaySeat> AdmitAsync(RelayCredential? credential, CancellationToken ct)
    {
        var clean = RelayCredential.Sanitize(credential);
        var seat = await AdmitPlanAsync(clean, ct).ConfigureAwait(false);
        return OverlayFairUse(ApplyUnlimited(ApplyGrant(seat, clean), clean));
    }

    public RelaySeat Combine(RelaySeat listen, RelaySeat dial)
    {
        if (listen.Unlimited || dial.Unlimited)
        {
            var winner = listen.Unlimited ? listen : dial;
            return OverlayFairUse(winner with
            {
                Unlimited = true,
                Key = listen.Key ?? dial.Key ?? winner.Key
            });
        }

        return OverlayFairUse(CombinePlans(listen, dial));
    }

    private async Task<RelaySeat> AdmitPlanAsync(RelayCredential? clean, CancellationToken ct)
    {
        if (_verifier is null)
        {
            var claimed = _tiers.ByProduct(clean?.ProductId) ?? _tiers.Free;
            return new RelaySeat(false, clean?.EntitlementKey, null, claimed.Id, 0, claimed.RelayBytes, false, null);
        }

        if (clean?.PurchaseToken is not null)
        {
            var subscription = await _verifier.VerifyAsync(clean, ct).ConfigureAwait(false);
            if (subscription is not null)
            {
                var tier = _tiers.ById(subscription.TierId) ?? _tiers.ByProduct(clean.ProductId) ?? _tiers.Free;
                var used = _ledger.Used(subscription.QuotaKey, subscription.PeriodId);
                return new RelaySeat(true, subscription.QuotaKey, subscription.PeriodId, tier.Id, used, tier.RelayBytes, false, null);
            }
        }

        var period = RelayPlans.CalendarPeriod(_clock());
        var key = clean?.EntitlementKey;
        var freeUsed = key is null ? 0 : _ledger.Used(key, period);
        return new RelaySeat(true, key, period, _tiers.Free.Id, freeUsed, _tiers.Free.RelayBytes, false, null);
    }

    private RelaySeat ApplyGrant(RelaySeat seat, RelayCredential? clean)
    {
        var key = seat.Key ?? clean?.EntitlementKey;
        if (!_fairUse.IsElite(key))
        {
            return seat;
        }

        var permanent = _fairUse.IsPermanentFamily(key);
        if (IsPaid(seat))
        {
            return seat with { GrantElite = true, PermanentFamily = permanent, Key = key };
        }

        return seat with
        {
            GrantElite = true,
            PermanentFamily = permanent,
            TierId = "elite",
            Enforced = false,
            Blocked = false,
            Message = null,
            Limit = 0,
            Used = 0,
            Key = key
        };
    }

    private RelaySeat ApplyUnlimited(RelaySeat seat, RelayCredential? clean)
    {
        if (!_fairUse.GrantsUnlimited(clean?.Account, clean?.AccountProof))
        {
            return seat;
        }

        return seat with
        {
            Unlimited = true,
            GrantElite = false,
            PermanentFamily = false,
            TierId = "unlimited",
            Enforced = false,
            Blocked = false,
            PlanBlocked = false,
            Message = null,
            Limit = 0,
            Used = 0,
            Key = seat.Key ?? clean?.EntitlementKey
        };
    }

    private RelaySeat CombinePlans(RelaySeat listen, RelaySeat dial)
    {
        if ((listen.GrantElite || dial.GrantElite) && !IsPaid(listen) && !IsPaid(dial))
        {
            var elite = listen.GrantElite ? listen : dial;
            return elite with
            {
                Enforced = false,
                Blocked = false,
                TierId = "elite",
                Key = listen.Key ?? dial.Key,
                Limit = 0,
                Used = 0,
                Period = null,
                Message = null,
                GrantElite = true,
                PermanentFamily = listen.PermanentFamily || dial.PermanentFamily,
                PlanBlocked = false
            };
        }

        var enforced = listen.Enforced || dial.Enforced;
        var winner = listen.Limit >= dial.Limit ? listen : dial;
        var key = VerifiedKey(listen, dial) ?? listen.Key ?? dial.Key;
        var period = winner.Period ?? (key is null ? null : RelayPlans.CalendarPeriod(_clock()));
        if (!enforced)
        {
            return winner with { Enforced = false, Blocked = false, PlanBlocked = false, Key = key, Period = period, Message = null };
        }

        if (string.IsNullOrEmpty(key) || string.IsNullOrEmpty(period))
        {
            return winner with
            {
                Enforced = true,
                Blocked = true,
                PlanBlocked = true,
                Key = key,
                Message = RelayPlans.QuotaMessage
            };
        }

        var used = _ledger.Used(key, period);
        var blocked = RelayPlans.OverCap(used, winner.Limit, enforced: true);
        return winner with
        {
            Enforced = true,
            Key = key,
            Period = period,
            Used = used,
            Blocked = blocked,
            PlanBlocked = blocked,
            Message = blocked ? RelayPlans.QuotaMessage : null,
            GrantElite = listen.GrantElite || dial.GrantElite,
            PermanentFamily = listen.PermanentFamily || dial.PermanentFamily
        };
    }

    private RelaySeat OverlayFairUse(RelaySeat seat)
    {
        if (seat.Unlimited)
        {
            return seat with
            {
                FairUseEnforced = false,
                FairPeriod = FairUse.Month(_clock()),
                FairLimit = 0,
                FairUsed = 0,
                FairUseBlocked = false,
                FreePool = false,
                PoolLimit = 0,
                PoolPeriod = null,
                RatePerMinute = 0,
                Blocked = false,
                PlanBlocked = false,
                Message = null
            };
        }

        if (!seat.PlanBlocked && seat.Enforced && !string.IsNullOrEmpty(seat.Key) && !string.IsNullOrEmpty(seat.Period) && seat.Limit > 0)
        {
            var planUsed = _ledger.Used(seat.Key, seat.Period);
            var planBlocked = planUsed >= seat.Limit;
            seat = seat with
            {
                Used = planUsed,
                PlanBlocked = planBlocked,
                Blocked = planBlocked,
                Message = planBlocked ? RelayPlans.QuotaMessage : seat.Message
            };
        }

        var month = FairUse.Month(_clock());
        var paid = IsPaid(seat) && !seat.GrantElite;
        var freeTier = !paid && !seat.GrantElite;
        if (paid)
        {
            return seat with
            {
                FairUseEnforced = false,
                FairPeriod = month,
                FairLimit = 0,
                FairUsed = 0,
                FairUseBlocked = false,
                FreePool = false,
                PoolLimit = 0,
                RatePerMinute = 0
            };
        }

        var personal = seat.GrantElite ? _fairUse.EliteBytes : _fairUse.DefaultBytes;
        var key = seat.Key;
        if (string.IsNullOrEmpty(key) && freeTier)
        {
            key = "anonymous";
            seat = seat with { Key = key };
        }

        if (string.IsNullOrEmpty(key))
        {
            return seat with
            {
                FairUseEnforced = false,
                FairPeriod = month,
                FairLimit = personal,
                FairUsed = 0,
                FairUseBlocked = false,
                FreePool = false,
                PoolLimit = 0,
                RatePerMinute = 0
            };
        }

        var cap = _ledger.EffectiveCap(key, personal);
        var used = _ledger.Used(key, FairUse.PeriodId(month));
        var fairBlocked = cap > 0 && used >= cap;
        var poolPeriod = FairUse.PoolPeriod(month);
        var poolUsed = freeTier ? _ledger.Used(FairUse.PoolAccount, poolPeriod) : 0;
        var poolBlocked = freeTier && _fairUse.PoolBytes > 0 && poolUsed >= _fairUse.PoolBytes;
        var blocked = seat.PlanBlocked || fairBlocked || poolBlocked;
        string? message = null;
        if (seat.PlanBlocked || fairBlocked)
        {
            message = string.IsNullOrEmpty(seat.Message) || fairBlocked ? FairUse.PauseMessage(cap) : seat.Message;
            if (seat.PlanBlocked && !fairBlocked)
            {
                message = RelayPlans.QuotaMessage;
            }
        }
        else if (poolBlocked)
        {
            message = FairUse.BusyMessage;
        }

        return seat with
        {
            FairUseEnforced = true,
            FairPeriod = month,
            FairLimit = cap,
            FairUsed = used,
            FairUseBlocked = fairBlocked,
            Blocked = blocked,
            Message = message,
            FreePool = freeTier,
            PoolLimit = freeTier ? _fairUse.PoolBytes : 0,
            PoolPeriod = freeTier ? poolPeriod : null,
            RatePerMinute = freeTier ? _fairUse.RatePerMinute : 0
        };
    }

    private static bool IsPaid(RelaySeat seat) =>
        seat.Enforced && seat.TierId is not ("free" or "elite") && seat.Limit > 0;

    private static string? VerifiedKey(RelaySeat listen, RelaySeat dial)
    {
        if (listen.Enforced && listen.TierId != "free" && !string.IsNullOrEmpty(listen.Key))
        {
            return listen.Key;
        }

        if (dial.Enforced && dial.TierId != "free" && !string.IsNullOrEmpty(dial.Key))
        {
            return dial.Key;
        }

        return null;
    }
}

public static class RelayMessages
{
    public static string Ready(RelaySeat seat, string? seen = null)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteNumber("v", 1);
            writer.WriteBoolean("ok", true);
            writer.WriteString("tier", seat.TierId);
            if (DirectLink.IsEndpoint(seen))
            {
                writer.WriteString("seen", seen);
            }

            WriteMeter(writer, seat, atCap: false);
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    public static string Quota(RelaySeat seat)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteNumber("v", 1);
            writer.WriteBoolean("ok", false);
            writer.WriteString("error", RelayPlans.Quota);
            writer.WriteString("message", seat.Message ?? RelayPlans.QuotaMessage);
            writer.WriteString("tier", seat.TierId);
            WriteMeter(writer, seat, atCap: true);
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    public static string Usage(RelaySeat seat)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteNumber("v", 1);
            writer.WriteBoolean("ok", true);
            writer.WriteString("tier", seat.TierId);
            WriteMeter(writer, seat, atCap: false);
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    private static void WriteMeter(Utf8JsonWriter writer, RelaySeat seat, bool atCap)
    {
        if (seat.Unlimited)
        {
            writer.WriteNumber("used", 0);
            writer.WriteNumber("limit", 0);
            writer.WriteBoolean("enforced", false);
            writer.WriteBoolean("unlimited", true);
            return;
        }

        if (seat.FairUseEnforced)
        {
            var used = atCap ? Math.Max(seat.FairUsed, seat.FairLimit) : seat.FairUsed;
            writer.WriteNumber("used", used);
            writer.WriteNumber("limit", seat.FairLimit);
            writer.WriteBoolean("enforced", true);
            if (!string.IsNullOrEmpty(seat.FairPeriod))
            {
                writer.WriteString("resets", seat.FairPeriod);
            }

            return;
        }

        var planUsed = atCap ? Math.Max(seat.Used, seat.Limit) : seat.Used;
        writer.WriteNumber("used", planUsed);
        writer.WriteNumber("limit", seat.Limit);
        writer.WriteBoolean("enforced", atCap || seat.Enforced);
        if (!string.IsNullOrEmpty(seat.Period))
        {
            writer.WriteString("resets", seat.Period);
        }
    }
}

public static class TokenHash
{
    public static string Sha256(string text)
    {
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(text));
        return Convert.ToHexString(bytes).ToLowerInvariant();
    }
}
