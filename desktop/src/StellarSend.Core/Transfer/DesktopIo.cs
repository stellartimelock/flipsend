using System.Net.Security;
using System.Net.Sockets;
using System.Reflection;

namespace StellarSend.Core.Transfer;

internal interface IInputPending
{
    bool InputPending { get; }
}

/// <summary>
/// Sees whether the next remote-desktop read would block. A blocked read must
/// not overlap a frame write on SslStream.
/// </summary>
internal static class DesktopIo
{
    private static readonly PropertyInfo? InnerStreamProperty =
        typeof(AuthenticatedStream).GetProperty("InnerStream", BindingFlags.Instance | BindingFlags.Public);

    private static readonly FieldInfo? BufferField =
        typeof(SslStream).GetField("_buffer", BindingFlags.Instance | BindingFlags.NonPublic);

    public static bool CanTurn(Stream stream)
    {
        try
        {
            return Probe(stream, 0, out _);
        }
        catch (Exception)
        {
            return false;
        }
    }

    public static bool Pending(Stream stream)
    {
        try
        {
            return Probe(stream, 0, out var pending) && pending;
        }
        catch (Exception)
        {
            return false;
        }
    }

    private static bool Probe(Stream? stream, int depth, out bool pending)
    {
        pending = false;
        if (stream is null || depth > 4) return false;
        if (stream is IInputPending marked)
        {
            pending = marked.InputPending;
            return true;
        }

        if (stream is NetworkStream network)
        {
            pending = network.DataAvailable;
            return true;
        }

        if (stream is SslStream ssl)
        {
            if (DecryptedReady(ssl))
            {
                pending = true;
                return true;
            }

            if (InnerStreamProperty?.GetValue(ssl) is not Stream inner) return false;
            return Probe(inner, depth + 1, out pending);
        }

        return false;
    }

    private static bool DecryptedReady(SslStream ssl)
    {
        var buffer = BufferField?.GetValue(ssl);
        if (buffer is null) return false;
        var length = buffer.GetType().GetProperty("DecryptedLength")?.GetValue(buffer);
        return length is int count && count > 0;
    }
}
