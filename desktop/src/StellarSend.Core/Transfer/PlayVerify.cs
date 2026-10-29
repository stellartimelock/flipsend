using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace StellarSend.Core.Transfer;

public sealed record PlaySubscription(string TierId, string QuotaKey, string PeriodId);

public interface IPlaySubscriptionVerifier
{
    Task<PlaySubscription?> VerifyAsync(RelayCredential credential, CancellationToken ct);
}

public sealed record PlayServiceAccount(string ClientEmail, string PrivateKeyPem, string TokenUri)
{
    public static PlayServiceAccount? TryLoad(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            return null;
        }

        var json = raw.TrimStart().StartsWith('{') ? raw : File.ReadAllText(raw.Trim());
        return TryParse(json);
    }

    public static PlayServiceAccount? TryParse(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            var email = root.TryGetProperty("client_email", out var emailNode) ? emailNode.GetString() : null;
            var key = root.TryGetProperty("private_key", out var keyNode) ? keyNode.GetString() : null;
            var uri = root.TryGetProperty("token_uri", out var uriNode) ? uriNode.GetString() : null;
            if (string.IsNullOrWhiteSpace(email) || !email.Contains('@', StringComparison.Ordinal))
            {
                return null;
            }

            if (string.IsNullOrWhiteSpace(key) || !key.Contains("BEGIN", StringComparison.Ordinal))
            {
                return null;
            }

            if (string.IsNullOrWhiteSpace(uri))
            {
                uri = "https://oauth2.googleapis.com/token";
            }

            if (!uri.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            {
                return null;
            }

            return new PlayServiceAccount(email, key.Replace("\\n", "\n", StringComparison.Ordinal), uri);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}

public static class PlaySubscriptionParser
{
    public static PlaySubscription? Parse(string json, TierTable tiers, DateTimeOffset now, string fallbackKey)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            var state = root.TryGetProperty("subscriptionState", out var stateNode) ? stateNode.GetString() : null;
            if (!root.TryGetProperty("lineItems", out var items) || items.ValueKind != JsonValueKind.Array)
            {
                return null;
            }

            RelayTier? best = null;
            string? expiry = null;
            foreach (var item in items.EnumerateArray())
            {
                var product = item.TryGetProperty("productId", out var productNode) ? productNode.GetString() : null;
                var tier = tiers.ByProduct(product);
                if (tier is null || !tier.Paid)
                {
                    continue;
                }

                var itemExpiry = item.TryGetProperty("expiryTime", out var expiryNode) ? expiryNode.GetString() : null;
                if (!Entitled(state, itemExpiry, now))
                {
                    continue;
                }

                if (best is null || tier.RelayBytes > best.RelayBytes)
                {
                    best = tier;
                    expiry = itemExpiry;
                }
            }

            if (best is null)
            {
                return null;
            }

            var key = fallbackKey;
            if (root.TryGetProperty("externalAccountIdentifiers", out var external)
                && external.TryGetProperty("obfuscatedExternalAccountId", out var account)
                && account.GetString() is { Length: 64 } hex
                && hex.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f'))
            {
                key = hex;
            }

            var period = string.IsNullOrWhiteSpace(expiry) ? RelayPlans.CalendarPeriod(now) : expiry;
            return new PlaySubscription(best.Id, key, period);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public static bool Entitled(string? state, string? expiry, DateTimeOffset now)
    {
        if (state is "SUBSCRIPTION_STATE_ACTIVE" or "SUBSCRIPTION_STATE_IN_GRACE_PERIOD")
        {
            return true;
        }

        if (state == "SUBSCRIPTION_STATE_CANCELED" && DateTimeOffset.TryParse(expiry, out var end))
        {
            return end > now;
        }

        return false;
    }
}

public static class PlayJwt
{
    public static string Create(PlayServiceAccount account, DateTimeOffset now)
    {
        var header = Base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}"u8);
        var iat = now.ToUnixTimeSeconds();
        var exp = iat + 3600;
        var claims = "{\"iss\":\"" + Escape(account.ClientEmail) + "\",\"scope\":\"https://www.googleapis.com/auth/androidpublisher\",\"aud\":\"" + Escape(account.TokenUri) + "\",\"iat\":" + iat + ",\"exp\":" + exp + "}";
        var signingInput = header + "." + Base64Url(Encoding.UTF8.GetBytes(claims));
        using var rsa = RSA.Create();
        rsa.ImportFromPem(account.PrivateKeyPem);
        var signature = rsa.SignData(Encoding.ASCII.GetBytes(signingInput), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        return signingInput + "." + Base64Url(signature);
    }

    public static bool Verify(string jwt, string pem)
    {
        var parts = jwt.Split('.');
        if (parts.Length != 3)
        {
            return false;
        }

        using var rsa = RSA.Create();
        rsa.ImportFromPem(pem);
        return rsa.VerifyData(Encoding.ASCII.GetBytes(parts[0] + "." + parts[1]), Base64UrlDecode(parts[2]), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
    }

    private static string Escape(string text) => text.Replace("\\", "\\\\", StringComparison.Ordinal).Replace("\"", "\\\"", StringComparison.Ordinal);

    private static string Base64Url(ReadOnlySpan<byte> bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] Base64UrlDecode(string text)
    {
        var padded = text.Replace('-', '+').Replace('_', '/');
        switch (padded.Length % 4)
        {
            case 2: padded += "=="; break;
            case 3: padded += "="; break;
        }

        return Convert.FromBase64String(padded);
    }
}

/// <summary>
/// Outbound HTTPS to Google only. The relay never sends file bytes here.
/// </summary>
public sealed class GooglePlayVerifier : IPlaySubscriptionVerifier
{
    private readonly PlayServiceAccount _account;
    private readonly TierTable _tiers;
    private readonly HttpClient _http;
    private readonly object _gate = new();
    private string? _accessToken;
    private DateTimeOffset _accessUntil;
    private readonly Dictionary<string, (PlaySubscription? Subscription, DateTimeOffset Until)> _cache = new(StringComparer.Ordinal);

    public GooglePlayVerifier(PlayServiceAccount account, TierTable tiers, HttpClient? http = null)
    {
        _account = account;
        _tiers = tiers;
        _http = http ?? new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
    }

    public async Task<PlaySubscription?> VerifyAsync(RelayCredential credential, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(credential.PurchaseToken))
        {
            return null;
        }

        var cacheKey = TokenHash.Sha256(credential.PurchaseToken);
        lock (_gate)
        {
            if (_cache.TryGetValue(cacheKey, out var cached) && cached.Until > DateTimeOffset.UtcNow)
            {
                return cached.Subscription;
            }
        }

        try
        {
            var packageName = string.IsNullOrWhiteSpace(credential.PackageName) ? _tiers.PackageName : credential.PackageName;
            var token = Uri.EscapeDataString(credential.PurchaseToken);
            var url = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + Uri.EscapeDataString(packageName) + "/purchases/subscriptionsv2/tokens/" + token;
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", await AccessTokenAsync(ct).ConfigureAwait(false));
            using var response = await _http.SendAsync(request, ct).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                Remember(cacheKey, null, TimeSpan.FromMinutes(1));
                return null;
            }

            var body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            var fallback = credential.EntitlementKey ?? cacheKey;
            var subscription = PlaySubscriptionParser.Parse(body, _tiers, DateTimeOffset.UtcNow, fallback);
            Remember(cacheKey, subscription, subscription is null ? TimeSpan.FromMinutes(1) : TimeSpan.FromMinutes(10));
            return subscription;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or IOException or JsonException or CryptographicException)
        {
            Remember(cacheKey, null, TimeSpan.FromMinutes(1));
            return null;
        }
    }

    private void Remember(string key, PlaySubscription? subscription, TimeSpan ttl)
    {
        lock (_gate)
        {
            _cache[key] = (subscription, DateTimeOffset.UtcNow.Add(ttl));
        }
    }

    private async Task<string> AccessTokenAsync(CancellationToken ct)
    {
        lock (_gate)
        {
            if (_accessToken is not null && _accessUntil > DateTimeOffset.UtcNow.AddMinutes(1))
            {
                return _accessToken;
            }
        }

        var jwt = PlayJwt.Create(_account, DateTimeOffset.UtcNow);
        using var body = new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["grant_type"] = "urn:ietf:params:oauth:grant-type:jwt-bearer",
            ["assertion"] = jwt
        });
        using var response = await _http.PostAsync(_account.TokenUri, body, ct).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        var json = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(json);
        var access = doc.RootElement.GetProperty("access_token").GetString() ?? throw new IOException("Google did not return an access token.");
        var seconds = doc.RootElement.TryGetProperty("expires_in", out var expires) ? expires.GetInt32() : 3600;
        lock (_gate)
        {
            _accessToken = access;
            _accessUntil = DateTimeOffset.UtcNow.AddSeconds(seconds);
        }

        return access;
    }
}
