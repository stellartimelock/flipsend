using System.Text.Json;
using StellarSend.Core.Transfer;

namespace StellarSend.Core.Tests;

public class FairUseTests
{
    private static readonly DateTimeOffset October = new(2026, 10, 4, 12, 0, 0, TimeSpan.Zero);
    private static readonly DateTimeOffset November = new(2026, 11, 1, 0, 0, 0, TimeSpan.Zero);

    [Fact]
    public void DefaultCapIs50GbAndTheServerValueWins()
    {
        Assert.Equal(100_000_000L, FairUse.DefaultBytes);
        Assert.Equal(100_000_000L, FairUse.ResolveDefaultBytes(null, null));
        Assert.Equal(100_000_000L, FairUse.ResolveDefaultBytes(" ", 0));
        Assert.Equal(100_000_000L, FairUse.ResolveDefaultBytes("nope", null));
        Assert.Equal(12_000_000_000L, FairUse.ResolveDefaultBytes(null, 12_000_000_000L));
        Assert.Equal(80_000_000_000L, FairUse.ResolveDefaultBytes("80", 12_000_000_000L));
        Assert.Equal("2.5 GB of 50 GB relay this month", FairUse.Label(2_500_000_000, 50_000_000_000));
        Assert.Equal("Resets on the 1st.", FairUse.ResetsOnTheFirst);
    }

    [Fact]
    public void LocalLanAndUsbNeverCount()
    {
        Assert.False(FairUse.Counts(TransferPath.Direct));
        Assert.False(FairUse.Counts(TransferPath.Usb));
        Assert.False(FairUse.Counts(TransferPath.Bluetooth));
        Assert.False(FairUse.Counts("usb"));
        Assert.False(FairUse.Counts("LAN"));
        Assert.True(FairUse.Counts(TransferPath.Relay));
        Assert.True(RelayPlans.LocalTransfersAllowed());
    }

    [Fact]
    public async Task EliteAndPermanentFamilyHitTheSameCap()
    {
        var table = Load();
        var eliteKey = new string('a', 64);
        var familyKey = new string('b', 64);
        var policy = new FairUsePolicy(500, [eliteKey], [familyKey], eliteBytes: 1_000);
        var path = TempLedger();
        var ledger = new QuotaLedger(path);
        var when = new RelayAdmission(table, null, ledger, policy, () => October);

        var elite = await when.AdmitAsync(Credential(eliteKey), CancellationToken.None);
        var family = await when.AdmitAsync(Credential(familyKey), CancellationToken.None);
        Assert.Equal("elite", elite.TierId);
        Assert.False(elite.PermanentFamily);
        Assert.Equal("elite", family.TierId);
        Assert.True(family.PermanentFamily);
        Assert.False(elite.Blocked);
        Assert.False(family.Blocked);
        Assert.Equal(1_000, elite.FairLimit);
        Assert.Equal(1_000, family.FairLimit);
        Assert.False(elite.FreePool);
        Assert.Equal(0, elite.RatePerMinute);

        Assert.True(ledger.TryConsume(eliteKey, FairUse.PeriodId("2026-10"), 1_000, 1_000));
        Assert.True(ledger.TryConsume(familyKey, FairUse.PeriodId("2026-10"), 1_000, 1_000));
        var eliteBlocked = await when.AdmitAsync(Credential(eliteKey), CancellationToken.None);
        var familyBlocked = await when.AdmitAsync(Credential(familyKey), CancellationToken.None);
        Assert.True(eliteBlocked.FairUseBlocked);
        Assert.True(familyBlocked.FairUseBlocked);
        Assert.True(eliteBlocked.Blocked);
        Assert.True(familyBlocked.Blocked);
        Assert.Contains("Wi-Fi transfers still work", eliteBlocked.Message, StringComparison.Ordinal);
        Assert.Contains("Wi-Fi transfers still work", familyBlocked.Message, StringComparison.Ordinal);

        var room = when.Combine(eliteBlocked, familyBlocked);
        Assert.True(room.Blocked);

        var plain = new string('f', 64);
        Assert.True(ledger.TryConsume(plain, FairUse.PeriodId("2026-10"), 500, 500));
        var plainSeat = await when.AdmitAsync(Credential(plain), CancellationToken.None);
        Assert.Equal("free", plainSeat.TierId);
        Assert.Equal(500, plainSeat.FairLimit);
        Assert.True(plainSeat.FreePool);
        Assert.False(plainSeat.GrantElite);
        Assert.True(plainSeat.FairUseBlocked);

        var november = new RelayAdmission(table, null, ledger, policy, () => November);
        var fresh = await november.AdmitAsync(Credential(familyKey), CancellationToken.None);
        Assert.False(fresh.Blocked);
        Assert.Equal(0, fresh.FairUsed);
        Assert.True(fresh.PermanentFamily);
    }

