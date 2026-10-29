namespace StellarSend.Core.Transfer;

public enum TransferState
{
    AwaitingConsent,
    InProgress,
    Succeeded,
    Failed,
    Cancelled
}

public sealed record TransferProgress(
    Guid TransferId,
    string Name,
    TransferState State,
    long BytesTransferred,
    long TotalBytes,
    double? BytesPerSecond,
    double? SecondsRemaining,
    string? Detail);

public sealed record TransferResult(
    TransferState State,
    string? Code,
    string? Message,
    string? SavedPath,
    string? Text,
    int Chunks,
    bool PairingOnly);

public sealed class OutgoingPayload : IAsyncDisposable
{
    public required string Kind { get; init; }
    public required string Name { get; init; }
    public required string Mime { get; init; }
    public required long Size { get; init; }
    public required string Sha256 { get; init; }
    public required Stream Content { get; init; }
    public int BatchIndex { get; set; }
    public int BatchCount { get; set; }

    public static async Task<OutgoingPayload> FromFileAsync(string path, CancellationToken ct)
    {
        var info = new FileInfo(path);
        if (!info.Exists)
        {
            throw new FileNotFoundException("File not found.", path);
        }

        if (info.Length > Protocol.ProtocolLimits.MaxFileBytes)
        {
            throw new Protocol.ProtocolException("File exceeds the size limit.", Protocol.ErrorCodes.Limit);
        }

        var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 65536, FileOptions.Asynchronous | FileOptions.SequentialScan);
        var hash = await Hashing.HashAsync(stream, ct).ConfigureAwait(false);
        stream.Position = 0;
        return new OutgoingPayload
        {
            Kind = Protocol.Kinds.File,
            Name = Files.FileNames.Sanitize(info.Name),
            Mime = MimeGuess.ForFileName(info.Name),
            Size = info.Length,
            Sha256 = hash,
            Content = stream
        };
    }

    public static async Task<OutgoingPayload> FromFolderAsync(string directory, CancellationToken ct)
    {
        if (!Directory.Exists(directory))
        {
            throw new DirectoryNotFoundException("Folder not found.");
        }

        var safe = Files.FileNames.Sanitize(new DirectoryInfo(directory).Name);
        var archiveName = safe.EndsWith(Files.FolderArchive.Suffix, StringComparison.OrdinalIgnoreCase)
            ? safe
            : TrimName(safe, Files.FolderArchive.Suffix);
        var temp = Path.Combine(Path.GetTempPath(), "stellarsend-" + Guid.NewGuid().ToString("N") + ".zip");
        Files.FolderArchive.Pack(directory, temp);
        var info = new FileInfo(temp);
        if (info.Length > Protocol.ProtocolLimits.MaxFileBytes)
        {
            File.Delete(temp);
            throw new Protocol.ProtocolException("Folder exceeds the size limit.", Protocol.ErrorCodes.Limit);
        }

        var stream = new FileStream(temp, FileMode.Open, FileAccess.Read, FileShare.Read, 65536, FileOptions.Asynchronous | FileOptions.DeleteOnClose);
        var hash = await Hashing.HashAsync(stream, ct).ConfigureAwait(false);
        stream.Position = 0;
        return new OutgoingPayload
        {
            Kind = Protocol.Kinds.File,
            Name = archiveName,
            Mime = Files.FolderArchive.Mime,
            Size = info.Length,
            Sha256 = hash,
            Content = stream
        };
    }

    private static string TrimName(string stem, string suffix)
    {
        var room = Protocol.ProtocolLimits.MaxNameChars - suffix.Length;
        if (room < 1)
        {
            return suffix.TrimStart('.');
        }

        if (stem.Length > room)
        {
            stem = stem[..room];
        }

        return stem + suffix;
    }

    public static OutgoingPayload FromText(string text)
    {
        if (string.IsNullOrEmpty(text))
        {
            throw new ArgumentException("Enter text first.", nameof(text));
        }

        var bytes = System.Text.Encoding.UTF8.GetBytes(text);
        if (bytes.Length > Protocol.ProtocolLimits.MaxTextBytes)
        {
            throw new Protocol.ProtocolException("Text exceeds the size limit.", Protocol.ErrorCodes.Limit);
        }

        var stream = new MemoryStream(bytes, writable: false);
        var hash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(bytes)).ToLowerInvariant();
        return new OutgoingPayload
        {
            Kind = Protocol.Kinds.Text,
            Name = "message.txt",
            Mime = "text/plain",
            Size = bytes.Length,
            Sha256 = hash,
            Content = stream
        };
    }

    public ValueTask DisposeAsync()
    {
        Content.Dispose();
        return ValueTask.CompletedTask;
    }
}

public static class Hashing
{
    public static async Task<string> HashAsync(Stream stream, CancellationToken ct)
    {
        using var hasher = System.Security.Cryptography.IncrementalHash.CreateHash(System.Security.Cryptography.HashAlgorithmName.SHA256);
        var buffer = new byte[81920];
        while (true)
        {
            var read = await stream.ReadAsync(buffer, ct).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }

            hasher.AppendData(buffer.AsSpan(0, read));
        }

        return Convert.ToHexString(hasher.GetHashAndReset()).ToLowerInvariant();
    }
}

public static class MimeGuess
{
    public static string ForFileName(string name)
    {
        var ext = Path.GetExtension(name).ToLowerInvariant();
        return ext switch
        {
            ".txt" or ".md" => "text/plain",
            ".json" => "application/json",
            ".png" => "image/png",
            ".jpg" or ".jpeg" => "image/jpeg",
            ".pdf" => "application/pdf",
            ".zip" => name.EndsWith(Files.FolderArchive.Suffix, StringComparison.OrdinalIgnoreCase)
                ? Files.FolderArchive.Mime
                : "application/zip",
            ".apk" => "application/vnd.android.package-archive",
            _ => "application/octet-stream"
        };
    }
}

public static class RetryPolicy
{
    public const int MaxAttempts = 5;

    public static bool ShouldRetry(bool remoteCompleted, bool cancelled, int attempt, bool transientFailure) =>
        !remoteCompleted && !cancelled && attempt < MaxAttempts && transientFailure;

    public static TimeSpan Delay(int failedAttempt)
    {
        var shift = Math.Clamp(failedAttempt - 1, 0, 4);
        return TimeSpan.FromMilliseconds(400L << shift);
    }
}
