namespace StellarSend.App;

public static class AppPaths
{
    public static string DataDirectory
    {
        get
        {
            var root = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            if (string.IsNullOrWhiteSpace(root))
            {
                root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".local", "share");
            }

            return Path.Combine(root, "StellarSend");
        }
    }

    public static string QueueDirectory => Path.Combine(DataDirectory, "queue");
    public static string LogFile => Path.Combine(DataDirectory, "stellar-send.log");
}
