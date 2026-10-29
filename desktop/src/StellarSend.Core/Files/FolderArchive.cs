using System.IO.Compression;

namespace StellarSend.Core.Files;

public static class FolderArchive
{
    public const string Mime = "application/x-stellarsend-folder";
    public const string Suffix = ".stellarsend-folder.zip";
    public const string Marker = "stellarsend-folder.txt";

    public static bool IsBundle(string? pathOrName, string? mime = null)
    {
        if (!string.IsNullOrEmpty(mime) && string.Equals(mime, Mime, StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        var name = string.IsNullOrEmpty(pathOrName) ? "" : Path.GetFileName(pathOrName);
        return name.EndsWith(Suffix, StringComparison.OrdinalIgnoreCase);
    }

    public static void Pack(string directory, string destinationZip)
    {
        if (!Directory.Exists(directory))
        {
            throw new DirectoryNotFoundException("Folder not found.");
        }

        var root = Path.GetFullPath(directory);
        if (File.Exists(destinationZip))
        {
            File.Delete(destinationZip);
        }

        using var zip = ZipFile.Open(destinationZip, ZipArchiveMode.Create);
        var marker = zip.CreateEntry(Marker);
        using (var writer = new StreamWriter(marker.Open()))
        {
            writer.Write(new DirectoryInfo(root).Name);
        }

        foreach (var file in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
        {
            var relative = Path.GetRelativePath(root, file).Replace('\\', '/');
            if (relative.StartsWith("../", StringComparison.Ordinal) || relative.Contains("/../", StringComparison.Ordinal))
            {
                continue;
            }

            zip.CreateEntryFromFile(file, relative, CompressionLevel.Optimal);
        }
    }

    public static string Unpack(string zipPath, string inbox)
    {
        var rawName = Path.GetFileName(zipPath);
        if (rawName.EndsWith(Suffix, StringComparison.OrdinalIgnoreCase))
        {
            rawName = rawName[..^Suffix.Length];
        }

        var folderName = FileNames.Sanitize(rawName);
        if (folderName.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
        {
            folderName = FileNames.Sanitize(folderName[..^4]);
        }

        Directory.CreateDirectory(inbox);
        var root = Path.GetFullPath(inbox);
        var target = Path.Combine(root, folderName);
        for (var index = 1; Directory.Exists(target) || File.Exists(target); index++)
        {
            target = Path.Combine(root, folderName + " (" + index + ")");
        }

        Directory.CreateDirectory(target);
        using var zip = ZipFile.OpenRead(zipPath);
        foreach (var entry in zip.Entries)
        {
            if (string.Equals(entry.FullName.TrimEnd('/'), Marker, StringComparison.Ordinal))
            {
                continue;
            }

            var destination = SafeEntry(target, entry.FullName);
            if (string.IsNullOrEmpty(entry.Name))
            {
                Directory.CreateDirectory(destination);
                continue;
            }

            var parent = Path.GetDirectoryName(destination);
            if (!string.IsNullOrEmpty(parent))
            {
                Directory.CreateDirectory(parent);
            }

            entry.ExtractToFile(destination, overwrite: false);
        }

        return target;
    }

    public static string SafeEntry(string root, string entryName)
    {
        var normalized = entryName.Replace('\\', '/').TrimStart('/');
        if (normalized.Length == 0
            || normalized.Split('/', StringSplitOptions.RemoveEmptyEntries).Contains("..")
            || Path.IsPathRooted(entryName))
        {
            throw new IOException("Folder archive contains an unsafe path.");
        }

        var combined = Path.GetFullPath(Path.Combine(root, normalized.Replace('/', Path.DirectorySeparatorChar)));
        var prefix = Path.GetFullPath(root) + Path.DirectorySeparatorChar;
        var comparison = OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
        if (!combined.StartsWith(prefix, comparison) && !string.Equals(combined, Path.GetFullPath(root), comparison))
        {
            throw new IOException("Folder archive contains an unsafe path.");
        }

        return combined;
    }
}
