namespace StellarSend.Core.Files;

public sealed class SaveBlockedException : IOException
{
    public SaveBlockedException()
        : base("Refusing to replace a save file with an empty or much smaller one.")
    {
    }
}

public static class DurableFile
{
    public const int BackupCount = 5;
    public const long MinGuardedBytes = 32;

    public static bool WouldReplaceWithMuchSmaller(long existing, long incoming)
    {
        if (existing <= 0)
        {
            return false;
        }

        if (incoming <= 0)
        {
            return true;
        }

        if (existing < MinGuardedBytes)
        {
            return false;
        }

        return incoming * 2 < existing;
    }

    public static bool Write(string path, ReadOnlySpan<byte> content, bool allowShrink)
    {
        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        if (File.Exists(path))
        {
            var existing = new FileInfo(path).Length;
            if (!allowShrink && WouldReplaceWithMuchSmaller(existing, content.Length))
            {
                return false;
            }

            ShiftBackups(path);
            File.Copy(path, BackupPath(path, 1), overwrite: true);
        }

        var temp = path + ".tmp";
        using (var stream = new FileStream(temp, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            stream.Write(content);
            stream.Flush(flushToDisk: true);
        }

        File.Move(temp, path, overwrite: true);
        return true;
    }

    public static void WriteOrThrow(string path, ReadOnlySpan<byte> content, bool allowShrink = false)
    {
        if (!Write(path, content, allowShrink))
        {
            throw new SaveBlockedException();
        }
    }

    public static void DeleteAll(string path)
    {
        if (File.Exists(path))
        {
            File.Delete(path);
        }

        for (var i = 1; i <= BackupCount; i++)
        {
            var backup = BackupPath(path, i);
            if (File.Exists(backup))
            {
                File.Delete(backup);
            }
        }

        var temp = path + ".tmp";
        if (File.Exists(temp))
        {
            File.Delete(temp);
        }
    }

    public static string BackupPath(string path, int index) => path + ".bak." + index;

    private static void ShiftBackups(string path)
    {
        for (var i = BackupCount; i >= 2; i--)
        {
            var source = BackupPath(path, i - 1);
            var destination = BackupPath(path, i);
            if (!File.Exists(source))
            {
                continue;
            }

            if (File.Exists(destination))
            {
                File.Delete(destination);
            }

            File.Move(source, destination);
        }
    }
}
