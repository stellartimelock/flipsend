using System.Globalization;
using System.Text;
using System.Text.Json;

namespace StellarSend.Core.Files;

public enum OverwriteKind
{
    Allow,
    NeedsConfirm
}

public sealed record OverwriteDecision(
    OverwriteKind Kind,
    string Reason,
    int ExistingItems,
    int IncomingItems,
    bool Unexpected);

public sealed record SaveInspection(bool Recognized, int ItemCount, int? Version);

public static class UsDates
{
    public static string Format(DateTimeOffset moment, TimeZoneInfo zone)
    {
        var local = TimeZoneInfo.ConvertTime(moment, zone);
        return local.ToString("MM/dd/yyyy h:mm tt", CultureInfo.GetCultureInfo("en-US"));
    }
}

public static class AtomicFile
{
    public static void Write(string path, byte[] bytes, bool backups)
    {
        var full = Path.GetFullPath(path);
        var directory = Path.GetDirectoryName(full) ?? throw new IOException("Save folder is missing.");
        Directory.CreateDirectory(directory);
        var canonical = Path.GetFullPath(Path.Combine(directory, Path.GetFileName(full)));
        var prefix = directory.EndsWith(Path.DirectorySeparatorChar) ? directory : directory + Path.DirectorySeparatorChar;
        if (!canonical.StartsWith(prefix, StringComparison.Ordinal))
        {
            throw new IOException("Refusing to write outside the save folder.");
        }

        var temp = canonical + ".tmp";
        using (var stream = new FileStream(temp, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            stream.Write(bytes, 0, bytes.Length);
            stream.Flush(true);
        }

        if (backups && File.Exists(canonical))
        {
            Rotate(canonical);
        }

        File.Move(temp, canonical, overwrite: true);
    }

    public static void Rotate(string path)
    {
        if (!File.Exists(path))
        {
            return;
        }

        var oldest = path + ".bak" + SaveStore.Backups;
        if (File.Exists(oldest))
        {
            File.Delete(oldest);
        }

        for (var index = SaveStore.Backups - 1; index >= 1; index--)
        {
            var source = path + ".bak" + index;
            if (!File.Exists(source))
            {
                continue;
            }

            File.Move(source, path + ".bak" + (index + 1), overwrite: true);
        }

        File.Move(path, path + ".bak1", overwrite: true);
    }
}

public static class SaveStore
{
    public const int Backups = 5;

    public static OverwriteDecision Decide(byte[]? existing, byte[] incoming)
    {
        var incomingInfo = Inspect(incoming);
        if (existing is null || existing.Length == 0)
        {
            return new OverwriteDecision(OverwriteKind.Allow, "No save is on disk yet.", 0, incomingInfo.ItemCount, !incomingInfo.Recognized);
        }

        var existingInfo = Inspect(existing);
        var existingItems = existingInfo.Recognized ? existingInfo.ItemCount : 0;
        var incomingItems = incomingInfo.ItemCount;
        if (!existingInfo.Recognized || !incomingInfo.Recognized)
        {
            var biggerSurprise = !incomingInfo.Recognized && incoming.Length > existing.Length;
            var reason = biggerSurprise
                ? $"A larger file that is not a save would replace a save with {existingItems} items."
                : !incomingInfo.Recognized
                    ? $"A file that is not a save would replace a save with {existingItems} items."
                    : "The current file is not a save this version recognizes. Replacing it needs confirmation.";
            return new OverwriteDecision(OverwriteKind.NeedsConfirm, reason, existingItems, incomingItems, !incomingInfo.Recognized);
        }

        if (incomingItems == 0 && existingItems > 0)
        {
            return new OverwriteDecision(
                OverwriteKind.NeedsConfirm,
                $"An empty save would replace a save with {existingItems} items.",
                existingItems,
                incomingItems,
                false);
        }

        if (existingItems > 0 && incomingItems * 2 < existingItems)
        {
            return new OverwriteDecision(
                OverwriteKind.NeedsConfirm,
                $"A save with {incomingItems} items would replace a fuller save with {existingItems} items.",
                existingItems,
                incomingItems,
                false);
        }

        if (existing.Length > 64 && incoming.Length * 2 < existing.Length && incomingItems <= existingItems)
        {
            return new OverwriteDecision(
                OverwriteKind.NeedsConfirm,
                $"A much smaller save ({incomingItems} items) would replace a save with {existingItems} items.",
                existingItems,
                incomingItems,
                false);
        }

        return new OverwriteDecision(OverwriteKind.Allow, "Save can be updated.", existingItems, incomingItems, false);
    }

    public static OverwriteDecision Commit(string path, byte[] incoming, bool confirmed)
    {
        byte[]? existing = File.Exists(path) ? File.ReadAllBytes(path) : null;
        var decision = Decide(existing, incoming);
        if (decision.Kind == OverwriteKind.NeedsConfirm && !confirmed)
        {
            return decision;
        }

        AtomicFile.Write(path, incoming, backups: true);
        var written = Inspect(incoming);
        return new OverwriteDecision(OverwriteKind.Allow, $"Saved {Path.GetFileName(path)} ({written.ItemCount} items).", decision.ExistingItems, written.ItemCount, false);
    }

    public static OverwriteDecision Restore(string path, int index, bool confirmed)
    {
        if (index is < 1 or > Backups)
        {
            throw new IOException($"Backup {index} is not one of bak1 through bak5.");
        }

        var backup = path + ".bak" + index;
        if (!File.Exists(backup))
        {
            throw new IOException($"Backup {index} was not found.");
        }

        return Commit(path, File.ReadAllBytes(backup), confirmed);
    }

    public static SaveInspection Inspect(byte[] bytes)
    {
        var body = Encoding.UTF8.GetString(bytes).Trim().TrimStart('\uFEFF');
        if (body.Length == 0)
        {
            return new SaveInspection(true, 0, null);
        }

        try
        {
            using var document = JsonDocument.Parse(body);
            return document.RootElement.ValueKind switch
            {
                JsonValueKind.Array => new SaveInspection(true, document.RootElement.GetArrayLength(), null),
                JsonValueKind.Object => InspectObject(document.RootElement),
                _ => new SaveInspection(false, 0, null)
            };
        }
        catch (JsonException)
        {
            return new SaveInspection(false, 0, null);
        }
    }

    private static SaveInspection InspectObject(JsonElement json)
    {
        int? version = json.TryGetProperty("v", out var versionValue) && versionValue.TryGetInt32(out var parsed) ? parsed : null;
        if (json.TryGetProperty("items", out var items))
        {
            if (items.ValueKind == JsonValueKind.Array)
            {
                return new SaveInspection(true, items.GetArrayLength(), version);
            }

            return new SaveInspection(false, 0, version);
        }

        var count = 0;
        foreach (var property in json.EnumerateObject())
        {
            if (property.Name is not ("v" or "updated"))
            {
                count++;
            }
        }

        return new SaveInspection(true, count, version);
    }
}

public static class PaymentPolicy
{
    public const string Asset = "XLM";
    public const string Payout = "Chime";
    public const decimal DevelopmentFundShare = 0.10m;

    public static bool Accepts(string? asset) =>
        string.Equals(asset?.Trim(), Asset, StringComparison.OrdinalIgnoreCase);

    public static bool MayStorePassword(string? password) => false;

    public static (decimal Fund, decimal Recipient) Split(decimal amount)
    {
        if (amount < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(amount));
        }

        var fund = decimal.Round(amount * DevelopmentFundShare, 7, MidpointRounding.AwayFromZero);
        return (fund, amount - fund);
    }
}
