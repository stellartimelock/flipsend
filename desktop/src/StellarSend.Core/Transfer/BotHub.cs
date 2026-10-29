using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using StellarSend.Core.Pairing;
using StellarSend.Core.Protocol;

namespace StellarSend.Core.Transfer;

public static class BotHub
{
    public const string RoleTheOne = "the-one";
    public const string RoleAccounts = "accounts";
    public const string KindMessage = "message";
    public const string KindAck = "ack";
    public const string StatusPending = "pending";
    public const string StatusInbox = "inbox";
    public const string StatusOutbox = "outbox";
    public const string StatusAccepted = "accepted";
    public const string StatusDeclined = "declined";
    public const string StatusExpired = "expired";
    public const string DirectionIn = "in";
    public const string DirectionOut = "out";
    public static readonly TimeSpan Ttl = TimeSpan.FromMinutes(30);
    public const int MaxBlobBytes = 12_000;
    public const int MaxBodyChars = 4000;
    public const int MaxSubjectChars = 80;
    public const int MaxMailbox = 32;
    /// <summary>settings.json / SharedPreferences key. Missing means off. Same name on phone and PC.</summary>
    public const string ConnectionsPrefKey = "bot_ai_connections";
    private const byte WireVersion = 1;
    private const int NonceSize = 12;
    private const int TagSize = 16;

    public static string Room(string a, string b)
    {
        var left = string.CompareOrdinal(a, b) <= 0 ? a : b;
        var right = string.CompareOrdinal(a, b) <= 0 ? b : a;
        return Hash("stellarsend-bothub-v1|" + left + "|" + right);
    }

    public static byte[] SealKey(string aId, string aFp, string bId, string bFp)
    {
        if (!PinFormat.IsWellFormed(aFp) || !PinFormat.IsWellFormed(bFp))
        {
            throw new ProtocolException("Pairing fingerprints are required.");
        }

        var leftId = string.CompareOrdinal(aId, bId) <= 0 ? aId : bId;
        var rightId = string.CompareOrdinal(aId, bId) <= 0 ? bId : aId;
        var leftFp = string.CompareOrdinal(aFp, bFp) <= 0 ? aFp : bFp;
        var rightFp = string.CompareOrdinal(aFp, bFp) <= 0 ? bFp : aFp;
        return SHA256.HashData(Encoding.UTF8.GetBytes("stellarsend-bothub-seal-v1|" + leftId + "|" + rightId + "|" + leftFp + "|" + rightFp));
    }

    public static string Encrypt(byte[] key, ReadOnlySpan<byte> plain)
    {
        if (key.Length != 32)
        {
            throw new ProtocolException("Bot hub key is invalid.");
        }

        var nonce = RandomNumberGenerator.GetBytes(NonceSize);
        var cipher = new byte[plain.Length];
        var tag = new byte[TagSize];
        using var gcm = new AesGcm(key, TagSize);
        gcm.Encrypt(nonce, plain, cipher, tag);
        var blob = new byte[1 + nonce.Length + cipher.Length + tag.Length];
        blob[0] = WireVersion;
        nonce.CopyTo(blob.AsSpan(1));
        cipher.CopyTo(blob.AsSpan(1 + nonce.Length));
        tag.CopyTo(blob.AsSpan(1 + nonce.Length + cipher.Length));
        if (blob.Length > MaxBlobBytes)
        {
            throw new ProtocolException("Bot message is too large.", ErrorCodes.Limit);
        }

        return Base64Url.Encode(blob);
    }

    public static byte[] Decrypt(byte[] key, string blob)
    {
        byte[] data;
        try
        {
            data = Base64Url.Decode(blob);
        }
        catch (Exception ex) when (ex is FormatException or ArgumentException)
        {
            throw new ProtocolException("Bot message is not readable.");
        }

        if (data.Length < 1 + NonceSize + TagSize || data[0] != WireVersion)
        {
            throw new ProtocolException("Bot message is not readable.");
        }

        var nonce = data.AsSpan(1, NonceSize);
        var tag = data.AsSpan(data.Length - TagSize, TagSize);
        var cipher = data.AsSpan(1 + NonceSize, data.Length - 1 - NonceSize - TagSize);
        var plain = new byte[cipher.Length];
        using var gcm = new AesGcm(key, TagSize);
        gcm.Decrypt(nonce, cipher, tag, plain);
        return plain;
    }

    public static string? RejectReason(string subject, string body)
    {
        var cleanSubject = new string(subject.Trim().Where(c => !char.IsControl(c)).ToArray());
        var cleanBody = body.Trim();
        if (cleanSubject.Length > MaxSubjectChars)
        {
            return "Subject is too long.";
        }

        if (cleanBody.Length == 0)
        {
            return "Write a message first.";
        }

        if (cleanBody.Length > MaxBodyChars)
        {
            return "Message is too long.";
        }

        var lower = (cleanSubject + "\n" + cleanBody).ToLowerInvariant();
        if (lower.Contains("begin private", StringComparison.Ordinal) ||
            lower.Contains("mnemonic", StringComparison.Ordinal) ||
            lower.Contains("stellarsend://pair", StringComparison.Ordinal))
        {
            return "Do not put a seed, private key, or pairing code in a bot message.";
        }

        if (LooksLikeSeed(cleanBody))
        {
            return "Do not put a seed in a bot message.";
        }

        return null;
    }

