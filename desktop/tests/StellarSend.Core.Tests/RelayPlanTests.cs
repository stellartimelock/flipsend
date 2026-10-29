using System.Security.Cryptography;
using System.Text.Json;
using StellarSend.Core.Transfer;

namespace StellarSend.Core.Tests;

public class RelayPlanTests
{
    [Fact]
    public void PaidTiersStayInsideTheWorstCaseMargin()
    {
        var table = Load();
        Assert.Equal(1_000_000_000L, table.BytesPerGb);
        Assert.Equal(0.15m, table.EgressUsdPerGb);
        Assert.Equal(0.15m, table.PlayFeeRate);
        Assert.Equal(2.9999m, table.AppPriceUsd);
        var appNet = table.AppPriceUsd * (1 - table.PlayFeeRate);
        Assert.True(table.Free.EgressCost(table.EgressUsdPerGb) <= appNet);
        Assert.Equal(3, table.Tiers.Count(tier => tier.Paid));
        foreach (var tier in table.Tiers.Where(tier => tier.Paid))
        {
            Assert.True(tier.StaysProfitable(table.EgressUsdPerGb, table.PlayFeeRate));
        }

        Assert.Equal("plus", table.Recommended?.Id);
        Assert.Equal(new[] { "relay_pro", "relay_plus", "relay_max" }, table.Tiers.Where(tier => tier.Paid).Select(tier => tier.ProductId).ToArray());
    }

