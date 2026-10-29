using System.Net;
using System.Text;
using System.Text.Json;
using StellarSend.Core.Transfer;
using Xunit;

namespace StellarSend.Relay.Tests;

public class UsageStoreTests
{
    private static readonly DateTime October = new(2026, 10, 3, 12, 0, 0, DateTimeKind.Utc);

    [Fact]
    public async Task AddsCoalesceIntoOneAbsoluteWrite()
    {
        var backend = new MemoryBackend();
        var counter = new UsageCounter(backend);
        for (var i = 0; i < 40; i++)
        {
            counter.Add("purchase-token", 25, October);
        }

        Assert.Equal(0, backend.Sets);
        Assert.True(await counter.FlushAsync());
        Assert.Equal(1, backend.Sets);
        Assert.Equal(1000, backend.Stored["purchase-token|2026-10"]);
        Assert.Equal(1000, counter.Peek("purchase-token", October));
    }

    [Fact]
    public async Task FailedStoreKeepsMemoryAndRetriesWithoutWiping()
    {
        var backend = new MemoryBackend { Remote = 5000, FailSets = 1 };
        var counter = new UsageCounter(backend);
        counter.Add("user-a", 7, October);
        Assert.False(await counter.FlushAsync());
        Assert.Equal(5000, backend.Remote);
        Assert.Equal(0, backend.Sets);
        Assert.Equal(5007, counter.Peek("user-a", October));
        Assert.True(counter.LastFlushFailed);

        Assert.True(await counter.FlushAsync());
        Assert.Equal(5007, backend.Stored["user-a|2026-10"]);
        Assert.False(counter.LastFlushFailed);
    }

    [Fact]
    public async Task UnreadableStoreIsNotReplaced()
    {
        var backend = new MemoryBackend { FailGets = 1, Remote = 80 };
        var counter = new UsageCounter(backend);
        counter.Add("user-a", 3, October);
        Assert.False(await counter.FlushAsync());
        Assert.Equal(0, backend.Sets);
        Assert.Equal(3, counter.Peek("user-a", October));

        Assert.True(await counter.FlushAsync());
        Assert.Equal(83, backend.Stored["user-a|2026-10"]);
    }

    [Fact]
    public async Task FileStoreSurvivesANewProcessAndDoesNotClobberACorruptFile()
    {
        var directory = Directory.CreateTempSubdirectory("flip-usage");
        var path = Path.Combine(directory.FullName, "usage.json");
        var first = new UsageCounter(new FileUsageBackend(path));
        first.Add("buyer", 12, October);
        first.Add("buyer", 8, October);
        Assert.True(await first.FlushAsync());

        var second = new UsageCounter(new FileUsageBackend(path));
        second.Add("buyer", 5, October);
        Assert.True(await second.FlushAsync());
        Assert.Equal(25, second.Peek("buyer", October));

        var corrupt = "{";
        File.WriteAllText(path, corrupt);
        var third = new UsageCounter(new FileUsageBackend(path));
        third.Add("buyer", 4, October);
        Assert.False(await third.FlushAsync());
        Assert.Equal(corrupt, File.ReadAllText(path));
        Assert.Equal(4, third.Peek("buyer", October));
    }

    [Fact]
    public async Task KeysArePurchaseIdPlusBillingMonth()
    {
        var backend = new MemoryBackend();
        var counter = new UsageCounter(backend);
        counter.Add("token", 1, October);
        counter.Add("token", 1, new DateTime(2026, 11, 1, 0, 0, 0, DateTimeKind.Utc));
        Assert.False(UsageKey.TryBuild("bad|id", October, out _));
        Assert.Equal(0, counter.Add("bad|id", 9, October));
        Assert.True(await counter.FlushAsync());
        Assert.Equal(1, backend.Stored["token|2026-10"]);
        Assert.Equal(1, backend.Stored["token|2026-11"]);
        Assert.Equal(2, backend.Stored.Count);
    }

