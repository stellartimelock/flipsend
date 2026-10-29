using System.Security.Cryptography;
using System.Text.Json;

namespace StellarSend.Core.Transfer;

public interface IReceiveSink : IAsyncDisposable
{
    long StartOffset => 0;

    Task WriteAsync(ReadOnlyMemory<byte> data, CancellationToken ct);

    Task HashExistingAsync(IncrementalHash hasher, CancellationToken ct) => Task.CompletedTask;

    Task<string?> CommitAsync(CancellationToken ct);

    Task DiscardAsync();

    Task PreserveAsync() => DiscardAsync();

    Task CheckpointAsync(CancellationToken ct) => Task.CompletedTask;
}

public sealed class FileReceiveSink : IReceiveSink
{
    private readonly FileStream _stream;
    private readonly string _tempPath;
    private readonly string _finalPath;
    private readonly string? _sha256;
    private readonly string? _transferId;
    private readonly long _total;
    private readonly string _metaPath;
    private readonly bool _overwrite;
    private bool _closed;

    private FileReceiveSink(FileStream stream, string tempPath, string finalPath, string? sha256, string? transferId, long total, long startOffset, bool overwrite)
    {
        _stream = stream;
        _tempPath = tempPath;
        _finalPath = finalPath;
        _sha256 = sha256;
        _transferId = transferId;
        _total = total;
        _metaPath = tempPath + ".meta";
        _overwrite = overwrite;
        StartOffset = startOffset;
    }

    public long StartOffset { get; }

    public static FileReceiveSink Create(string directory, string unsafeName) =>
        Create(directory, unsafeName, null, 0);

    public static FileReceiveSink Create(string directory, string unsafeName, string? sha256, long total, string? transferId = null)
    {
        Directory.CreateDirectory(directory);
        var overwrite = Files.FileNames.IsFortressBotResponse(unsafeName);
        var finalPath = overwrite
            ? Files.FileNames.Exact(directory, unsafeName)
            : Files.FileNames.Allocate(directory, unsafeName);
        var legacy = finalPath + ".partial";
        string? matched = null;
        if (!string.IsNullOrEmpty(transferId))
        {
            var byId = Path.Combine(directory, transferId + ".partial");
            if (Matches(byId, sha256, total, transferId))
            {
                matched = byId;
            }
        }

        if (matched is null && Matches(legacy, sha256, total, transferId))
        {
            matched = legacy;
        }

        var tempPath = matched
            ?? (!string.IsNullOrEmpty(transferId) ? Path.Combine(directory, transferId + ".partial") : legacy);
        var metaPath = tempPath + ".meta";
        var start = matched is null ? 0 : new FileInfo(matched).Length;
        FileStream stream;
        if (start > 0)
        {
            stream = new FileStream(tempPath, FileMode.Open, FileAccess.Write, FileShare.Read, 65536, FileOptions.Asynchronous);
            stream.Seek(start, SeekOrigin.Begin);
        }
        else
        {
            if (File.Exists(tempPath))
            {
                File.Delete(tempPath);
            }

            if (File.Exists(metaPath))
            {
                File.Delete(metaPath);
            }

            stream = new FileStream(tempPath, FileMode.CreateNew, FileAccess.Write, FileShare.Read, 65536, FileOptions.Asynchronous | FileOptions.SequentialScan);
        }

        return new FileReceiveSink(stream, tempPath, finalPath, sha256, transferId, total, start, overwrite);
    }

