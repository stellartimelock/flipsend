using System.Globalization;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using StellarSend.Core.Transfer;

namespace StellarSend.Relay;

/// <summary>
/// Monthly relay usage for one purchase or user id. Keys are "{id}|{yyyy-MM}" in UTC.
/// </summary>
public static class UsageKey
{
    public const int MaxIdLength = 1024;

    public static bool TryBuild(string? userId, DateTime utc, out string key)
    {
        key = "";
        if (!TryNormalize(userId, out var id))
        {
            return false;
        }

        key = id + "|" + utc.ToUniversalTime().ToString("yyyy-MM", CultureInfo.InvariantCulture);
        return true;
    }

    public static bool TryNormalize(string? userId, out string id)
    {
        id = "";
        if (string.IsNullOrWhiteSpace(userId))
        {
            return false;
        }

        var trimmed = userId.Trim();
        if (trimmed.Length is 0 or > MaxIdLength)
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

        id = trimmed;
        return true;
    }
}

public interface IUsageBackend
{
    string Name { get; }

    /// <summary>Returns stored totals. A missing key is 0. Throws when the store cannot be read.</summary>
    Task<IReadOnlyDictionary<string, long>> GetManyAsync(IReadOnlyList<string> keys, CancellationToken cancellationToken);

    /// <summary>Writes absolute totals. Must not replace the store when the current values cannot be read.</summary>
    Task SetManyAsync(IReadOnlyList<KeyValuePair<string, long>> values, CancellationToken cancellationToken);
}

/// <summary>
/// In-memory totals plus a debounced flush. A failed read or write leaves the memory
/// totals in place and retries the same absolute values. It never writes zeros over a store it could not read.
/// </summary>
public sealed class UsageCounter
{
    private readonly IUsageBackend _backend;
    private readonly object _gate = new();
    private readonly Dictionary<string, long> _totals = new(StringComparer.Ordinal);
    private readonly Dictionary<string, long> _dirty = new(StringComparer.Ordinal);
    private readonly Dictionary<string, long> _pending = new(StringComparer.Ordinal);
    private readonly HashSet<string> _loaded = new(StringComparer.Ordinal);
    private readonly Dictionary<string, long> _dayTotals = new(StringComparer.Ordinal);
    private readonly Dictionary<string, long> _dayPending = new(StringComparer.Ordinal);
    private readonly HashSet<string> _dayLoaded = new(StringComparer.Ordinal);
    private readonly Dictionary<string, string> _tiers = new(StringComparer.Ordinal);

    public UsageCounter(IUsageBackend backend)
    {
        _backend = backend;
    }

    public string StoreName => _backend.Name;

    public bool LastFlushFailed { get; private set; }

    public long Add(string? userId, long bytes, DateTime utc) => Add(userId, bytes, utc, null);

    public long Add(string? userId, long bytes, DateTime utc, string? tier)
    {
        if (bytes <= 0 || !UsageKey.TryNormalize(userId, out var id))
        {
            return 0;
        }

        var when = utc.ToUniversalTime();
        var monthKey = id + "|" + when.ToString("yyyy-MM", CultureInfo.InvariantCulture);
        var dayKey = id + "|" + when.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        lock (_gate)
        {
            long month;
            if (_loaded.Contains(monthKey))
            {
                month = _totals.GetValueOrDefault(monthKey) + bytes;
                _totals[monthKey] = month;
                _dirty[monthKey] = month;
            }
            else
            {
                month = _pending.GetValueOrDefault(monthKey) + bytes;
                _pending[monthKey] = month;
            }

            if (_dayLoaded.Contains(dayKey))
            {
                var day = _dayTotals.GetValueOrDefault(dayKey) + bytes;
                _dayTotals[dayKey] = day;
            }
            else
            {
                _dayPending[dayKey] = _dayPending.GetValueOrDefault(dayKey) + bytes;
            }

            if (!string.IsNullOrEmpty(tier))
            {
                _tiers[id] = RelayTiers.Normalize(tier);
            }
            else if (!_tiers.ContainsKey(id))
            {
                _tiers[id] = "free";
            }

            return month;
        }
    }

