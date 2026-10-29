using System.IO.Compression;
using System.Text;
using StellarSend.Core.Discovery;
using StellarSend.Core.Files;
using StellarSend.Core.Pairing;
using StellarSend.Core.Security;
using StellarSend.Core.Transfer;

namespace StellarSend.Core.Tests;

public sealed class CompanionFeatureTests
{
    [Fact]
    public void SaveFilesKeepBackupsAndBlockAMuchSmallerReplacement()
    {
        var directory = Directory.CreateTempSubdirectory("ss-save");
        var path = Path.Combine(directory.FullName, "settings.json");
        var original = Encoding.UTF8.GetBytes(new string('a', 80));
        Assert.True(DurableFile.Write(path, original, allowShrink: false));
        Assert.True(DurableFile.Write(path, Encoding.UTF8.GetBytes(new string('b', 80)), allowShrink: false));
        Assert.True(File.Exists(DurableFile.BackupPath(path, 1)));
        Assert.False(DurableFile.Write(path, [], allowShrink: false));
        Assert.Equal(80, new FileInfo(path).Length);
        Assert.False(DurableFile.Write(path, Encoding.UTF8.GetBytes("tiny"), allowShrink: false));
        Assert.True(DurableFile.Write(path, Encoding.UTF8.GetBytes("tiny"), allowShrink: true));
        Assert.Equal("tiny", File.ReadAllText(path));
        DurableFile.DeleteAll(path);
        Assert.False(File.Exists(path));
        Assert.False(File.Exists(DurableFile.BackupPath(path, 1)));
    }

    [Fact]
    public void DatesUseMonthDayYear()
    {
        var value = new DateTimeOffset(2026, 10, 2, 16, 0, 0, TimeSpan.Zero);
        var local = value.ToLocalTime();
        Assert.Equal($"{local:MM}/{local:dd}/{local:yyyy}", UsDate.Format(value));
    }

    [Fact]
    public void ShortCodeIsEightCharactersFromTheFingerprint()
    {
        var fingerprint = "abcd" + new string('e', 60);
        Assert.Equal("ABCD-EFGH".Length, ShortCode.Format(fingerprint).Length);
        Assert.Equal("ABCD-EEEE", ShortCode.Format(fingerprint));
        Assert.True(ShortCode.LooksLike("abcd-eeee"));
        Assert.True(ShortCode.Matches("ABCD EEEE", fingerprint));
        Assert.False(ShortCode.LooksLike("stellarsend://pair?v=1"));
        Assert.False(ShortCode.Matches("ffff-ffff", fingerprint));
    }

    [Fact]
    public void PairingBeaconOmitsTheCodeUntilAWindowIsOpen()
    {
        var id = Guid.Parse("11111111-1111-1111-1111-111111111111");
        var plain = Encoding.UTF8.GetString(BeaconCodec.Encode(id, 53321, "Desk"));
        Assert.DoesNotContain("\"code\"", plain, StringComparison.Ordinal);
        Assert.True(BeaconCodec.TryParse(BeaconCodec.Encode(id, 53321, "Desk", "ABCD-EEEE"), out var body));
        Assert.Equal("ABCD-EEEE", body!.Code);
    }

    [Fact]
    public void FolderArchiveRoundTripsAndRejectsZipSlip()
    {
        var root = Directory.CreateTempSubdirectory("ss-folder");
        var notes = Directory.CreateDirectory(Path.Combine(root.FullName, "Notes"));
        File.WriteAllText(Path.Combine(notes.FullName, "a.txt"), "hello");
        var nested = Directory.CreateDirectory(Path.Combine(notes.FullName, "sub"));
        File.WriteAllText(Path.Combine(nested.FullName, "b.txt"), "there");
        var zip = Path.Combine(root.FullName, "Notes.stellarsend-folder.zip");
        FolderArchive.Pack(notes.FullName, zip);
        Assert.True(FolderArchive.IsBundle(zip));
        var inbox = Directory.CreateDirectory(Path.Combine(root.FullName, "inbox"));
        var unpacked = FolderArchive.Unpack(zip, inbox.FullName);
        Assert.Equal("hello", File.ReadAllText(Path.Combine(unpacked, "a.txt")));
        Assert.Equal("there", File.ReadAllText(Path.Combine(unpacked, "sub", "b.txt")));

        var evil = Path.Combine(root.FullName, "evil.stellarsend-folder.zip");
        using (var archive = ZipFile.Open(evil, ZipArchiveMode.Create))
        {
            var entry = archive.CreateEntry("../outside.txt");
            using var writer = new StreamWriter(entry.Open());
            writer.Write("nope");
        }

        Assert.Throws<IOException>(() => FolderArchive.Unpack(evil, inbox.FullName));
        Assert.False(File.Exists(Path.Combine(root.FullName, "outside.txt")));
    }

    [Fact]
    public void InternetFallbackIsTheOutboundRelay()
    {
        Assert.True(InternetFallback.Available);
        Assert.Contains("relay", InternetFallback.Summary, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("allow local network", InternetFallback.Summary, StringComparison.OrdinalIgnoreCase);
    }
}
