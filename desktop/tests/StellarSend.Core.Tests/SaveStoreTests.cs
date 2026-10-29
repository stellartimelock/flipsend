using System.Text;
using StellarSend.Core.Files;

namespace StellarSend.Core.Tests;

public sealed class SaveStoreTests
{
    [Fact]
    public void EmptySaveAndUnexpectedFileNeedConfirmation()
    {
        var directory = Directory.CreateTempSubdirectory("ss-save");
        var path = Path.Combine(directory.FullName, "notes.json");
        var full = Encoding.UTF8.GetBytes("{\"v\":1,\"items\":[{\"id\":1},{\"id\":2},{\"id\":3}]}");
        var created = SaveStore.Commit(path, full, confirmed: false);
        Assert.Equal(OverwriteKind.Allow, created.Kind);
        Assert.Equal(3, created.IncomingItems);

        var empty = Encoding.UTF8.GetBytes("{\"v\":1,\"items\":[]}");
        var blocked = SaveStore.Commit(path, empty, confirmed: false);
        Assert.Equal(OverwriteKind.NeedsConfirm, blocked.Kind);
        Assert.Equal(3, blocked.ExistingItems);
        Assert.Equal(0, blocked.IncomingItems);
        Assert.False(blocked.Unexpected);
        Assert.Contains("empty", blocked.Reason, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(full, File.ReadAllBytes(path));

        var big = new byte[full.Length * 20];
        Array.Fill(big, (byte)'X');
        var unexpected = SaveStore.Commit(path, big, confirmed: false);
        Assert.Equal(OverwriteKind.NeedsConfirm, unexpected.Kind);
        Assert.True(unexpected.Unexpected);
        Assert.Contains("larger", unexpected.Reason, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(3, unexpected.ExistingItems);
        Assert.Equal(full, File.ReadAllBytes(path));
        Assert.False(File.Exists(path + ".tmp"));
    }

    [Fact]
    public void OlderSaveStillOpensFromTheBackup()
    {
        var directory = Directory.CreateTempSubdirectory("ss-save-old");
        var path = Path.Combine(directory.FullName, "notes.json");
        var legacy = Encoding.UTF8.GetBytes("[{\"id\":\"a\"},{\"id\":\"b\"}]");
        File.WriteAllBytes(path, legacy);
        var opened = SaveStore.Inspect(legacy);
        Assert.True(opened.Recognized);
        Assert.Equal(2, opened.ItemCount);
        Assert.Null(opened.Version);

        var replacement = Encoding.UTF8.GetBytes("{\"v\":1,\"items\":[{\"k\":\"manualHost\",\"v\":\"10.0.0.2\"},{\"k\":\"name\",\"v\":\"PC\"}]}");
        var written = SaveStore.Commit(path, replacement, confirmed: false);
        Assert.Equal(OverwriteKind.Allow, written.Kind);
        Assert.Equal(legacy, File.ReadAllBytes(path + ".bak1"));
        Assert.Equal(2, SaveStore.Inspect(File.ReadAllBytes(path + ".bak1")).ItemCount);

        var empty = Encoding.UTF8.GetBytes("{\"v\":1,\"items\":[]}");
        Assert.Equal(OverwriteKind.Allow, SaveStore.Commit(path, empty, confirmed: true).Kind);
        Assert.Equal(OverwriteKind.Allow, SaveStore.Restore(path, 1, confirmed: true).Kind);
        Assert.Equal(2, SaveStore.Inspect(File.ReadAllBytes(path)).ItemCount);
    }

    [Fact]
    public void DatesUseUsMonthDayAndTwelveHourTime()
    {
        var moment = DateTimeOffset.Parse("2026-10-02T19:04:00Z");
        var zone = TimeZoneInfo.CreateCustomTimeZone("US-Eastern-Daylight", TimeSpan.FromHours(-4), "EDT", "EDT");
        Assert.Equal("10/02/2026 3:04 PM", UsDates.Format(moment, zone));
    }

    [Fact]
    public void FeesAreXlmWithTenPercentToTheDevelopmentFund()
    {
        Assert.True(PaymentPolicy.Accepts("xlm"));
        Assert.False(PaymentPolicy.Accepts("USD"));
        Assert.False(PaymentPolicy.MayStorePassword("secret"));
        var (fund, recipient) = PaymentPolicy.Split(10.0000000m);
        Assert.Equal(1.0000000m, fund);
        Assert.Equal(9.0000000m, recipient);
        Assert.Equal("XLM", PaymentPolicy.Asset);
        Assert.Equal("Chime", PaymentPolicy.Payout);
    }
}