    [Fact]
    public void AMissingPhoneIsOfflineNotAPairingProblem()
    {
        Assert.Equal(RelayWake.PhoneOffline, RelayWake.AfterAttempts(true));
        Assert.True(RelayWake.IsPhoneOffline(RelayWake.PhoneOffline));
        Assert.DoesNotContain("pair", RelayWake.PhoneOffline, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(TimeSpan.FromMinutes(1), RelayWake.OfflineRetryDelay(1));
        Assert.Equal(TimeSpan.FromMinutes(2), RelayWake.OfflineRetryDelay(2));
        Assert.Equal(TimeSpan.FromMinutes(5), RelayWake.OfflineRetryDelay(3));
        Assert.Equal(TimeSpan.FromMinutes(5), RelayWake.OfflineRetryDelay(40));
    }

    [Fact]
    public void AnAwakeRelayIsNotStillWakingUp()
    {
        Assert.Equal(RelayWake.PhoneMissing, RelayWake.AfterAttempts(true));
        Assert.Equal(RelayWake.GaveUp, RelayWake.AfterAttempts(false));
        var pin = new string('a', 64);
        var other = new string('b', 64);
        Assert.True(DeliveryFault.IsSelf(pin, pin));
        Assert.False(DeliveryFault.IsSelf(pin, other));
        Assert.False(DeliveryFault.IsSelf(pin, null));
        Assert.True(DeliveryFault.IsOwnAddress("fe80::1", new[] { "FE80::1" }));
        Assert.False(DeliveryFault.IsOwnAddress("192.168.1.77", new[] { "192.168.1.20" }));
        Assert.False(DeliveryFault.IsOwnAddress(null, new[] { "192.168.1.20" }));
    }

    [Fact]
    public void WakeBudgetRetriesThenStops()
    {
        var plan = RelayWake.Attempts();
        Assert.True(plan.Count > 1);
        Assert.Equal(TimeSpan.Zero, plan[0].Delay);
        Assert.True(RelayWake.BudgetOf(plan) <= RelayWake.Budget);
    }

    [Fact]
    public void RoutesMatchHealthAndTheLiveSocket()
    {
        Assert.Equal(RelayRoute.Health, RelayRoutes.Decide("GET", "/healthz", false));
        Assert.Equal(RelayRoute.Health, RelayRoutes.Decide("GET", "/healthz/", false));
        Assert.Equal(RelayRoute.WebSocket, RelayRoutes.Decide("GET", "/v1", true));
        Assert.Equal(RelayRoute.WebSocket, RelayRoutes.Decide("GET", "/v1/", true));
        Assert.Equal(RelayRoute.NotFound, RelayRoutes.Decide("GET", "/v1", false));
        Assert.Equal(RelayRoute.NotFound, RelayRoutes.Decide("GET", "/ws", true));
    }

    [Fact]
    public void BrowserPreflightIsCors()
    {
        Assert.True(RelayCors.IsPreflight("OPTIONS"));
        Assert.True(RelayCors.IsPreflight(" options "));
        Assert.False(RelayCors.IsPreflight("GET"));
        Assert.Equal("*", RelayCors.AllowOrigin);
        Assert.Equal("GET, HEAD, POST, OPTIONS", RelayCors.AllowMethods);
    }

    [Fact]
    public void JoinWithoutACredentialKeepsTheOldFrame()
    {
        const string room = "8d691f26b6b708eb4b0a058b4ab26c3550f9b2006086aba986f692df67957a74";
        Assert.Equal("{\"v\":1,\"room\":\"" + room + "\",\"role\":\"dial\"}", RelayJoin.Write(room, RelayRole.Dial));
        var withDevice = RelayJoin.Write(room, RelayRole.Dial, deviceId: "11111111-1111-1111-1111-111111111111");
        Assert.Contains("\"device\":\"11111111-1111-1111-1111-111111111111\"", withDevice, StringComparison.Ordinal);
        Assert.True(RelayJoin.TryParse(withDevice, out var hello));
        Assert.Equal("11111111-1111-1111-1111-111111111111", hello.Device);
        var key = RelayPlans.EntitlementKey("11111111-1111-1111-1111-111111111111");
        Assert.Equal("c30c56297025c9d0cdca357ebae9c3526f97f1155bf7938d10e243bd44bd6875", key);
        var joined = RelayJoin.Write(room, RelayRole.Listen, new RelayCredential("play-token", "relay_plus", "monthly", RelayPlans.Package, key));
        Assert.Contains("play-token", joined);
        Assert.DoesNotContain("hello from a file", joined);
    }

    [Fact]
    public void LedgerBlocksAtTheCapAndResetsOnTheNextPeriod()
    {
        var path = Path.Combine(Path.GetTempPath(), "stellarsend-quota-" + Guid.NewGuid().ToString("n") + ".json");
        var ledger = new QuotaLedger(path);
        Assert.True(ledger.TryConsume("key", "2026-09", 100, 50));
        Assert.False(ledger.TryConsume("key", "2026-09", 1, 50));
        Assert.True(ledger.TryConsume("key", "2026-10", 1, 50));
        Assert.Equal(1, ledger.Used("key", "2026-10"));
    }

    [Fact]
    public async Task OnePaidSeatCoversAFreeSeatUntilTheCap()
    {
        var table = Load();
        var path = Path.Combine(Path.GetTempPath(), "stellarsend-quota-" + Guid.NewGuid().ToString("n") + ".json");
        var key = new string('a', 64);
        var period = "2026-11-01T00:00:00Z";
        var verifier = new FakeVerifier { Result = new PlaySubscription("plus", key, period) };
        var admission = new RelayAdmission(table, verifier, new QuotaLedger(path));
        var paid = await admission.AdmitAsync(new RelayCredential("token", "relay_plus", "monthly", RelayPlans.Package, key), CancellationToken.None);
        var free = await admission.AdmitAsync(new RelayCredential(null, null, null, RelayPlans.Package, new string('b', 64)), CancellationToken.None);
        var open = admission.Combine(free, paid);
        Assert.False(open.Blocked);
        Assert.Equal("plus", open.TierId);
        Assert.Equal(key, open.Key);

        var ledger = new QuotaLedger(path);
        Assert.True(ledger.TryConsume(key, period, table.ById("plus")!.RelayBytes, table.ById("plus")!.RelayBytes));
        var blocked = new RelayAdmission(table, verifier, ledger).Combine(free, paid);
        Assert.True(blocked.Blocked);
    }

    [Fact]
    public async Task MissingServiceAccountDoesNotBlock()
    {
        var table = Load();
        var path = Path.Combine(Path.GetTempPath(), "stellarsend-quota-" + Guid.NewGuid().ToString("n") + ".json");
        var admission = new RelayAdmission(table, null, new QuotaLedger(path));
        var seat = await admission.AdmitAsync(null, CancellationToken.None);
        Assert.False(seat.Enforced);
        Assert.False(admission.Combine(seat, seat).Blocked);
    }

    [Fact]
    public void ServiceAccountJwtSignsLocally()
    {
        using var rsa = RSA.Create(2048);
        var json = JsonSerializer.Serialize(new Dictionary<string, string>
        {
            ["client_email"] = "relay@example.iam.gserviceaccount.com",
            ["private_key"] = rsa.ExportPkcs8PrivateKeyPem(),
            ["token_uri"] = "https://oauth2.googleapis.com/token"
        });
        var account = PlayServiceAccount.TryParse(json);
        Assert.NotNull(account);
        var jwt = PlayJwt.Create(account!, DateTimeOffset.UnixEpoch);
        Assert.True(PlayJwt.Verify(jwt, account.PrivateKeyPem));
        Assert.Null(PlayServiceAccount.TryParse("{\"client_email\":\"a@b.c\",\"private_key\":\"nope\",\"token_uri\":\"http://example\"}"));
    }

    [Fact]
    public void SubscriptionParserHonorsStateAndTheAccountId()
    {
        var table = Load();
        var key = new string('c', 64);
        var now = DateTimeOffset.Parse("2026-10-03T00:00:00Z");
        var active = """
            {
              "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
              "lineItems": [{ "productId": "relay_plus", "expiryTime": "2026-11-03T00:00:00Z" }],
              "externalAccountIdentifiers": { "obfuscatedExternalAccountId": "KEY" }
            }
            """.Replace("KEY", key);
        var parsed = PlaySubscriptionParser.Parse(active, table, now, "fallback");
        Assert.Equal("plus", parsed?.TierId);
        Assert.Equal(key, parsed?.QuotaKey);
        Assert.Equal("2026-11-03T00:00:00Z", parsed?.PeriodId);

        var expired = """
            { "subscriptionState": "SUBSCRIPTION_STATE_EXPIRED", "lineItems": [{ "productId": "relay_max", "expiryTime": "2026-11-03T00:00:00Z" }] }
            """;
        Assert.Null(PlaySubscriptionParser.Parse(expired, table, now, key));
        Assert.False(PlaySubscriptionParser.Entitled("SUBSCRIPTION_STATE_PAUSED", "2026-11-03T00:00:00Z", now));
        Assert.False(PlaySubscriptionParser.Entitled("SUBSCRIPTION_STATE_ON_HOLD", "2026-11-03T00:00:00Z", now));
        Assert.True(PlaySubscriptionParser.Entitled("SUBSCRIPTION_STATE_CANCELED", "2026-11-03T00:00:00Z", now));
        Assert.False(PlaySubscriptionParser.Entitled("SUBSCRIPTION_STATE_CANCELED", "2026-09-01T00:00:00Z", now));
        Assert.True(PlaySubscriptionParser.Entitled("SUBSCRIPTION_STATE_IN_GRACE_PERIOD", null, now));
    }

    private static TierTable Load() => TierTable.Parse(File.ReadAllText(Path.Combine(Repo.Root(), "relay", "tiers.json")));

    private sealed class FakeVerifier : IPlaySubscriptionVerifier
    {
        public PlaySubscription? Result { get; init; }

        public Task<PlaySubscription?> VerifyAsync(RelayCredential credential, CancellationToken ct) => Task.FromResult(Result);
    }
}
