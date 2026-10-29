using System.Security.Cryptography;

namespace StellarSend.Core.Pairing;

/// <summary>
/// Replaces identity.bin and peer.bin together. Both payloads are flushed to temp
/// files and renamed. If the peer rename fails, the previous identity bytes are put back.
/// </summary>
public static class PairingCommit
{
    public static void Commit(
        IdentityStore identities,
        string identityPath,
        DeviceIdentity identity,
        PeerStore peers,
        string peerPath,
        PeerRecord peer)
    {
        var identityBytes = identities.Protect(identity);
        var peerBytes = peers.Protect(peer);
        try
        {
            CommitBytes(identityPath, identityBytes, peerPath, peerBytes);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(identityBytes);
            CryptographicOperations.ZeroMemory(peerBytes);
        }
    }

    public static void CommitBytes(string identityPath, byte[] identityBytes, string peerPath, byte[] peerBytes)
    {
        var previousIdentity = ReadOrNull(identityPath);
        var previousPeer = ReadOrNull(peerPath);
        var identityTemp = TempPath(identityPath);
        var peerTemp = TempPath(peerPath);
        try
        {
            WriteTemp(identityTemp, identityBytes);
            WriteTemp(peerTemp, peerBytes);
            MoveOver(identityTemp, identityPath);
            try
            {
                MoveOver(peerTemp, peerPath);
            }
            catch
            {
                Restore(identityPath, previousIdentity);
                Restore(peerPath, previousPeer);
                throw;
            }
        }
        finally
        {
            DeleteIfExists(identityTemp);
            DeleteIfExists(peerTemp);
        }
    }

    private static byte[]? ReadOrNull(string path)
    {
        if (!File.Exists(path) || Directory.Exists(path))
        {
            return null;
        }

        return File.ReadAllBytes(path);
    }

    private static string TempPath(string path) => path + ".pairing.tmp";

    private static void WriteTemp(string path, byte[] content)
    {
        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        using var stream = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None);
        stream.Write(content);
        stream.Flush(flushToDisk: true);
    }

    private static void MoveOver(string temp, string target)
    {
        File.Move(temp, target, overwrite: true);
    }

    private static void Restore(string path, byte[]? previous)
    {
        if (previous is null)
        {
            if (Directory.Exists(path))
            {
                return;
            }

            if (File.Exists(path))
            {
                File.Delete(path);
            }

            return;
        }

        var temp = TempPath(path);
        WriteTemp(temp, previous);
        MoveOver(temp, path);
    }

    private static void DeleteIfExists(string path)
    {
        if (File.Exists(path))
        {
            File.Delete(path);
        }
    }
}
