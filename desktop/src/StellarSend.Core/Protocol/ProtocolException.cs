namespace StellarSend.Core.Protocol;

public class ProtocolException : Exception
{
    public string Code { get; }

    public ProtocolException(string message, string code = ErrorCodes.BadRequest)
        : base(message)
    {
        Code = code;
    }
}

public sealed class PeerRejectedException : ProtocolException
{
    public PeerRejectedException(string code, string message)
        : base(message, code)
    {
    }

    public static PeerRejectedException Unknown() =>
        new(ErrorCodes.UnknownPeer, "This device is not paired. Pair again from the QR code.");

    public static PeerRejectedException KeyChanged() =>
        new(ErrorCodes.KeyChanged, "The paired device key changed. Unpair both sides and pair again.");
}

public static class ErrorCodes
{
    public const string KeyChanged = "key_changed";
    public const string UnknownPeer = "unknown_peer";
    public const string Limit = "limit";
    public const string HashMismatch = "hash_mismatch";
    public const string Cancelled = "cancelled";
    public const string BadRequest = "bad_request";
    public const string Busy = "busy";
    public const string Declined = "declined";
    public const string Io = "io";
    public const string NotImplemented = "not_implemented";
}
