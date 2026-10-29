using Avalonia.Platform;

namespace StellarSend.App;

/// <summary>
/// Avalonia asset URIs use the assembly name. After the exe was renamed
/// to FlipSend, hard-coded avares://StellarSend/... paths no longer exist.
/// Every URI is built from the loaded assembly name so a rename cannot
/// silently break the tray icon again.
/// </summary>
public static class AppAssets
{
    public const string IconPng = "/Assets/icon.png";
    public const string IconIco = "/Assets/icon.ico";

    public static readonly string[] Paths = [IconPng, IconIco];

    public static string AssemblyName =>
        typeof(App).Assembly.GetName().Name
        ?? throw new InvalidOperationException("FlipSend assembly name is missing.");

    public static Uri UriFor(string path)
    {
        var relative = path.StartsWith('/') ? path : "/" + path;
        return new Uri($"avares://{AssemblyName}{relative}");
    }

    public static IReadOnlyList<Uri> AllUris() => Paths.Select(UriFor).ToArray();

    public static Stream Open(string path) => AssetLoader.Open(UriFor(path));

    /// <summary>
    /// Opens every listed asset and every avares resource packed in this
    /// assembly. Startup and tests both call this so a missing URI fails
    /// before the tray is created.
    /// </summary>
    public static void RequireAll()
    {
        var missing = new List<string>();
        foreach (var uri in AllUris())
        {
            if (!TryOpen(uri, out var error))
            {
                missing.Add(error);
            }
        }

        var root = new Uri($"avares://{AssemblyName}/");
        foreach (var uri in AssetLoader.GetAssets(root, null))
        {
            if (!TryOpen(uri, out var error))
            {
                missing.Add(error);
            }
        }

        if (missing.Count > 0)
        {
            throw new FileNotFoundException(string.Join(Environment.NewLine, missing));
        }
    }

    private static bool TryOpen(Uri uri, out string error)
    {
        try
        {
            if (!AssetLoader.Exists(uri))
            {
                error = $"The resource {uri} could not be found";
                return false;
            }

            using var stream = AssetLoader.Open(uri);
            if (stream.Length == 0)
            {
                error = $"The resource {uri} is empty";
                return false;
            }

            error = "";
            return true;
        }
        catch (Exception ex)
        {
            error = $"The resource {uri} could not be found ({ex.Message})";
            return false;
        }
    }
}
