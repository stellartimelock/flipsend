namespace StellarSend.Core.Security;

public interface ILog
{
    void Info(string message);
    void Warn(string message);
    void Error(string message);
}

public sealed class NullLog : ILog
{
    public static readonly NullLog Instance = new();
    public void Info(string message) { }
    public void Warn(string message) { }
    public void Error(string message) { }
}

public sealed class RedactingFileLog : ILog
{
    private readonly string _path;
    private readonly object _gate = new();

    public RedactingFileLog(string path)
    {
        _path = path;
        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }
    }

    public void Info(string message) => Write("INFO", message);
    public void Warn(string message) => Write("WARN", message);
    public void Error(string message) => Write("ERROR", message);

    public static string Redact(string message)
    {
        if (string.IsNullOrEmpty(message))
        {
            return message;
        }

        var lower = message.ToLowerInvariant();
        if (lower.Contains("stellarsend://", StringComparison.Ordinal)
            || lower.Contains("\"pk\"", StringComparison.Ordinal)
            || lower.Contains("\"password\"", StringComparison.Ordinal)
            || lower.Contains("\"seed\"", StringComparison.Ordinal)
            || lower.Contains("\"mnemonic\"", StringComparison.Ordinal)
            || lower.Contains("private key", StringComparison.Ordinal)
            || lower.Contains("begin private", StringComparison.Ordinal)
            || lower.Contains("begin certificate", StringComparison.Ordinal)
            || lower.Contains("purchasetoken", StringComparison.Ordinal)
            || lower.Contains("private_key", StringComparison.Ordinal))
        {
            return "[redacted]";
        }

        return message;
    }

    private void Write(string level, string message)
    {
        var line = $"{DateTimeOffset.UtcNow:O} {level} {Redact(message)}{Environment.NewLine}";
        lock (_gate)
        {
            File.AppendAllText(_path, line);
        }
    }
}
