using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace StellarSend.Core.Discovery;

public static class NetworkInfo
{
    public static IReadOnlyList<string> ListLanIpv4() => AdapterRank.Rank(ReadAdapters());

    public static bool VpnOn() => VpnOn(ReadAdapters());

    public static bool VpnOn(IEnumerable<AdapterSnapshot> adapters) =>
        adapters.Any(adapter => adapter.IsUp && (adapter.Medium == AdapterMedium.Vpn || AdapterRank.IsVpnName(adapter.Name) || AdapterRank.IsTunName(adapter.Name)));

    public static bool VpnTunnelActive() => VpnOn();

    public static bool VpnTunnelActive(IEnumerable<AdapterSnapshot> adapters) =>
        adapters.Any(adapter => adapter.IsUp && adapter.Medium == AdapterMedium.Vpn);

    public static string? TryGetLanIpv4() => ListLanIpv4().FirstOrDefault();

    public static IReadOnlyList<LanInterface> ListInterfaces()
    {
        var found = new List<LanInterface>();
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback)
            {
                continue;
            }

            var label = nic.Name + " " + nic.Description;
            if (AdapterRank.IsVirtual(label) || AdapterRank.IsVpnName(label) || AdapterRank.IsTunName(label))
            {
                continue;
            }

            var medium = AdapterRank.Classify(label, nic.NetworkInterfaceType);
            if (medium == AdapterMedium.Vpn)
            {
                continue;
            }

            var prefer = medium is AdapterMedium.Wifi or AdapterMedium.Ethernet;
            foreach (var address in nic.GetIPProperties().UnicastAddresses)
            {
                if (address.Address.AddressFamily != AddressFamily.InterNetwork)
                {
                    continue;
                }

                var text = address.Address.ToString();
                if (!AdapterRank.IsCandidateAddress(text))
                {
                    continue;
                }

                if (!prefer && AdapterRank.IsVpnRange(text))
                {
                    continue;
                }

                var prefix = address.PrefixLength;
                if (prefix <= 0 && address.IPv4Mask is not null)
                {
                    prefix = PrefixBits(address.IPv4Mask);
                }

                found.Add(new LanInterface(text, prefix, prefer));
            }
        }

        return found;
    }

    private static int PrefixBits(IPAddress mask)
    {
        var bits = 0;
        foreach (var part in mask.GetAddressBytes())
        {
            var value = (int)part;
            while ((value & 0x80) != 0)
            {
                bits++;
                value = (value << 1) & 0xFF;
            }

            if (value != 0)
            {
                break;
            }
        }

        return bits;
    }

    public static string? TryGetGateway()
    {
        string? fallback = null;
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback)
            {
                continue;
            }

            var label = nic.Name + " " + nic.Description;
            if (AdapterRank.IsVirtual(label))
            {
                continue;
            }

            var gateway = FirstGateway(nic);
            if (gateway is null)
            {
                continue;
            }

            var medium = AdapterRank.Classify(label, nic.NetworkInterfaceType);
            if (medium is AdapterMedium.Wifi or AdapterMedium.Ethernet)
            {
                return gateway;
            }

            fallback ??= gateway;
        }

        return fallback;
    }

    public static IReadOnlyList<AdapterSnapshot> ReadAdapters()
    {
        var found = new List<AdapterSnapshot>();
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback)
            {
                continue;
            }

            var label = nic.Name + " " + nic.Description;
            var up = nic.OperationalStatus == OperationalStatus.Up;
            var gateway = FirstGateway(nic) is not null;
            var medium = AdapterRank.Classify(label, nic.NetworkInterfaceType);
            var virtualAdapter = AdapterRank.IsVirtual(label);
            foreach (var address in nic.GetIPProperties().UnicastAddresses)
            {
                if (address.Address.AddressFamily != AddressFamily.InterNetwork)
                {
                    continue;
                }

                found.Add(new AdapterSnapshot(label, address.Address.ToString(), up, gateway, medium, virtualAdapter));
            }
        }

        return found;
    }

    private static string? FirstGateway(NetworkInterface nic)
    {
        foreach (var gateway in nic.GetIPProperties().GatewayAddresses)
        {
            if (gateway.Address.AddressFamily == AddressFamily.InterNetwork
                && !IPAddress.Any.Equals(gateway.Address)
                && LocalAddressPolicy.IsLocalUnicast(gateway.Address, allowLoopback: false))
            {
                return gateway.Address.ToString();
            }
        }

        return null;
    }
}