    public long Peek(string? userId, DateTime utc)
    {
        if (!UsageKey.TryBuild(userId, utc, out var key))
        {
            return 0;
        }

        lock (_gate)
        {
            if (_loaded.Contains(key))
            {
                return _totals.GetValueOrDefault(key);
            }

            return _pending.GetValueOrDefault(key);
        }
    }

    public async Task<bool> FlushAsync(CancellationToken cancellationToken = default)
    {
        string[] pendingKeys;
        lock (_gate)
        {
            pendingKeys = _pending.Keys.ToArray();
        }

        IReadOnlyDictionary<string, long> remote;
        try
        {
            remote = pendingKeys.Length == 0
                ? new Dictionary<string, long>(StringComparer.Ordinal)
                : await _backend.GetManyAsync(pendingKeys, cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            LastFlushFailed = true;
            return false;
        }

        List<KeyValuePair<string, long>> batch;
        lock (_gate)
        {
            foreach (var pair in remote)
            {
                var total = pair.Value + _pending.GetValueOrDefault(pair.Key);
                _pending.Remove(pair.Key);
                _loaded.Add(pair.Key);
                _totals[pair.Key] = total;
                _dirty[pair.Key] = total;
            }

            batch = _dirty.ToList();
        }

        if (batch.Count == 0)
        {
            LastFlushFailed = false;
            return true;
        }

        try
        {
            await _backend.SetManyAsync(batch, cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            LastFlushFailed = true;
            return false;
        }

        if (!await SaveAdminAsync(cancellationToken).ConfigureAwait(false))
        {
            LastFlushFailed = true;
            return false;
        }

        lock (_gate)
        {
            foreach (var pair in batch)
            {
                if (_dirty.TryGetValue(pair.Key, out var current) && current == pair.Value)
                {
                    _dirty.Remove(pair.Key);
                }
            }

            LastFlushFailed = false;
        }

        return true;
    }

    public async Task<string> ReportJsonAsync(DateTime utc, TimeSpan uptime, CancellationToken cancellationToken = default, long fairUseBytes = 0)
    {
        var document = new UsageDocument();
        if (_backend is IUsageAdminStore admin)
        {
            try
            {
                document = await admin.LoadAdminAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception)
            {
                document = new UsageDocument();
            }
        }

        lock (_gate)
        {
            Overlay(document);
        }

        return AdminReport.ToJson(document, utc, uptime, StoreName, RelayTiers.Load(), fairUseBytes);
    }

    private async Task<bool> SaveAdminAsync(CancellationToken cancellationToken)
    {
        if (_backend is not IUsageAdminStore admin)
        {
            return true;
        }

        UsageDocument document;
        try
        {
            document = await admin.LoadAdminAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return false;
        }

        lock (_gate)
        {
            foreach (var pair in document.Days)
            {
                if (!_dayLoaded.Contains(pair.Key) && _dayPending.ContainsKey(pair.Key))
                {
                    var total = pair.Value + _dayPending[pair.Key];
                    _dayPending.Remove(pair.Key);
                    _dayLoaded.Add(pair.Key);
                    _dayTotals[pair.Key] = total;
                }
            }

            foreach (var pair in _dayPending.ToArray())
            {
                if (_dayLoaded.Contains(pair.Key))
                {
                    continue;
                }

                var remote = document.Days.GetValueOrDefault(pair.Key);
                _dayPending.Remove(pair.Key);
                _dayLoaded.Add(pair.Key);
                _dayTotals[pair.Key] = remote + pair.Value;
            }

            Overlay(document);
        }

        try
        {
            await admin.SaveAdminAsync(document, cancellationToken).ConfigureAwait(false);
            return true;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return false;
        }
    }

    private void Overlay(UsageDocument document)
    {
        foreach (var pair in _totals)
        {
            if (_loaded.Contains(pair.Key))
            {
                document.Counts[pair.Key] = pair.Value;
            }
        }

        foreach (var pair in _pending)
        {
            document.Counts[pair.Key] = document.Counts.GetValueOrDefault(pair.Key) + pair.Value;
        }

        foreach (var pair in _dayTotals)
        {
            if (_dayLoaded.Contains(pair.Key))
            {
                document.Days[pair.Key] = pair.Value;
            }
        }

        foreach (var pair in _dayPending)
        {
            document.Days[pair.Key] = document.Days.GetValueOrDefault(pair.Key) + pair.Value;
        }

        foreach (var pair in _tiers)
        {
            document.Tiers[pair.Key] = pair.Value;
        }
    }
}

public sealed class FileUsageBackend : IUsageBackend, IUsageAdminStore
{
    private readonly string _path;
    private readonly object _gate = new();

    public FileUsageBackend(string path)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            throw new ArgumentException("Usage file path is required.", nameof(path));
        }

        _path = path;
    }

