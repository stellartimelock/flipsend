using StellarSend.Core.Transfer;

namespace StellarSend.Core.Tests;

public sealed class TransferAttentionTests
{
    [Fact]
    public void TransfersStayInTheBackgroundUnlessTheSettingIsOn()
    {
        Assert.False(TransferAttention.BringToFrontByDefault);
        Assert.False(TransferAttention.BringsWindowForward(settingOn: false));
        Assert.False(TransferAttention.AsksBeforeReceive(settingOn: false));
        Assert.False(TransferAttention.OpensReceivedText(settingOn: false));

        Assert.True(TransferAttention.BringsWindowForward(settingOn: true));
        Assert.True(TransferAttention.AsksBeforeReceive(settingOn: true));
        Assert.True(TransferAttention.OpensReceivedText(settingOn: true));
        Assert.False(TransferAttention.AsksBeforeReceive(settingOn: true, "fortress-bot-response.json"));
        Assert.False(TransferAttention.AsksBeforeReceive(settingOn: false, "fortress-bot-response.json"));
    }

    [Fact]
    public void NoticesNameTheFileAndStayShort()
    {
        Assert.Equal("Sending notes.txt", TransferAttention.Sending("notes.txt"));
        Assert.Equal("Sending 3 files", TransferAttention.SendingCount(3));
        Assert.Equal("Sent notes.txt", TransferAttention.Sent(" notes.txt "));
        Assert.Equal("Could not send a file", TransferAttention.CouldNotSend("  "));
        Assert.Equal("Received photo.jpg", TransferAttention.Received("photo.jpg"));
        Assert.Equal("Sent report.pdf", TransferAttention.Finished("report.pdf", "Sent"));
        Assert.Equal("Cancelled report.pdf", TransferAttention.Finished("report.pdf", "Cancelled"));
        Assert.Equal("Could not send report.pdf", TransferAttention.Finished("report.pdf", "The phone did not answer"));

        var longName = new string('a', 90) + ".pdf";
        var label = TransferAttention.Label(longName);
        Assert.Equal(80, label.Length);
        Assert.EndsWith("...", label);
        Assert.DoesNotContain("Activate", TransferAttention.Received(longName), StringComparison.Ordinal);
    }
}
