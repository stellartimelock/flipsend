using System.Text.Json;

namespace StellarSend.Core.Transfer;

/// <summary>
/// In-memory ciphertext mailbox. Messages expire after 30 minutes. The hub never inspects plaintext.
/// </summary>
public sealed class BotMailbox
{
    private readonly object _gate = new();
    private readonly Dictionary<string, List<Slot>> _rooms = new(StringComparer.Ordinal);

    public BotPutResult Put(string room, string id, string blob, DateTimeOffset now)
    {
        if (!RelayRoom.IsToken(room))
        {
            return BotPutResult.Fail("bad_request", "Relay room is invalid.");
        }

        if (!Guid.TryParse(id, out _))
        {
            return BotPutResult.Fail("bad_request", "Message id is invalid.");
        }

        if (!BotHub.IsBlob(blob))
        {
            return BotPutResult.Fail("bad_request", "Bot message is not ciphertext.");
        }

        var expires = now.Add(BotHub.Ttl);
        lock (_gate)
        {
            Sweep(now);
            if (!_rooms.TryGetValue(room, out var list))
            {
                list = [];
                _rooms[room] = list;
            }

            list.RemoveAll(slot => string.Equals(slot.Id, id, StringComparison.OrdinalIgnoreCase));
            if (list.Count >= BotHub.MaxMailbox)
            {
                return BotPutResult.Fail("limit", "The bot mailbox is full. Pick up or wait for older notes to expire.");
            }

            list.Add(new Slot(id, blob, expires.ToUnixTimeMilliseconds()));
        }

        return BotPutResult.Succeeded(id, expires.ToUnixTimeMilliseconds());
    }

    public IReadOnlyList<BotEnvelope> Get(string room, DateTimeOffset now)
    {
        if (!RelayRoom.IsToken(room))
        {
            return [];
        }

        lock (_gate)
        {
            Sweep(now);
            if (!_rooms.TryGetValue(room, out var list))
            {
                return [];
            }

            return list.Select(slot => new BotEnvelope(slot.Id, slot.Blob, slot.ExpiresAtMs)).ToArray();
        }
    }

    public string GetJson(string room, DateTimeOffset now)
    {
        var messages = Get(room, now);
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteNumber("v", 1);
            writer.WriteBoolean("ok", true);
            writer.WriteStartArray("messages");
            foreach (var item in messages)
            {
                writer.WriteStartObject();
                writer.WriteString("id", item.Id);
                writer.WriteString("blob", item.Blob);
                writer.WriteNumber("expiresAt", item.ExpiresAtMs);
                writer.WriteEndObject();
            }

            writer.WriteEndArray();
            writer.WriteEndObject();
        }

        return System.Text.Encoding.UTF8.GetString(stream.ToArray());
    }

    public string PutJson(BotPutResult result)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteNumber("v", 1);
            writer.WriteBoolean("ok", result.Ok);
            if (result.Ok)
            {
                writer.WriteString("id", result.Id);
                writer.WriteNumber("expiresAt", result.ExpiresAtMs);
            }
            else
            {
                writer.WriteString("error", result.Error);
                writer.WriteString("message", result.Message);
            }

            writer.WriteEndObject();
        }

        return System.Text.Encoding.UTF8.GetString(stream.ToArray());
    }

    public static bool TryParsePut(string json, out string id, out string blob)
    {
        id = "";
        blob = "";
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            id = root.TryGetProperty("id", out var idNode) ? idNode.GetString() ?? "" : "";
            blob = root.TryGetProperty("blob", out var blobNode) ? blobNode.GetString() ?? "" : "";
            return Guid.TryParse(id, out _) && BotHub.IsBlob(blob);
        }
        catch (JsonException)
        {
            return false;
        }
    }

    private void Sweep(DateTimeOffset now)
    {
        var cutoff = now.ToUnixTimeMilliseconds();
        var empty = new List<string>();
        foreach (var pair in _rooms)
        {
            pair.Value.RemoveAll(slot => slot.ExpiresAtMs <= cutoff);
            if (pair.Value.Count == 0)
            {
                empty.Add(pair.Key);
            }
        }

        foreach (var room in empty)
        {
            _rooms.Remove(room);
        }
    }

    private sealed record Slot(string Id, string Blob, long ExpiresAtMs);
}

public readonly record struct BotPutResult(bool Ok, string Id, long ExpiresAtMs, string? Error, string? Message)
{
    public static BotPutResult Succeeded(string id, long expiresAtMs) => new(true, id, expiresAtMs, null, null);

    public static BotPutResult Fail(string error, string message) => new(false, "", 0, error, message);
}
