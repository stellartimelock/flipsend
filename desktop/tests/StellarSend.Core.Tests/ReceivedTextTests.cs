using StellarSend.Core.Files;

namespace StellarSend.Core.Tests;

public class ReceivedTextTests
{
    [Fact]
    public void TextLikeNamesAreTheOnesYouCanCopy()
    {
        Assert.True(ReceivedText.IsTextLike("note.txt"));
        Assert.True(ReceivedText.IsTextLike(@"C:\inbox\from-phone.JSON"));
        Assert.True(ReceivedText.IsTextLike("readme.md"));
        Assert.False(ReceivedText.IsTextLike("photo.png"));
        Assert.False(ReceivedText.IsTextLike("app.apk"));
        Assert.False(ReceivedText.IsTextLike("fortress-bot-response.json"));
        Assert.False(ReceivedText.IsTextLike(@"C:\Users\user\AppData\Local\StellarSend\inbox\fortress-bot-response.json"));
        Assert.False(ReceivedText.IsTextLike(null));
    }

    [Fact]
    public void TryReadReturnsTheFileText()
    {
        var directory = Directory.CreateTempSubdirectory("ss-received-text");
        var path = Path.Combine(directory.FullName, "from-phone.txt");
        File.WriteAllText(path, "hello from the phone");
        Assert.True(ReceivedText.TryRead(path, out var text));
        Assert.Equal("hello from the phone", text);
        Assert.False(ReceivedText.TryRead(Path.Combine(directory.FullName, "missing.txt"), out _));
        var png = Path.Combine(directory.FullName, "shot.png");
        File.WriteAllBytes(png, [1, 2, 3]);
        Assert.False(ReceivedText.TryRead(png, out _));
    }
}