    [Fact]
    public async Task MaxAndFamilyEliteStopAtTheFairUseCapNotThePlan()
    {
        var table = Load();
        var key = new string('c', 64);
        var path = TempLedger();
        var ledger = new QuotaLedger(path);
        Assert.True(ledger.TryConsume(key, FairUse.PeriodId("2026-10"), FairUse.EliteBytes, FairUse.EliteBytes));
        var policy = new FairUsePolicy(FairUse.DefaultBytes, null, [key], eliteBytes: FairUse.EliteBytes);
        var verifier = new FakeVerifier { Result = new PlaySubscription("max", key, "2026-11-04T00:00:00Z") };
        var admission = new RelayAdmission(table, verifier, ledger, policy, () => October);
        var seat = await admission.AdmitAsync(new RelayCredential("token", "relay_max", "monthly", RelayPlans.Package, key), CancellationToken.None);
        Assert.Equal("max", seat.TierId);
        Assert.True(seat.PermanentFamily);
        Assert.True(seat.Enforced);
        Assert.Equal(table.ById("max")!.RelayBytes, seat.Limit);
        Assert.True(seat.FairUseBlocked);
        Assert.False(seat.PlanBlocked);
        Assert.True(seat.Blocked);
        Assert.Equal(FairUse.EliteBytes, seat.FairLimit);
        var paused = ledger.TryForward(seat, 1);
        Assert.False(paused.Ok);
        Assert.True(paused.FairBlocked);
        Assert.False(paused.PlanBlocked);
        Assert.Equal(FairUse.EliteBytes, ledger.Used(key, FairUse.PeriodId("2026-10")));
    }

    [Fact]
    public async Task PlanCapStillPausesBeforeFairUse()
    {
        var table = Load();
        var key = new string('d', 64);
        var path = TempLedger();
        var period = "2026-11-04T00:00:00Z";
        var ledger = new QuotaLedger(path);
        var plus = table.ById("plus")!.RelayBytes;
        Assert.True(ledger.TryConsume(key, period, plus, plus));
        var verifier = new FakeVerifier { Result = new PlaySubscription("plus", key, period) };
        var admission = new RelayAdmission(table, verifier, ledger, FairUsePolicy.Default, () => October);
        var seat = await admission.AdmitAsync(new RelayCredential("token", "relay_plus", "monthly", RelayPlans.Package, key), CancellationToken.None);
        Assert.True(seat.PlanBlocked);
        Assert.False(seat.FairUseBlocked);
        Assert.Equal(RelayPlans.QuotaMessage, seat.Message);
        var paused = ledger.TryForward(seat, 1);
        Assert.True(paused.PlanBlocked);
        Assert.Equal(0, ledger.Used(key, FairUse.PeriodId("2026-10")));
    }