    [Fact]
    public async Task UpstashBatchesDirtyKeysInOnePipeline()
    {
        var handler = new ScriptHandler();
        handler.Enqueue("""[{"result":null},{"result":"4"}]""");
        handler.Enqueue("""[{"result":"OK"},{"result":"OK"}]""");
        handler.Enqueue("""[{"result":null}]""");
        handler.Enqueue("""[{"result":"OK"}]""");
        var backend = new UpstashUsageBackend("https://example.upstash.io", "token", handler);
        var counter = new UsageCounter(backend);
        counter.Add("one", 2, October);
        counter.Add("one", 3, October);
        counter.Add("two", 9, October);
        Assert.Empty(handler.Bodies);
        Assert.True(await counter.FlushAsync());
        Assert.Equal(4, handler.Bodies.Count);
        Assert.Contains("\"GET\"", handler.Bodies[0], StringComparison.Ordinal);
        Assert.Contains("flipsend:usage:one|2026-10", handler.Bodies[0], StringComparison.Ordinal);
        Assert.Contains("flipsend:usage:two|2026-10", handler.Bodies[0], StringComparison.Ordinal);
        Assert.Contains("\"SET\"", handler.Bodies[1], StringComparison.Ordinal);
        Assert.Contains("\"5\"", handler.Bodies[1], StringComparison.Ordinal);
        Assert.Contains("\"13\"", handler.Bodies[1], StringComparison.Ordinal);
        Assert.Equal("Bearer token", handler.Authorization);
    }

    [Fact]
    public async Task UpstashFailureDoesNotSendAZeroAndRetries()
    {
        var handler = new ScriptHandler { Failures = 1 };
        handler.Enqueue("""[{"result":"20"}]""");
        handler.Enqueue("""[{"result":"OK"}]""");
        handler.Enqueue("""[{"result":null}]""");
        handler.Enqueue("""[{"result":"OK"}]""");
        var backend = new UpstashUsageBackend("https://example.upstash.io", "secret", handler);
        var counter = new UsageCounter(backend);
        counter.Add("buyer", 6, October);
        Assert.False(await counter.FlushAsync());
        Assert.Equal(6, counter.Peek("buyer", October));
        Assert.True(await counter.FlushAsync());
        var counted = string.Join('\n', handler.Bodies);
        Assert.Contains("\"26\"", counted, StringComparison.Ordinal);
        Assert.DoesNotContain("buyer|2026-10\",\"0\"", counted, StringComparison.Ordinal);
    }

    [Fact]
    public void HealthReportsTheActiveStore()
    {
        var health = Encoding.UTF8.GetString(RelayPages.HealthJson("upstash"));
        Assert.Equal("{\"ok\":true,\"store\":\"upstash\"}", health);
        Assert.Equal("{\"ok\":true,\"store\":\"file\"}", Encoding.UTF8.GetString(RelayPages.HealthJson("file")));
        Assert.True(RelayPages.IsHealth("/healthz"));
        Assert.True(RelayPages.IsPrivacy("/privacy"));
    }