    public static bool LooksLikeSeed(string text)
    {
        var words = text.Trim().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        if (words.Length is not (12 or 24))
        {
            return false;
        }

        return words.All(word => word.All(char.IsLetter) && word.Length is >= 3 and <= 8);
    }

    public static string Plaintext(string id, string from, string subject, string body, long createdAtMs, string kind = KindMessage, string? reference = null)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteNumber("v", 1);
            writer.WriteString("kind", kind);
            writer.WriteString("id", id);
            writer.WriteString("from", from);
            writer.WriteString("subject", Trim(subject, MaxSubjectChars));
            writer.WriteString("body", Trim(body, MaxBodyChars));
            writer.WriteNumber("createdAt", createdAtMs);
            if (!string.IsNullOrWhiteSpace(reference))
            {
                writer.WriteString("ref", reference);
            }

            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    public static BotPlain ParsePlain(byte[] bytes)
    {
        using var doc = JsonDocument.Parse(bytes);
        var root = doc.RootElement;
        var kind = root.TryGetProperty("kind", out var kindNode) ? kindNode.GetString() ?? KindMessage : KindMessage;
        if (kind is not (KindMessage or KindAck))
        {
            throw new ProtocolException("Bot message is not readable.");
        }

        var from = root.TryGetProperty("from", out var fromNode) ? fromNode.GetString() : null;
        if (from is not (RoleTheOne or RoleAccounts))
        {
            throw new ProtocolException("Bot message is not readable.");
        }

        var id = root.TryGetProperty("id", out var idNode) ? idNode.GetString() : null;
        if (!Guid.TryParse(id, out _))
        {
            throw new ProtocolException("Bot message is not readable.");
        }

        return new BotPlain(
            id!,
            kind,
            from,
            Trim(root.TryGetProperty("subject", out var subject) ? subject.GetString() ?? "" : "", MaxSubjectChars),
            Trim(root.TryGetProperty("body", out var body) ? body.GetString() ?? "" : "", MaxBodyChars),
            root.TryGetProperty("createdAt", out var created) && created.TryGetInt64(out var ms) ? ms : 0,
            root.TryGetProperty("ref", out var refNode) ? refNode.GetString() : null);
    }

    public static string PutBody(string id, string blob)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteString("id", id);
            writer.WriteString("blob", blob);
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    public static IReadOnlyList<BotEnvelope> ParseMailbox(string json)
    {
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        if (!root.TryGetProperty("ok", out var ok) || ok.ValueKind != JsonValueKind.True)
        {
            var message = root.TryGetProperty("message", out var msg) ? msg.GetString() : null;
            if (root.TryGetProperty("error", out var error) && error.GetString() == RelayPlans.Quota)
            {
                throw new RelayQuotaException(message ?? RelayPlans.QuotaMessage);
            }

            throw new ProtocolException(string.IsNullOrWhiteSpace(message) ? "The relay did not connect." : message);
        }

        if (!root.TryGetProperty("messages", out var array) || array.ValueKind != JsonValueKind.Array)
        {
            return [];
        }

        var list = new List<BotEnvelope>();
        foreach (var item in array.EnumerateArray())
        {
            var id = item.TryGetProperty("id", out var idNode) ? idNode.GetString() : null;
            var blob = item.TryGetProperty("blob", out var blobNode) ? blobNode.GetString() : null;
            if (string.IsNullOrWhiteSpace(id) || string.IsNullOrWhiteSpace(blob))
            {
                continue;
            }

            if (Encoding.ASCII.GetByteCount(blob) > MaxBlobBytes)
            {
                continue;
            }

            var expires = item.TryGetProperty("expiresAt", out var exp) && exp.TryGetInt64(out var ms) ? ms : 0;
            list.Add(new BotEnvelope(id, blob, expires));
        }

        return list;
    }

    public static bool NeedsPhoneApproval(string localRole, string incomingFrom) =>
        localRole == RoleTheOne && incomingFrom == RoleAccounts;

    public static bool IsBlob(string? blob) =>
        !string.IsNullOrEmpty(blob) &&
        blob.Length <= MaxBlobBytes &&
        blob.All(c => c is (>= '0' and <= '9') or (>= 'a' and <= 'z') or (>= 'A' and <= 'Z') or '-' or '_');

    private static string Hash(string text) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

    private static string Trim(string value, int max)
    {
        var clean = value.Trim();
        return clean.Length <= max ? clean : clean[..max];
    }
}

public sealed record BotPlain(string Id, string Kind, string From, string Subject, string Body, long CreatedAtMs, string? Ref);

public sealed record BotEnvelope(string Id, string Blob, long ExpiresAtMs);

public sealed record BotRecord(
    string Id,
    string Kind,
    string From,
    string Subject,
    string Body,
    long CreatedAtMs,
    long ExpiresAtMs,
    string Status,
    string Direction,
    string? Ref = null);
