using StellarSend.Core.Files;
using StellarSend.Core.Transfer;

namespace StellarSend.Core.Tests;

public sealed class ApkSendOrderTests
{
    [Fact]
    public void FlipSendApkIsRecognizedFromLocalBuildName()
    {
        Assert.True(ApkSendOrder.IsFlipSendApk(@"C:\Users\user\Downloads\STELLAR_SEND-1.5.24-main.apk"));
        Assert.True(ApkSendOrder.IsFlipSendApk("org.stellartimelock.stellarsend-debug.apk"));
        Assert.False(ApkSendOrder.IsFlipSendApk("PRESS-1.3.0.apk"));
        Assert.False(ApkSendOrder.IsFlipSendApk("notes.txt"));
    }

    [Fact]
    public void SelfLastPutsFlipSendAtTheEnd()
    {
        var ordered = ApkSendOrder.SelfLast(
        [
            @"C:\apks\STELLAR_SEND-1.5.24-main.apk",
            @"C:\apks\PRESS-1.3.0.apk",
            @"C:\apks\NATURALLY-2.0.apk"
        ]);
        Assert.Equal(
        [
            @"C:\apks\PRESS-1.3.0.apk",
            @"C:\apks\NATURALLY-2.0.apk",
            @"C:\apks\STELLAR_SEND-1.5.24-main.apk"
        ], ordered);
    }

    [Fact]
    public void LanSendBudgetIsShortEnoughForRelayFallback()
    {
        Assert.Equal(5000, LanSend.AttemptBudgetMs);
        Assert.True(LanSend.AttemptBudgetMs < 30_000);
    }
}
