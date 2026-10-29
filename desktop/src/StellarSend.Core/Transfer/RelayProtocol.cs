using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace StellarSend.Core.Transfer;

public enum RelayRole
{
    Listen,
    Dial
}

public static class TransferPath
{
    public const string Direct = "Direct on Wi-Fi";
    public const string Cross = "Direct";
    public const string Relay = "Through relay";
    public const string Usb = "USB";
    public const string Bluetooth = "Bluetooth";
}

public static class LanSend
{
    public const int AttemptBudgetMs = 5000;
    public const int HandshakeBudgetMs = 4000;
}

public static class Reachability
{
    public const string Phone =
        "Couldn't reach your phone. For faster direct transfers, turn on the VPN's allow local network option, or enter the phone's Wi-Fi address.";

    public const string Other =
        "Couldn't reach the other device directly. For faster direct transfers, turn on the VPN's allow local network option, or enter its Wi-Fi address.";
}

public static class RelayRoom
{
    public static string Paired(Guid a, Guid b)
    {
        var left = a.ToString("D");
        var right = b.ToString("D");
        if (string.CompareOrdinal(left, right) > 0)
        {
            (left, right) = (right, left);
        }

        return Hash("stellarsend-room-v1|" + left + "|" + right);
    }

    public static string Pairing(Guid showingDevice) =>
        Hash("stellarsend-pair-v1|" + showingDevice.ToString("D"));

    public static string Invite(string code) =>
        Hash("stellarsend-invite-v1|" + (code ?? "").Trim().ToUpperInvariant());

    public static string BotMailbox(Guid a, Guid b) =>
        BotHub.Room(a.ToString("D"), b.ToString("D"));

    public static bool IsToken(string? room) =>
        room is { Length: 64 } && room.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');

    private static string Hash(string text)
    {
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(text));
        return Convert.ToHexString(bytes).ToLowerInvariant();
    }
}

public enum BotOp
{
    None = 0,
    Put = 1,
    Get = 2
}

public readonly record struct RelayHello(string Room, RelayRole Role, RelayCredential? Credential, bool Usage, BotOp Bot = BotOp.None)
{
    /// <summary>Purchase token from <c>purchaseToken</c> or the older <c>purchase</c> field.</summary>
    public string? Purchase { get; init; }

    /// <summary>Plan id from the older <c>plan</c> field: free, pro, plus, or max.</summary>
    public string? Plan { get; init; }

    /// <summary>Device id for room membership. Never an IP.</summary>
    public string? Device { get; init; }
}

