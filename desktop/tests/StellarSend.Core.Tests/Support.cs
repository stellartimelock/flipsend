using System.Text.Json;
using StellarSend.Core.Companion;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;
using StellarSend.Core.Transfer;

namespace StellarSend.Core.Tests;

public static class Repo
{
    public static string Root()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (File.Exists(Path.Combine(dir.FullName, "testdata", "protocol-vectors.json")))
            {
                return dir.FullName;
            }

            dir = dir.Parent;
        }

        throw new DirectoryNotFoundException("testdata was not found.");
    }

    public static T ReadJson<T>(string name)
    {
        var json = File.ReadAllText(Path.Combine(Root(), "testdata", name));
        return JsonSerializer.Deserialize<T>(json, new JsonSerializerOptions { PropertyNameCaseInsensitive = true })
            ?? throw new InvalidOperationException(name);
    }
}

public sealed class TestProtector : IKeyProtector
{
    public byte[] Protect(byte[] plaintext)
    {
        var output = new byte[plaintext.Length];
        for (var i = 0; i < plaintext.Length; i++)
        {
            output[i] = (byte)(plaintext[i] ^ 0x5A);
        }

        return output;
    }

    public byte[] Unprotect(byte[] ciphertext) => Protect(ciphertext);
}

public sealed class ScriptedConsent : IConsent
{
    public bool AcceptPairing { get; set; } = true;
    public bool AcceptIncoming { get; set; } = true;
    public bool AcceptRemote { get; set; } = true;

    public Task<bool> AcceptPairingAsync(string remoteName, string fingerprint, CancellationToken ct) =>
        Task.FromResult(AcceptPairing);

    public Task<bool> AcceptIncomingAsync(OfferBody offer, string remoteName, CancellationToken ct) =>
        Task.FromResult(AcceptIncoming);

    public Task<RemoteDesktopConsent> AcceptRemoteControlAsync(string fromName, CancellationToken ct) =>
        Task.FromResult(new RemoteDesktopConsent(AcceptRemote, false));
}

[CollectionDefinition("net", DisableParallelization = true)]
public sealed class NetCollection;