    public string Name => "file";

    public Task<IReadOnlyDictionary<string, long>> GetManyAsync(IReadOnlyList<string> keys, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (_gate)
        {
            var counts = ReadDocument().Counts;
            var map = new Dictionary<string, long>(keys.Count, StringComparer.Ordinal);
            foreach (var key in keys)
            {
                map[key] = counts.TryGetValue(key, out var value) ? value : 0;
            }

            return Task.FromResult<IReadOnlyDictionary<string, long>>(map);
        }
    }

    public Task SetManyAsync(IReadOnlyList<KeyValuePair<string, long>> values, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (values.Count == 0)
        {
            return Task.CompletedTask;
        }

        lock (_gate)
        {
            var document = ReadDocument();
            foreach (var pair in values)
            {
                document.Counts[pair.Key] = pair.Value;
            }

            WriteDocument(document);
        }

        return Task.CompletedTask;
    }

    public Task<UsageDocument> LoadAdminAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (_gate)
        {
            return Task.FromResult(ReadDocument());
        }
    }

    public Task SaveAdminAsync(UsageDocument document, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (_gate)
        {
            var current = ReadDocument();
            foreach (var pair in document.Counts)
            {
                current.Counts[pair.Key] = pair.Value;
            }

            foreach (var pair in document.Days)
            {
                current.Days[pair.Key] = pair.Value;
            }

            foreach (var pair in document.Tiers)
            {
                current.Tiers[pair.Key] = pair.Value;
            }

            WriteDocument(current);
        }

        return Task.CompletedTask;
    }

    private UsageDocument ReadDocument()
    {
        if (!File.Exists(_path))
        {
            return new UsageDocument();
        }

        string text;
        try
        {
            text = File.ReadAllText(_path);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            throw new IOException("Usage file could not be read.", ex);
        }

        if (string.IsNullOrWhiteSpace(text))
        {
            return new UsageDocument();
        }

        try
        {
            using var doc = JsonDocument.Parse(text);
            if (!doc.RootElement.TryGetProperty("counts", out var counts) || counts.ValueKind != JsonValueKind.Object)
            {
                throw new InvalidDataException("Usage file is missing counts.");
            }

            var document = new UsageDocument();
            foreach (var property in counts.EnumerateObject())
            {
                document.Counts[property.Name] = ReadLong(property.Value);
            }

            if (doc.RootElement.TryGetProperty("days", out var days) && days.ValueKind == JsonValueKind.Object)
            {
                foreach (var property in days.EnumerateObject())
                {
                    document.Days[property.Name] = ReadLong(property.Value);
                }
            }

            if (doc.RootElement.TryGetProperty("tiers", out var tiers) && tiers.ValueKind == JsonValueKind.Object)
            {
                foreach (var property in tiers.EnumerateObject())
                {
                    if (property.Value.ValueKind == JsonValueKind.String && property.Value.GetString() is { } tier)
                    {
                        document.Tiers[property.Name] = RelayTiers.Normalize(tier);
                    }
                }
            }

            return document;
        }
        catch (JsonException ex)
        {
            throw new InvalidDataException("Usage file is not valid JSON.", ex);
        }
    }

    private static long ReadLong(JsonElement value)
    {
        if (value.ValueKind != JsonValueKind.Number || !value.TryGetInt64(out var parsed) || parsed < 0)
        {
            throw new InvalidDataException("Usage file has a bad count.");
        }

        return parsed;
    }

    private void WriteDocument(UsageDocument document)
    {
        var directory = Path.GetDirectoryName(_path);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var payload = new Dictionary<string, object?>
        {
            ["v"] = 1,
            ["counts"] = document.Counts,
            ["days"] = document.Days,
            ["tiers"] = document.Tiers
        };
        var json = JsonSerializer.Serialize(payload);
        var temporary = _path + ".tmp";
        try
        {
            File.WriteAllText(temporary, json);
            File.Move(temporary, _path, overwrite: true);
        }
        catch
        {
            try
            {
                if (File.Exists(temporary))
                {
                    File.Delete(temporary);
                }
            }
            catch (IOException)
            {
            }

            throw;
        }
    }
}

