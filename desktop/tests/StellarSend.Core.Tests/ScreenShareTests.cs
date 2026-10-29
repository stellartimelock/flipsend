using StellarSend.Core.Companion;
using StellarSend.Core.Protocol;

namespace StellarSend.Core.Tests;

public sealed class ScreenShareTests
{
    [Fact]
    public void OnlyARealJpegIsShown()
    {
        Assert.False(ScreenShare.TryJpeg(null, out _));
        Assert.False(ScreenShare.TryJpeg([1, 2, 3], out _));
        Assert.True(ScreenShare.TryJpeg([0xFF, 0xD8, 1, 2, 3], out var jpeg));
        Assert.Equal(5, jpeg.Length);
        Assert.Equal("screen.share", CompanionServices.ScreenShare);
        Assert.Equal(13, (byte)MessageType.ScreenFrame);
        Assert.Equal("remote.control", CompanionServices.RemoteControl);
        Assert.Equal(14, (byte)MessageType.RemoteInput);
        Assert.Equal("Cast to TV", TvCast.Button);
        Assert.Equal("ms-settings:project", TvCast.ProjectUri);
        Assert.Contains("Project sheet", TvCast.Started, StringComparison.Ordinal);
    }
}
