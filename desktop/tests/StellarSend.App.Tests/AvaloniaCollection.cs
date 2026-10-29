using Xunit;

namespace StellarSend.App.Tests;

public sealed class AvaloniaFixture
{
    public AvaloniaFixture() => HeadlessAvalonia.Ensure();
}

[CollectionDefinition("avalonia")]
public sealed class AvaloniaCollection : ICollectionFixture<AvaloniaFixture>
{
}
