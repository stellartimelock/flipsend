using System.Text.Json;
using System.Text.Json.Serialization;

namespace StellarSend.Core.Protocol;

public static class Kinds
{
    public const string File = "file";
    public const string Text = "text";
}

public sealed record HelloBody(
    [property: JsonPropertyName("v")] int V,
    [property: JsonPropertyName("deviceId")] string DeviceId,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("fingerprint")] string Fingerprint,
    [property: JsonPropertyName("plan")]
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    string? Plan = null,
    [property: JsonPropertyName("quotaKey")]
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    string? QuotaKey = null,
    [property: JsonPropertyName("relayUsed")]
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    long? RelayUsed = null,
    [property: JsonPropertyName("relayLimit")]
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    long? RelayLimit = null,
    [property: JsonPropertyName("direct")]
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    StellarSend.Core.Transfer.DirectOffer? Direct = null,
    [property: JsonPropertyName("resume")]
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingDefault)]
    bool Resume = false);

public sealed record HelloAckBody(
    [property: JsonPropertyName("deviceId")] string DeviceId,
    [property: JsonPropertyName("fingerprint")] string Fingerprint);

public sealed record OfferBody(
    [property: JsonPropertyName("transferId")] string TransferId,
    [property: JsonPropertyName("kind")] string Kind,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("size")] long Size,
    [property: JsonPropertyName("sha256")] string Sha256,
    [property: JsonPropertyName("mime")] string Mime,
    [property: JsonPropertyName("index")]
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    int? Index = null,
    [property: JsonPropertyName("count")]
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    int? Count = null);

public sealed record RejectBody(
    [property: JsonPropertyName("transferId")] string TransferId,
    [property: JsonPropertyName("reason")] string Reason);

public sealed record CompleteBody(
    [property: JsonPropertyName("transferId")] string TransferId,
    [property: JsonPropertyName("sha256")] string Sha256);

public sealed record ErrorBody(
    [property: JsonPropertyName("transferId")] string? TransferId,
    [property: JsonPropertyName("code")] string Code,
    [property: JsonPropertyName("message")] string Message);

public sealed record TransferIdBody(
    [property: JsonPropertyName("transferId")] string TransferId);

public sealed record AcceptBody(
    [property: JsonPropertyName("transferId")] string TransferId,
    [property: JsonPropertyName("offset")] long Offset);

public sealed record AckBody(
    [property: JsonPropertyName("transferId")] string TransferId,
    [property: JsonPropertyName("offset")] long Offset);

public sealed record BeaconBody(
    [property: JsonPropertyName("v")] int V,
    [property: JsonPropertyName("app")] string App,
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("port")] int Port,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("code")]
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    string? Code = null);

public static class ProtocolJson
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = null,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
        WriteIndented = false
    };

    public static byte[] ToUtf8<T>(T value) => JsonSerializer.SerializeToUtf8Bytes(value, Options);

    public static T Read<T>(byte[] payload)
    {
        try
        {
            var value = JsonSerializer.Deserialize<T>(payload, Options);
            if (value is null)
            {
                throw new ProtocolException("Empty protocol message.");
            }

            return value;
        }
        catch (ProtocolException)
        {
            throw;
        }
        catch (JsonException)
        {
            // JsonException text can echo payload fragments. Keep the public message generic.
            throw new ProtocolException("Unreadable protocol message.", ErrorCodes.BadRequest);
        }
    }
}