public sealed class UpstashUsageBackend : IUsageBackend, IUsageAdminStore
{
    private readonly HttpClient _http;
    private readonly string _prefix;

    public UpstashUsageBackend(string restUrl, string token, HttpMessageHandler? handler = null, string keyPrefix = "flipsend:usage:")
    {
        if (string.IsNullOrWhiteSpace(restUrl) || !Uri.TryCreate(restUrl.Trim(), UriKind.Absolute, out var uri))
        {
            throw new ArgumentException("Upstash REST URL is invalid.", nameof(restUrl));
        }

        if (handler is null && uri.Scheme != Uri.UriSchemeHttps)
        {
            throw new ArgumentException("Upstash REST URL must be https.", nameof(restUrl));
        }

        if (string.IsNullOrWhiteSpace(token))
        {
            throw new ArgumentException("Upstash REST token is required.", nameof(token));
        }

        _prefix = keyPrefix;
        _http = handler is null ? new HttpClient() : new HttpClient(handler, disposeHandler: false);
        _http.BaseAddress = new Uri(uri.ToString().TrimEnd('/') + "/");
        _http.Timeout = TimeSpan.FromSeconds(10);
        _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token.Trim());
    }

    public string Name => "upstash";

    public async Task<IReadOnlyDictionary<string, long>> GetManyAsync(IReadOnlyList<string> keys, CancellationToken cancellationToken)
    {
        var map = new Dictionary<string, long>(keys.Count, StringComparer.Ordinal);
        if (keys.Count == 0)
        {
            return map;
        }

        var commands = new List<string[]>(keys.Count);
        foreach (var key in keys)
        {
            commands.Add(["GET", RedisKey(key)]);
        }

        using var response = await PostAsync("pipeline", PipelineBody(commands), cancellationToken).ConfigureAwait(false);
        var results = await ReadResultsAsync(response, cancellationToken).ConfigureAwait(false);
        if (results.Count != keys.Count)
        {
            throw new IOException("Upstash GET returned an unexpected payload.");
        }

        for (var i = 0; i < keys.Count; i++)
        {
            map[keys[i]] = ReadCount(results[i]);
        }

        return map;
    }

    private static long ReadCount(JsonElement result)
    {
        if (result.ValueKind is JsonValueKind.Null)
        {
            return 0;
        }

        if (result.ValueKind == JsonValueKind.String && long.TryParse(result.GetString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed))
        {
            return parsed < 0 ? 0 : parsed;
        }

        if (result.ValueKind == JsonValueKind.Number && result.TryGetInt64(out var number))
        {
            return number < 0 ? 0 : number;
        }

        throw new IOException("Upstash GET returned an unexpected count.");
    }

    public async Task SetManyAsync(IReadOnlyList<KeyValuePair<string, long>> values, CancellationToken cancellationToken)
    {
        if (values.Count == 0)
        {
            return;
        }

        var commands = new List<string[]>(values.Count);
        foreach (var pair in values)
        {
            commands.Add(["SET", RedisKey(pair.Key), pair.Value.ToString(CultureInfo.InvariantCulture)]);
        }

        using var response = await PostAsync("pipeline", PipelineBody(commands), cancellationToken).ConfigureAwait(false);
        var results = await ReadResultsAsync(response, cancellationToken).ConfigureAwait(false);
        if (results.Count != commands.Count)
        {
            throw new IOException("Upstash pipeline did not return every result.");
        }
    }

    public async Task<UsageDocument> LoadAdminAsync(CancellationToken cancellationToken)
    {
        using var response = await PostAsync("pipeline", PipelineBody([["GET", RedisKey("ledger")]]), cancellationToken).ConfigureAwait(false);
        var results = await ReadResultsAsync(response, cancellationToken).ConfigureAwait(false);
        if (results.Count != 1)
        {
            throw new IOException("Upstash ledger read failed.");
        }

        if (results[0].ValueKind is JsonValueKind.Null)
        {
            return new UsageDocument();
        }

        var json = results[0].ValueKind == JsonValueKind.String ? results[0].GetString() : results[0].GetRawText();
        if (string.IsNullOrWhiteSpace(json))
        {
            return new UsageDocument();
        }

        try
        {
            using var doc = JsonDocument.Parse(json);
            var document = new UsageDocument();
            CopyLongs(doc.RootElement, "counts", document.Counts);
            CopyLongs(doc.RootElement, "days", document.Days);
            if (doc.RootElement.TryGetProperty("tiers", out var tiers) && tiers.ValueKind == JsonValueKind.Object)
            {
                foreach (var property in tiers.EnumerateObject())
                {
                    if (property.Value.ValueKind == JsonValueKind.String && property.Value.GetString() is { } tier)
                    {
                        document.Tiers[property.Name] = RelayTiers.Normalize(tier);
                    }
                }
            }

            return document;
        }
        catch (JsonException ex)
        {
            throw new InvalidDataException("Upstash ledger is not valid JSON.", ex);
        }
    }

    public async Task SaveAdminAsync(UsageDocument document, CancellationToken cancellationToken)
    {
        var json = JsonSerializer.Serialize(new Dictionary<string, object?>
        {
            ["v"] = 1,
            ["counts"] = document.Counts,
            ["days"] = document.Days,
            ["tiers"] = document.Tiers
        });
        using var response = await PostAsync("pipeline", PipelineBody([["SET", RedisKey("ledger"), json]]), cancellationToken).ConfigureAwait(false);
        var results = await ReadResultsAsync(response, cancellationToken).ConfigureAwait(false);
        if (results.Count != 1)
        {
            throw new IOException("Upstash ledger write failed.");
        }
    }

    private static void CopyLongs(JsonElement root, string name, Dictionary<string, long> target)
    {
        if (!root.TryGetProperty(name, out var node) || node.ValueKind != JsonValueKind.Object)
        {
            return;
        }

        foreach (var property in node.EnumerateObject())
        {
            if (property.Value.ValueKind == JsonValueKind.Number && property.Value.TryGetInt64(out var value) && value >= 0)
            {
                target[property.Name] = value;
            }
        }
    }

    private string RedisKey(string key) => _prefix + key;

    private static string PipelineBody(List<string[]> commands)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartArray();
            foreach (var command in commands)
            {
                writer.WriteStartArray();
                foreach (var part in command)
                {
                    writer.WriteStringValue(part);
                }

                writer.WriteEndArray();
            }

            writer.WriteEndArray();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    private async Task<HttpResponseMessage> PostAsync(string path, string json, CancellationToken cancellationToken)
    {
        using var content = new StringContent(json, Encoding.UTF8, "application/json");
        HttpResponseMessage response;
        try
        {
            response = await _http.PostAsync(path, content, cancellationToken).ConfigureAwait(false);
        }
        catch (HttpRequestException ex)
        {
            throw new IOException("Upstash REST request failed.", ex);
        }
        catch (TaskCanceledException ex) when (!cancellationToken.IsCancellationRequested)
        {
            throw new IOException("Upstash REST request timed out.", ex);
        }

        if (!response.IsSuccessStatusCode)
        {
            response.Dispose();
            throw new IOException("Upstash REST request was rejected.");
        }

        return response;
    }

    private static async Task<List<JsonElement>> ReadResultsAsync(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using var doc = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken).ConfigureAwait(false);
        if (doc.RootElement.ValueKind != JsonValueKind.Array)
        {
            throw new IOException("Upstash REST payload was not a pipeline result.");
        }

        var results = new List<JsonElement>();
        foreach (var item in doc.RootElement.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object || item.TryGetProperty("error", out _))
            {
                throw new IOException("Upstash command failed.");
            }

            if (!item.TryGetProperty("result", out var result))
            {
                throw new IOException("Upstash command returned no result.");
            }

            results.Add(result.Clone());
        }

        return results;
    }
}

