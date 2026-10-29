using StellarSend.Core.Protocol;

namespace StellarSend.Core.Files;

public static class FileNames
{
    private static readonly HashSet<string> Reserved = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    };

    public static string Sanitize(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            return "file";
        }

        var normalized = raw.Replace('\\', '/');
        var slash = normalized.LastIndexOf('/');
        var name = slash >= 0 ? normalized[(slash + 1)..] : normalized;
        if (name is "." or "..")
        {
            return "file";
        }

        var builder = new System.Text.StringBuilder(name.Length);
        foreach (var c in name)
        {
            if (c < 32 || c is '<' or '>' or ':' or '"' or '/' or '\\' or '|' or '?' or '*' )
            {
                builder.Append('_');
            }
            else
            {
                builder.Append(c);
            }
        }

        var cleaned = builder.ToString().Trim().TrimEnd('.', ' ');
        if (cleaned.Length == 0)
        {
            return "file";
        }

        var stem = cleaned;
        var dot = cleaned.LastIndexOf('.');
        if (dot > 0)
        {
            stem = cleaned[..dot];
        }

        if (Reserved.Contains(stem))
        {
            cleaned = "_" + cleaned;
        }

        if (cleaned.Length > ProtocolLimits.MaxNameChars)
        {
            var extensionIndex = cleaned.LastIndexOf('.');
            var extension = extensionIndex > 0 ? cleaned[extensionIndex..] : "";
            if (extension.Length > 20)
            {
                extension = "";
            }

            var stemLength = ProtocolLimits.MaxNameChars - extension.Length;
            cleaned = cleaned[..stemLength] + extension;
        }

        return cleaned;
    }

    public static string Allocate(string directory, string sanitizedName)
    {
        Directory.CreateDirectory(directory);
        var root = Path.GetFullPath(directory);
        var rootPrefix = root.EndsWith(Path.DirectorySeparatorChar) ? root : root + Path.DirectorySeparatorChar;
        var safe = Sanitize(sanitizedName);
        for (var i = 0; i < 1000; i++)
        {
            var fileName = i == 0 ? safe : InsertSuffix(safe, $" ({i})");
            var candidate = Path.GetFullPath(Path.Combine(root, fileName));
            if (!IsUnder(rootPrefix, candidate))
            {
                throw new InvalidOperationException("Refusing to write outside the destination folder.");
            }

            if (!File.Exists(candidate))
            {
                return candidate;
            }
        }

        throw new IOException("Too many files with that name.");
    }

    public static string Exact(string directory, string unsafeName)
    {
        Directory.CreateDirectory(directory);
        var root = Path.GetFullPath(directory);
        var rootPrefix = root.EndsWith(Path.DirectorySeparatorChar) ? root : root + Path.DirectorySeparatorChar;
        var safe = Sanitize(unsafeName);
        var candidate = Path.GetFullPath(Path.Combine(root, safe));
        if (!IsUnder(rootPrefix, candidate))
        {
            throw new InvalidOperationException("Refusing to write outside the destination folder.");
        }

        return candidate;
    }

    public static bool IsFortressBotResponse(string? unsafeName) =>
        string.Equals(Sanitize(unsafeName), "fortress-bot-response.json", StringComparison.OrdinalIgnoreCase);

    public static bool IsUnder(string rootPrefix, string candidate)
    {
        var comparison = OperatingSystem.IsWindows()
            ? StringComparison.OrdinalIgnoreCase
            : StringComparison.Ordinal;
        return candidate.StartsWith(rootPrefix, comparison);
    }

    private static string InsertSuffix(string name, string suffix)
    {
        var dot = name.LastIndexOf('.');
        if (dot <= 0)
        {
            return name + suffix;
        }

        return name[..dot] + suffix + name[dot..];
    }
}