    [Fact]
    public void PrivacyPageMatchesThePublishedPolicy()
    {
        var html = RelayPages.LoadPrivacyHtml();
        Assert.Contains("FlipSend", html, StringComparison.Ordinal);
        Assert.Contains("stellartimelock@gmail.com", html, StringComparison.Ordinal);
        Assert.Contains("ciphertext only", html, StringComparison.Ordinal);
        Assert.Contains("no ads and no analytics", html, StringComparison.Ordinal);
        Assert.Contains("purchase token is sent to our relay only to verify the plan with Google", html, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("end-to-end encrypted", html, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void JoinWithoutPurchaseStillParses()
    {
        var room = new string('a', 64);
        Assert.True(RelayJoin.TryParse(RelayJoin.Write(room, RelayRole.Listen), out var hello));
        Assert.Null(hello.Purchase);
        var withPurchase = "{\"v\":1,\"room\":\"" + room + "\",\"role\":\"dial\",\"purchase\":\"play-token\"}";
        Assert.True(RelayJoin.TryParse(withPurchase, out var billed));
        Assert.Equal("play-token", billed.Purchase);
        Assert.Equal(RelayRole.Dial, billed.Role);
        Assert.Null(billed.Plan);
        var withPlan = "{\"v\":1,\"room\":\"" + room + "\",\"role\":\"listen\",\"plan\":\"plus\"}";
        Assert.True(RelayJoin.TryParse(withPlan, out var planned));
        Assert.Equal("plus", planned.Plan);
        Assert.Null(planned.Purchase);
    }

    [Fact]
    public void AdminRouteIsHiddenWithoutATokenAndRejectsABadOne()
    {
        Assert.Equal(404, AdminAuth.Authorize(null, null));
        Assert.Equal(404, AdminAuth.Authorize("Bearer secret", "  "));
        Assert.Equal(401, AdminAuth.Authorize(null, "secret"));
        Assert.Equal(401, AdminAuth.Authorize("Bearer wrong", "secret"));
        Assert.Equal(200, AdminAuth.Authorize("Bearer secret", "secret"));
        Assert.True(AdminAuth.IsPath("/v1/admin/usage"));
    }

    [Fact]
    public async Task AdminReportHasAggregatesAndNoUserIds()
    {
        var directory = Directory.CreateTempSubdirectory("flip-admin");
        var counter = new UsageCounter(new FileUsageBackend(Path.Combine(directory.FullName, "usage.json")));
        var secret = "play-token-secret";
        var other = "second-buyer";
        counter.Add(secret, 450_000_000, October, "free");
        counter.Add(other, 9_000_000_000, October, "pro");
        counter.Add("plus-user", 1_000_000_000, October, "plus");
        Assert.True(await counter.FlushAsync());
        var json = await counter.ReportJsonAsync(October, TimeSpan.FromSeconds(90));
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        Assert.Equal("2026-10", root.GetProperty("month").GetString());
        Assert.Equal(450_000_000L + 9_000_000_000L + 1_000_000_000L, root.GetProperty("totalBytes").GetInt64());
        Assert.Equal(root.GetProperty("totalBytes").GetInt64(), root.GetProperty("todayBytes").GetInt64());
        Assert.Equal(1, root.GetProperty("freeUsers").GetInt32());
        Assert.Equal(1, root.GetProperty("subscribers").GetProperty("pro").GetInt32());
        Assert.Equal(1, root.GetProperty("subscribers").GetProperty("plus").GetInt32());
        Assert.Equal(0, root.GetProperty("subscribers").GetProperty("max").GetInt32());
        Assert.Equal(2, root.GetProperty("nearCap").GetInt32());
        Assert.Equal(90, root.GetProperty("uptimeSeconds").GetInt64());
        Assert.Equal("file", root.GetProperty("store").GetString());
        Assert.True(root.GetProperty("egressUsd").GetDecimal() > 0);
        Assert.DoesNotContain(secret, json, StringComparison.Ordinal);
        Assert.DoesNotContain(other, json, StringComparison.Ordinal);
        Assert.DoesNotContain("plus-user", json, StringComparison.Ordinal);
    }

    [Fact]
    public void TiersMatchTheWebsiteAndTheAndroidAsset()
    {
        var root = RepoRoot();
        using var tiers = JsonDocument.Parse(File.ReadAllText(Path.Combine(root, "relay", "tiers.json")));
        var plans = tiers.RootElement.GetProperty("plans");
        Assert.Equal(4, plans.GetArrayLength());
        Assert.Equal(2.9999m, tiers.RootElement.GetProperty("appPriceUsd").GetDecimal());
        Expect(plans[0], "free", 2.9999m, 0.1m, included: true, recommended: false);
        Expect(plans[1], "pro", 2.99m, 10m, included: false, recommended: false);
        Expect(plans[2], "plus", 5.99m, 30m, included: false, recommended: true);
        Expect(plans[3], "max", 11.99m, 60m, included: false, recommended: false);

        var asset = File.ReadAllText(Path.Combine(root, "android", "app", "src", "main", "assets", "tiers.json"));
        Assert.Equal(Normalize(File.ReadAllText(Path.Combine(root, "relay", "tiers.json"))), Normalize(asset));
        var site = File.ReadAllText(Path.Combine(root, "site", "index.html"));
        Assert.Contains("$2.9999", site, StringComparison.Ordinal);
        Assert.Contains("100 MB", site, StringComparison.Ordinal);
        Assert.Contains("$5", site, StringComparison.Ordinal);
        Assert.Contains("$10", site, StringComparison.Ordinal);
        Assert.Contains("$15", site, StringComparison.Ordinal);
        Assert.Contains("Wi-Fi transfers still work", site, StringComparison.Ordinal);
    }

    private static void Expect(JsonElement plan, string id, decimal usd, decimal gb, bool included, bool recommended)
    {
        Assert.Equal(id, plan.GetProperty("id").GetString());
        Assert.Equal(usd, plan.GetProperty("usd").GetDecimal());
        Assert.Equal(gb, plan.GetProperty("relayGb").GetDecimal());
        Assert.Equal(included, plan.GetProperty("includedWithApp").GetBoolean());
        Assert.Equal(recommended, plan.GetProperty("recommended").GetBoolean());
    }

    private static string Normalize(string json) => json.Replace("\r", "", StringComparison.Ordinal).Trim();

    private static string RepoRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (File.Exists(Path.Combine(dir.FullName, "relay", "tiers.json")))
            {
                return dir.FullName;
            }

            dir = dir.Parent;
        }

        throw new InvalidOperationException("Could not find the repository root.");
    }

    private sealed class MemoryBackend : IUsageBackend
    {
        public Dictionary<string, long> Stored { get; } = new(StringComparer.Ordinal);
        public long Remote { get; set; }
        public int FailGets { get; set; }
        public int FailSets { get; set; }
        public int Sets { get; private set; }

        public string Name => "upstash";

        public Task<IReadOnlyDictionary<string, long>> GetManyAsync(IReadOnlyList<string> keys, CancellationToken cancellationToken)
        {
            if (FailGets > 0)
            {
                FailGets--;
                throw new IOException("unreachable");
            }

            var map = new Dictionary<string, long>(keys.Count, StringComparer.Ordinal);
            foreach (var key in keys)
            {
                map[key] = Stored.TryGetValue(key, out var value) ? value : Remote;
            }

            return Task.FromResult<IReadOnlyDictionary<string, long>>(map);
        }

        public Task SetManyAsync(IReadOnlyList<KeyValuePair<string, long>> values, CancellationToken cancellationToken)
        {
            if (FailSets > 0)
            {
                FailSets--;
                throw new IOException("unreachable");
            }

            Sets++;
            foreach (var pair in values)
            {
                Stored[pair.Key] = pair.Value;
            }

            return Task.CompletedTask;
        }
    }

    private sealed class ScriptHandler : HttpMessageHandler
    {
        private readonly Queue<string> _responses = new();
        public List<string> Bodies { get; } = [];
        public string? Authorization { get; private set; }
        public int Failures { get; set; }

        public void Enqueue(string json) => _responses.Enqueue(json);

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Authorization = request.Headers.Authorization?.ToString();
            var body = request.Content is null ? "" : request.Content.ReadAsStringAsync(cancellationToken).GetAwaiter().GetResult();
            if (Failures > 0)
            {
                Failures--;
                throw new HttpRequestException("down");
            }

            Bodies.Add(body);
            var json = _responses.Count > 0 ? _responses.Dequeue() : "[]";
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(json, Encoding.UTF8, "application/json")
            });
        }
    }
}