public static class UsageStoreFactory
{
    public const int DefaultFlushSeconds = 120;

    public static UsageCounter Create(out int flushSeconds)
    {
        flushSeconds = ReadFlushSeconds();
        var url = Environment.GetEnvironmentVariable("UPSTASH_REDIS_REST_URL");
        var token = Environment.GetEnvironmentVariable("UPSTASH_REDIS_REST_TOKEN");
        if (!string.IsNullOrWhiteSpace(url) && !string.IsNullOrWhiteSpace(token))
        {
            try
            {
                return new UsageCounter(new UpstashUsageBackend(url, token));
            }
            catch (Exception ex) when (ex is ArgumentException or InvalidOperationException)
            {
                Console.Error.WriteLine("Upstash usage store was not started. Using the file store. " + ex.Message);
            }
        }
        else if (!string.IsNullOrWhiteSpace(url) || !string.IsNullOrWhiteSpace(token))
        {
            Console.Error.WriteLine("Set both UPSTASH_REDIS_REST_URL and UPSTASH_REDIS_REST_TOKEN. Using the file usage store.");
        }

        var path = Environment.GetEnvironmentVariable("RELAY_USAGE_PATH");
        if (string.IsNullOrWhiteSpace(path))
        {
            path = Path.Combine(AppContext.BaseDirectory, "relay-usage.json");
        }

        return new UsageCounter(new FileUsageBackend(path));
    }

