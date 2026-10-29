namespace StellarSend.Core.Transfer;

/// <summary>
/// The PC's LAN addresses changed. Drop the old sockets and listen again.
/// The first snapshot is not a change.
/// </summary>
public static class NetworkHop
{
    public const string Line = "Switching networks";

    public static bool Changed(IReadOnlyCollection<string>? before, IReadOnlyCollection<string> after)
    {
        if (before is null)
        {
            return false;
        }

        return !Normalize(before).SetEquals(Normalize(after));
    }

    private static HashSet<string> Normalize(IReadOnlyCollection<string> addresses)
    {
        var set = new HashSet<string>(StringComparer.Ordinal);
        foreach (var address in addresses)
        {
            if (!string.IsNullOrWhiteSpace(address))
            {
                set.Add(address.Trim());
            }
        }

        return set;
    }
}
