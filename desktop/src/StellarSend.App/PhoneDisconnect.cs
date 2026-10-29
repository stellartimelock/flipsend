namespace StellarSend.App;

/// <summary>
/// Disconnect phone (unpair) always confirms first, then copies peer files
/// to a timestamped .bak, then removes the live pairing files.
/// </summary>
public static class PhoneDisconnect
{
    public const string Title = "Disconnect phone";
    public const string Body = "This removes the pairing. You'll need to scan the code again.";
    public const string Action = "Disconnect phone";

    public static async Task<bool> TryAsync(Func<Task<bool>> confirm, Action afterConfirm)
    {
        ArgumentNullException.ThrowIfNull(confirm);
        ArgumentNullException.ThrowIfNull(afterConfirm);
        if (!await confirm().ConfigureAwait(false))
        {
            return false;
        }

        afterConfirm();
        return true;
    }

    public static IReadOnlyList<string> BackupPeerFiles(string dataDirectory, DateTimeOffset now)
    {
        Directory.CreateDirectory(dataDirectory);
        var stamp = now.ToString("yyyyMMdd-HHmmss");
        var copied = new List<string>();
        foreach (var name in PeerFileNames())
        {
            var source = Path.Combine(dataDirectory, name);
            if (!File.Exists(source))
            {
                continue;
            }

            var dest = source + "." + stamp + ".bak";
            File.Copy(source, dest, overwrite: true);
            copied.Add(dest);
        }

        return copied;
    }

    private static IEnumerable<string> PeerFileNames()
    {
        yield return "peer.bin";
        for (var i = 1; i <= 5; i++)
        {
            yield return "peer.bin.bak." + i;
        }
    }
}