    private static bool Matches(string tempPath, string? sha256, long total, string? transferId)
    {
        var metaPath = tempPath + ".meta";
        if (string.IsNullOrEmpty(sha256) || total <= 0 || !File.Exists(tempPath) || !File.Exists(metaPath))
        {
            return false;
        }

        try
        {
            var meta = JsonSerializer.Deserialize<ResumeMeta>(File.ReadAllBytes(metaPath));
            var length = new FileInfo(tempPath).Length;
            if (meta is null
                || !string.Equals(meta.Sha256, sha256, StringComparison.Ordinal)
                || meta.Length <= 0
                || meta.Length >= total
                || meta.Length != length)
            {
                return false;
            }

            if (!string.IsNullOrEmpty(meta.TransferId)
                && !string.Equals(meta.TransferId, transferId, StringComparison.OrdinalIgnoreCase))
            {
                return false;
            }

            if (meta.Size > 0 && meta.Size != total)
            {
                return false;
            }

            return true;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    public Task WriteAsync(ReadOnlyMemory<byte> data, CancellationToken ct) =>
        _stream.WriteAsync(data, ct).AsTask();

    public async Task CheckpointAsync(CancellationToken ct)
    {
        if (_closed || string.IsNullOrEmpty(_sha256))
        {
            return;
        }

        await _stream.FlushAsync(ct).ConfigureAwait(false);
        var length = _stream.Length;
        if (length <= 0 || length >= _total)
        {
            return;
        }

        var meta = JsonSerializer.SerializeToUtf8Bytes(new ResumeMeta(_sha256, length, _transferId, _total));
        await File.WriteAllBytesAsync(_metaPath, meta, ct).ConfigureAwait(false);
    }

    public async Task HashExistingAsync(IncrementalHash hasher, CancellationToken ct)
    {
        if (StartOffset <= 0)
        {
            return;
        }

        await using var read = new FileStream(_tempPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 65536, FileOptions.Asynchronous | FileOptions.SequentialScan);
        var remaining = StartOffset;
        var buffer = new byte[81920];
        while (remaining > 0)
        {
            var n = await read.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, remaining)), ct).ConfigureAwait(false);
            if (n == 0)
            {
                throw new IOException("The saved partial file is shorter than its resume marker.");
            }

            hasher.AppendData(buffer.AsSpan(0, n));
            remaining -= n;
        }
    }

    public async Task<string?> CommitAsync(CancellationToken ct)
    {
        await _stream.FlushAsync(ct).ConfigureAwait(false);
        _stream.Dispose();
        _closed = true;
        if (File.Exists(_metaPath))
        {
            File.Delete(_metaPath);
        }

        File.Move(_tempPath, _finalPath, overwrite: _overwrite);
        return _finalPath;
    }

    public Task DiscardAsync()
    {
        CloseStream();
        if (File.Exists(_tempPath))
        {
            File.Delete(_tempPath);
        }

        if (File.Exists(_metaPath))
        {
            File.Delete(_metaPath);
        }

        return Task.CompletedTask;
    }

    public async Task PreserveAsync()
    {
        long length = 0;
        if (!_closed)
        {
            await _stream.FlushAsync().ConfigureAwait(false);
            length = _stream.Length;
            _stream.Dispose();
            _closed = true;
        }
        else if (File.Exists(_tempPath))
        {
            length = new FileInfo(_tempPath).Length;
        }

        if (length <= 0 || string.IsNullOrEmpty(_sha256))
        {
            if (File.Exists(_tempPath))
            {
                File.Delete(_tempPath);
            }

            if (File.Exists(_metaPath))
            {
                File.Delete(_metaPath);
            }

            return;
        }

        var meta = JsonSerializer.SerializeToUtf8Bytes(new ResumeMeta(_sha256, length, _transferId, _total));
        await File.WriteAllBytesAsync(_metaPath, meta).ConfigureAwait(false);
    }

    public ValueTask DisposeAsync()
    {
        CloseStream();
        return ValueTask.CompletedTask;
    }

    private void CloseStream()
    {
        if (!_closed)
        {
            _stream.Dispose();
            _closed = true;
        }
    }

    private sealed record ResumeMeta(string Sha256, long Length, string? TransferId = null, long Size = 0);
}

public sealed class TextReceiveSink : IReceiveSink
{
    private readonly MemoryStream _stream = new();

    public Task WriteAsync(ReadOnlyMemory<byte> data, CancellationToken ct)
    {
        _stream.Write(data.Span);
        return Task.CompletedTask;
    }

    public Task<string?> CommitAsync(CancellationToken ct)
    {
        var text = new System.Text.UTF8Encoding(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true)
            .GetString(_stream.ToArray());
        return Task.FromResult<string?>(text);
    }

    public Task DiscardAsync()
    {
        _stream.Dispose();
        return Task.CompletedTask;
    }

    public ValueTask DisposeAsync()
    {
        _stream.Dispose();
        return ValueTask.CompletedTask;
    }
}
