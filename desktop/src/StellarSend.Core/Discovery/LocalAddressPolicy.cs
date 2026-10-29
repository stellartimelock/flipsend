using System.Net;
using System.Net.Sockets;

namespace StellarSend.Core.Discovery;

public static class LocalAddressPolicy
{
    public static bool IsLocalUnicast(IPAddress address, bool allowLoopback)
    {
        if (address.AddressFamily != AddressFamily.InterNetwork)
        {
            return false;
        }

        if (IPAddress.Any.Equals(address) || IPAddress.Broadcast.Equals(address))
        {
            return false;
        }

        var bytes = address.GetAddressBytes();
        if (bytes[0] >= 224)
        {
            return false;
        }

        if (bytes[0] == 127)
        {
            return allowLoopback;
        }

        if (bytes[0] == 10)
        {
            return true;
        }

        if (bytes[0] == 192 && bytes[1] == 168)
        {
            return true;
        }

        if (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31)
        {
            return true;
        }

        if (bytes[0] == 169 && bytes[1] == 254)
        {
            return true;
        }

        return false;
    }

    public static bool IsAllowedHost(string host, bool allowLoopback)
    {
        if (!IPAddress.TryParse(host, out var address))
        {
            return false;
        }

        return IsLocalUnicast(address, allowLoopback);
    }
}