    [Fact]
    public void OwnerCanRaiseLowerAndResetACap()
    {
        var path = TempLedger();
        var ledger = new QuotaLedger(path);
        var policy = new FairUsePolicy(1_000, null, null);
        var account = new string('e', 64);
        var now = October;

        var missing = FairUseAdmin.Apply(ledger, policy, """{"action":"raise","capGb":2}""", now);
        Assert.Equal(400, missing.Status);

        var same = FairUseAdmin.Apply(ledger, policy, Account("raise", account, 0.000001m), now);
        Assert.Equal(400, same.Status);

        var raised = FairUseAdmin.Apply(ledger, policy, Account("raise", account, 0.000002m), now);
        Assert.Equal(200, raised.Status);
        Assert.DoesNotContain(account, raised.Json, StringComparison.Ordinal);
        Assert.Equal(2_000, ledger.EffectiveCap(account, policy.DefaultBytes));
        using (var doc = JsonDocument.Parse(raised.Json))
        {
            Assert.Equal("raise", doc.RootElement.GetProperty("action").GetString());
            Assert.Equal(2_000, doc.RootElement.GetProperty("capBytes").GetInt64());
            Assert.Equal("2026-10", doc.RootElement.GetProperty("month").GetString());
            Assert.Equal("1st", doc.RootElement.GetProperty("resets").GetString());
        }

        var reloaded = new QuotaLedger(path);
        Assert.Equal(2_000, reloaded.EffectiveCap(account, policy.DefaultBytes));

        Assert.True(reloaded.TryConsume(account, FairUse.PeriodId("2026-10"), 1_500, 2_000));
        var lowered = FairUseAdmin.Apply(reloaded, policy, Account("lower", account, 0.000001m), now);
        Assert.Equal(200, lowered.Status);
        Assert.Equal(1_000, reloaded.EffectiveCap(account, policy.DefaultBytes));
        Assert.Equal(1_500, reloaded.Used(account, FairUse.PeriodId("2026-10")));

        var reset = FairUseAdmin.Apply(reloaded, policy, Account("reset", account, null), now);
        Assert.Equal(200, reset.Status);
        Assert.Equal(1_000, new QuotaLedger(path).EffectiveCap(account, policy.DefaultBytes));
        Assert.Equal(0, new QuotaLedger(path).Used(account, FairUse.PeriodId("2026-10")));
        Assert.True(FairUseAdmin.IsPath("/v1/admin/cap"));
        Assert.False(FairUseAdmin.IsPath("/v1/admin/usage"));
    }

    [Fact]
    public void CheckedInTableCarriesTheFreeEliteAndPoolCaps()
    {
        var table = Load();
        Assert.Equal(0.1m, table.FairUseGb);
        Assert.Equal(FairUse.FreeBytes, table.FairUseBytes);
        Assert.Equal(FairUse.EliteBytes, table.EliteRelayBytes);
        Assert.Equal(FairUse.FreePoolBytes, table.FreePoolBytes);
        Assert.Equal(FairUse.FreeRateBytes, table.FreeRateBytes);
        Assert.Equal(FairUse.FreeBytes, table.Free.RelayBytes);
    }

    [Fact]
    public async Task OnlyTheOwnerEmailsAreUnlimitedAndOnlyWithTheServerProof()
    {
        var table = Load();
        const string secret = "owner-secret";
        var policy = new FairUsePolicy(
            FairUse.FreeBytes,
            null,
            null,
            unlimitedAccounts: UnlimitedAccounts.Defaults,
            unlimitedSecret: secret);
        var ledger = new QuotaLedger(TempLedger());
        var admission = new RelayAdmission(table, null, ledger, policy, () => October);
        var key = new string('a', 64);
        var proof = UnlimitedAccounts.Proof(secret, "stellartimelock@gmail.com");
        var granted = await admission.AdmitAsync(
            new RelayCredential(null, null, null, RelayPlans.Package, key, "StellarTimeLock@gmail.com", proof),
            CancellationToken.None);
        Assert.True(granted.Unlimited);
        Assert.False(granted.Blocked);
        Assert.False(granted.FreePool);
        Assert.True(ledger.TryForward(granted, 50_000_000_000).Ok);

        var spoof = await admission.AdmitAsync(
            new RelayCredential(null, null, null, RelayPlans.Package, key, "stellartimelock@gmail.com", null),
            CancellationToken.None);
        Assert.False(spoof.Unlimited);
        Assert.Equal(FairUse.FreeBytes, spoof.FairLimit);

        var strangerProof = UnlimitedAccounts.Proof(secret, "someone@example.com");
        var stranger = await admission.AdmitAsync(
            new RelayCredential(null, null, null, RelayPlans.Package, new string('b', 64), "someone@example.com", strangerProof),
            CancellationToken.None);
        Assert.False(stranger.Unlimited);

        var closed = new RelayAdmission(table, null, ledger, FairUsePolicy.Default, () => October);
        var denied = await closed.AdmitAsync(
            new RelayCredential(null, null, null, RelayPlans.Package, key, "stellartimelock@gmail.com", proof),
            CancellationToken.None);
        Assert.False(denied.Unlimited);
    }

