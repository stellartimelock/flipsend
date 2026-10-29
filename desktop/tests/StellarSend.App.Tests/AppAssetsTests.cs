using Avalonia;
using Avalonia.Headless;
using Avalonia.Platform;
using Xunit;

namespace StellarSend.App.Tests;

[Collection("avalonia")]
public class AppAssetsTests
{
    static AppAssetsTests()
    {
        HeadlessAvalonia.Ensure();
    }

    [Fact]
    public void AssemblyNameIsFlipSend()
    {
        Assert.Equal("FlipSend", AppAssets.AssemblyName);
    }

    [Fact]
    public void EveryAvaresUriUsesTheLoadedAssemblyName()
    {
        Assert.NotEmpty(AppAssets.AllUris());
        foreach (var uri in AppAssets.AllUris())
        {
            Assert.Equal("avares", uri.Scheme);
            Assert.Equal(AppAssets.AssemblyName, uri.Authority);
            Assert.DoesNotContain("StellarSend", uri.ToString(), StringComparison.Ordinal);
        }
    }

    [Fact]
    public void EveryAvaresAssetOpens()
    {
        AppAssets.RequireAll();
        foreach (var uri in AppAssets.AllUris())
        {
            Assert.True(AssetLoader.Exists(uri), uri.ToString());
            using var stream = AssetLoader.Open(uri);
            Assert.True(stream.Length > 0, uri.ToString());
        }

        var packed = AssetLoader.GetAssets(new Uri($"avares://{AppAssets.AssemblyName}/"), null).ToList();
        Assert.Contains(packed, uri => uri.AbsolutePath.EndsWith("/Assets/icon.png", StringComparison.Ordinal));
        foreach (var uri in packed)
        {
            Assert.True(AssetLoader.Exists(uri), uri.ToString());
            using var stream = AssetLoader.Open(uri);
            Assert.True(stream.Length > 0, uri.ToString());
        }
    }

    [Fact]
    public void TheOldStellarSendHostDoesNotResolveTheIcon()
    {
        var stale = new Uri("avares://StellarSend/Assets/icon.png");
        Assert.False(AssetLoader.Exists(stale));
        Assert.True(AssetLoader.Exists(AppAssets.UriFor(AppAssets.IconPng)));
    }
}