    public static int ReadFlushSeconds()
    {
        var raw = Environment.GetEnvironmentVariable("RELAY_USAGE_FLUSH_SECONDS");
        if (string.IsNullOrWhiteSpace(raw) || !int.TryParse(raw, NumberStyles.Integer, CultureInfo.InvariantCulture, out var seconds))
        {
            return DefaultFlushSeconds;
        }

        if (seconds < 30)
        {
            return 30;
        }

        if (seconds > 3600)
        {
            return 3600;
        }

        return seconds;
    }
}

public static class RelayPages
{
    public static bool IsHealth(string? path) => path is "/healthz" or "/healthz/";

    public static bool IsPrivacy(string? path) => path is "/privacy" or "/privacy/";

    public static byte[] HealthJson(string storeName)
    {
        var name = storeName == "upstash" ? "upstash" : "file";
        return Encoding.UTF8.GetBytes("{\"ok\":true,\"store\":\"" + name + "\"}");
    }

    public static string LoadPrivacyHtml()
    {
        using var stream = typeof(UsageCounter).Assembly.GetManifestResourceStream("privacy.html");
        if (stream is null)
        {
            return "<!DOCTYPE html><html><body><p>FlipSend privacy policy is unavailable.</p></body></html>";
        }

        using var reader = new StreamReader(stream, Encoding.UTF8);
        return reader.ReadToEnd();
    }
}