public static class RelayJoin
{
    public static string Write(string room, RelayRole role, RelayCredential? credential = null, string? deviceId = null)
    {
        if (!RelayRoom.IsToken(room))
        {
            throw new ArgumentException("Relay room is invalid.");
        }

        var roleText = role == RelayRole.Listen ? "listen" : "dial";
        var clean = RelayCredential.Sanitize(credential);
        var device = Guid.TryParse(deviceId, out var parsed) ? parsed.ToString("D") : null;
        if (clean is null && device is null)
        {
            return "{\"v\":1,\"room\":\"" + room + "\",\"role\":\"" + roleText + "\"}";
        }

        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteNumber("v", 1);
            writer.WriteString("room", room);
            writer.WriteString("role", roleText);
            if (device is not null)
            {
                writer.WriteString("device", device);
            }

            WriteCredential(writer, clean);
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    public static string Usage(RelayCredential? credential)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteNumber("v", 1);
            writer.WriteString("op", "usage");
            WriteCredential(writer, RelayCredential.Sanitize(credential));
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    public static string Bot(string room, BotOp op, RelayCredential? credential = null)
    {
        if (!RelayRoom.IsToken(room))
        {
            throw new ArgumentException("Relay room is invalid.");
        }

        if (op is not (BotOp.Put or BotOp.Get))
        {
            throw new ArgumentException("Relay role is invalid.");
        }

        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteNumber("v", 1);
            writer.WriteString("op", "bot");
            writer.WriteString("room", room);
            writer.WriteString("role", op == BotOp.Put ? "put" : "get");
            WriteCredential(writer, RelayCredential.Sanitize(credential));
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    public static string Ready() => "{\"v\":1,\"ok\":true}";

    public static bool IsReady(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            return doc.RootElement.TryGetProperty("ok", out var ok) && ok.ValueKind == JsonValueKind.True;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    public static bool IsQuota(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            return doc.RootElement.TryGetProperty("error", out var error)
                && error.GetString() == RelayPlans.Quota;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    public static string QuotaMessage(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            if (doc.RootElement.TryGetProperty("message", out var message) && message.GetString() is { Length: > 0 } text)
            {
                return text;
            }
        }
        catch (JsonException)
        {
        }

        return RelayPlans.QuotaMessage;
    }

    public static bool TryParse(string json, out RelayHello hello)
    {
        hello = default;
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            var opText = root.TryGetProperty("op", out var opNode) ? opNode.GetString() : null;
            var usage = opText == "usage";
            if (opText == "bot")
            {
                if (!root.TryGetProperty("room", out var botRoomNode) || botRoomNode.GetString() is not { } botRoom || !RelayRoom.IsToken(botRoom))
                {
                    return false;
                }

                if (!root.TryGetProperty("role", out var botRoleNode) || botRoleNode.GetString() is not { } botRoleText)
                {
                    return false;
                }

                var bot = botRoleText switch
                {
                    "put" => BotOp.Put,
                    "get" => BotOp.Get,
                    _ => BotOp.None
                };
                if (bot == BotOp.None)
                {
                    return false;
                }

                hello = Describe(botRoom, RelayRole.Dial, ReadCredential(root), usage: false, bot, root);
                return true;
            }

            if (!usage)
            {
                if (!root.TryGetProperty("room", out var roomNode) || roomNode.GetString() is not { } room || !RelayRoom.IsToken(room))
                {
                    return false;
                }

                if (!root.TryGetProperty("role", out var roleNode) || roleNode.GetString() is not { } roleText)
                {
                    return false;
                }

                var role = roleText switch
                {
                    "listen" => RelayRole.Listen,
                    "dial" => RelayRole.Dial,
                    _ => (RelayRole?)null
                };
                if (role is null)
                {
                    return false;
                }

                hello = Describe(room, role.Value, ReadCredential(root), usage: false, BotOp.None, root);
                return true;
            }

            hello = Describe("", RelayRole.Dial, ReadCredential(root), usage: true, BotOp.None, root);
            return true;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    private static RelayHello Describe(string room, RelayRole role, RelayCredential? credential, bool usage, BotOp bot, JsonElement root)
    {
        var legacyPurchase = ReadLegacyPurchase(root);
        var token = credential?.PurchaseToken ?? legacyPurchase;
        if (credential is null && legacyPurchase is not null)
        {
            credential = RelayCredential.Sanitize(new RelayCredential(legacyPurchase, null, null, null, null));
            token = credential?.PurchaseToken ?? legacyPurchase;
        }

        return new RelayHello(room, role, credential, usage, bot)
        {
            Purchase = token,
            Plan = ReadLegacyPlan(root),
            Device = ReadDevice(root)
        };
    }

    /// <summary>
    /// Optional purchase token from older joins. A missing or unusable value does not reject the join.
    /// </summary>
    private static string? ReadLegacyPurchase(JsonElement root)
    {
        if (!root.TryGetProperty("purchase", out var node) || node.ValueKind != JsonValueKind.String)
        {
            return null;
        }

        var text = node.GetString();
        if (string.IsNullOrWhiteSpace(text))
        {
            return null;
        }

        var trimmed = text.Trim();
        if (trimmed.Length > 1024)
        {
            return null;
        }

        foreach (var c in trimmed)
        {
            if (c is '|' or '\r' or '\n' or '\0' || char.IsControl(c))
            {
                return null;
            }
        }

        return trimmed;
    }

    private static string? ReadDevice(JsonElement root)
    {
        if (!root.TryGetProperty("device", out var node) || node.ValueKind != JsonValueKind.String)
        {
            return null;
        }

        var text = node.GetString();
        return Guid.TryParse(text, out var id) ? id.ToString("D") : null;
    }

    private static string? ReadLegacyPlan(JsonElement root)
    {
        if (!root.TryGetProperty("plan", out var node) || node.ValueKind != JsonValueKind.String)
        {
            return null;
        }

        var text = node.GetString()?.Trim().ToLowerInvariant();
        return text is "free" or "pro" or "plus" or "max" ? text : null;
    }

    private static void WriteCredential(Utf8JsonWriter writer, RelayCredential? credential)
    {
        if (credential is null)
        {
            return;
        }

        if (!string.IsNullOrEmpty(credential.PurchaseToken))
        {
            writer.WriteString("purchaseToken", credential.PurchaseToken);
        }

        if (!string.IsNullOrEmpty(credential.ProductId))
        {
            writer.WriteString("productId", credential.ProductId);
        }

        if (!string.IsNullOrEmpty(credential.BasePlanId))
        {
            writer.WriteString("basePlanId", credential.BasePlanId);
        }

        if (!string.IsNullOrEmpty(credential.PackageName))
        {
            writer.WriteString("packageName", credential.PackageName);
        }

        if (!string.IsNullOrEmpty(credential.EntitlementKey))
        {
            writer.WriteString("entitlementKey", credential.EntitlementKey);
        }

        if (!string.IsNullOrEmpty(credential.Account))
        {
            writer.WriteString("account", credential.Account);
        }

        if (!string.IsNullOrEmpty(credential.AccountProof))
        {
            writer.WriteString("accountProof", credential.AccountProof);
        }
    }

    private static RelayCredential? ReadCredential(JsonElement root)
    {
        string? Text(string name) =>
            root.TryGetProperty(name, out var node) && node.ValueKind == JsonValueKind.String ? node.GetString() : null;

        return RelayCredential.Sanitize(new RelayCredential(
            Text("purchaseToken"),
            Text("productId"),
            Text("basePlanId"),
            Text("packageName"),
            Text("entitlementKey"),
            Text("account"),
            Text("accountProof")));
    }
}

public static class RelayAddress
{
    public static bool TryParse(string? raw, out Uri webSocket)
    {
        webSocket = null!;
        if (string.IsNullOrWhiteSpace(raw))
        {
            return false;
        }

        if (!Uri.TryCreate(raw.Trim(), UriKind.Absolute, out var uri))
        {
            return false;
        }

        var secure = uri.Scheme.Equals("wss", StringComparison.OrdinalIgnoreCase)
            || uri.Scheme.Equals("https", StringComparison.OrdinalIgnoreCase);
        var insecure = uri.Scheme.Equals("ws", StringComparison.OrdinalIgnoreCase)
            || uri.Scheme.Equals("http", StringComparison.OrdinalIgnoreCase);
        if (!secure && !insecure)
        {
            return false;
        }

        var host = uri.IdnHost;
        if (!secure && !IsLoopback(host))
        {
            return false;
        }

        var port = uri.IsDefaultPort ? (secure ? 443 : 80) : uri.Port;
        var path = string.IsNullOrEmpty(uri.AbsolutePath) || uri.AbsolutePath == "/" ? "/v1" : uri.AbsolutePath;
        var scheme = secure ? "wss" : "ws";
        if (!Uri.TryCreate($"{scheme}://{host}:{port}{path}", UriKind.Absolute, out var built))
        {
            return false;
        }

        webSocket = built;
        return true;
    }

    private static bool IsLoopback(string host) =>
        host.Equals("localhost", StringComparison.OrdinalIgnoreCase) || host == "127.0.0.1";
}
