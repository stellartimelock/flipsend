namespace StellarSend.Core.Pairing;

/// <summary>
/// Repeat pairing with the same phone keeps the stored pin. Hosts and
/// the display name can update. A different phone still replaces trust.
/// </summary>
public static class PeerTrust
{
    public static bool SameDevice(Guid? existing, Guid incoming) =>
        existing is Guid id && id == incoming;

    public static PeerRecord Merge(PeerRecord? existing, PeerRecord incoming)
    {
        if (existing is null || existing.DeviceId != incoming.DeviceId)
        {
            return incoming;
        }

        var hosts = new List<string>();
        void add(IEnumerable<string>? values)
        {
            if (values is null)
            {
                return;
            }

            foreach (var value in values)
            {
                var host = value?.Trim();
                if (!string.IsNullOrWhiteSpace(host) && !hosts.Contains(host, StringComparer.OrdinalIgnoreCase))
                {
                    hosts.Add(host);
                }
            }
        }

        add(existing.Hosts);
        add(incoming.Hosts);
        add(new[] { existing.LastHost ?? "", incoming.LastHost ?? "" });

        return existing with
        {
            Name = string.IsNullOrWhiteSpace(incoming.Name) ? existing.Name : incoming.Name,
            LastHost = string.IsNullOrWhiteSpace(incoming.LastHost) ? existing.LastHost : incoming.LastHost,
            Port = incoming.Port > 0 ? incoming.Port : existing.Port,
            PairedAt = existing.PairedAt == default ? incoming.PairedAt : existing.PairedAt,
            Hosts = hosts,
            PhonePlan = incoming.PhonePlan ?? existing.PhonePlan,
            PhoneQuotaKey = incoming.PhoneQuotaKey ?? existing.PhoneQuotaKey,
            PhoneRelayUsed = incoming.PhoneRelayUsed ?? existing.PhoneRelayUsed,
            PhoneRelayLimit = incoming.PhoneRelayLimit ?? existing.PhoneRelayLimit
        };
    }

    public static bool ShouldWrite(PeerRecord? existing, PeerRecord merged)
    {
        if (existing is null)
        {
            return true;
        }

        if (existing.DeviceId != merged.DeviceId)
        {
            return true;
        }

        return !string.Equals(existing.Name, merged.Name, StringComparison.Ordinal)
            || !string.Equals(existing.LastHost, merged.LastHost, StringComparison.Ordinal)
            || existing.Port != merged.Port
            || !SameHosts(existing.Hosts, merged.Hosts)
            || !string.Equals(existing.Fingerprint, merged.Fingerprint, StringComparison.OrdinalIgnoreCase)
            || !string.Equals(existing.PublicKeySpkiBase64Url, merged.PublicKeySpkiBase64Url, StringComparison.Ordinal)
            || existing.PhonePlan != merged.PhonePlan
            || existing.PhoneQuotaKey != merged.PhoneQuotaKey
            || existing.PhoneRelayUsed != merged.PhoneRelayUsed
            || existing.PhoneRelayLimit != merged.PhoneRelayLimit;
    }

    private static bool SameHosts(IReadOnlyList<string>? left, IReadOnlyList<string>? right)
    {
        var a = left ?? Array.Empty<string>();
        var b = right ?? Array.Empty<string>();
        if (a.Count != b.Count)
        {
            return false;
        }

        return a.Select(value => value.Trim()).ToHashSet(StringComparer.OrdinalIgnoreCase)
            .SetEquals(b.Select(value => value.Trim()));
    }
}