    [Fact]
    public async Task FreePoolAndRateLimitDoNotApplyToElite()
    {
        var table = Load();
        var eliteKey = new string('a', 64);
        var policy = new FairUsePolicy(5_000, [eliteKey], null, eliteBytes: 5_000, poolBytes: 1_000, ratePerMinute: 1_000);
        var ledger = new QuotaLedger(TempLedger());
        var admission = new RelayAdmission(table, null, ledger, policy, () => October);
        var firstKey = new string('1', 64);
        var first = await admission.AdmitAsync(Credential(firstKey), CancellationToken.None);
        Assert.True(first.FreePool);
        Assert.Equal(1_000, first.RatePerMinute);
        Assert.True(ledger.TryForward(first, 1_000, October).Ok);
        var second = await admission.AdmitAsync(Credential(new string('2', 64)), CancellationToken.None);
        Assert.True(second.Blocked);
        Assert.Equal(FairUse.BusyMessage, second.Message);
        var elite = await admission.AdmitAsync(Credential(eliteKey), CancellationToken.None);
        Assert.False(elite.Blocked);
        Assert.False(elite.FreePool);
        Assert.True(ledger.TryForward(elite, 1_000, October).Ok);
        Assert.Equal(1_000, ledger.Used(FairUse.PoolAccount, FairUse.PoolPeriod("2026-10")));

        var clock = new DateTimeOffset(2026, 10, 4, 12, 30, 0, TimeSpan.Zero);
        var ratePolicy = new FairUsePolicy(50_000, null, null, poolBytes: 100_000, ratePerMinute: 1_000);
        var rateLedger = new QuotaLedger(TempLedger());
        var open = new RelayAdmission(table, null, rateLedger, ratePolicy, () => clock);
        var fresh = await open.AdmitAsync(Credential(new string('4', 64)), CancellationToken.None);
        Assert.True(rateLedger.TryForward(fresh, 1_000, clock).Ok);
        var slowed = rateLedger.TryForward(fresh, 1, clock);
        Assert.False(slowed.Ok);
        Assert.True(slowed.RateBlocked);
        Assert.False(slowed.PoolBlocked);
        var later = clock.AddMinutes(1);
        Assert.True(rateLedger.TryForward(fresh, 1, later).Ok);
    }

    [Fact]
    public async Task AClaimedPaidProductWithoutVerificationStaysOnTheFreeCap()
    {
        var key = new string('9', 64);
        var admission = new RelayAdmission(Load(), null, new QuotaLedger(TempLedger()), FairUsePolicy.Default, () => October);
        var claimed = await admission.AdmitAsync(
            new RelayCredential(null, "relay_max", "monthly", RelayPlans.Package, key),
            CancellationToken.None);
        Assert.False(claimed.Enforced);
        Assert.Equal(FairUse.FreeBytes, claimed.FairLimit);
        Assert.True(claimed.FreePool);
        Assert.False(claimed.Unlimited);
    }

    private static string Account(string action, string account, decimal? capGb)
    {
        var cap = capGb is null ? "" : ",\"capGb\":" + capGb.Value.ToString(System.Globalization.CultureInfo.InvariantCulture);
        return "{\"action\":\"" + action + "\",\"account\":\"" + account + "\"" + cap + "}";
    }

    private static RelayCredential Credential(string key) => new(null, null, null, RelayPlans.Package, key);

    private static string TempLedger() => Path.Combine(Path.GetTempPath(), "stellarsend-fair-" + Guid.NewGuid().ToString("n") + ".json");

    private static TierTable Load() => TierTable.Parse(File.ReadAllText(Path.Combine(Repo.Root(), "relay", "tiers.json")));

    private sealed class FakeVerifier : IPlaySubscriptionVerifier
    {
        public PlaySubscription? Result { get; init; }

        public Task<PlaySubscription?> VerifyAsync(RelayCredential credential, CancellationToken ct) => Task.FromResult(Result);
    }
}
