using System.Text;
using StellarSend.Core.Protocol;

namespace StellarSend.Core.Files;

/// <summary>
/// Notes and text-like files from the phone. The PC received view can show and copy these.
/// </summary>
public static class ReceivedText
{
    public static bool IsTextLike(string? pathOrName)
    {
        if (FileNames.IsFortressBotResponse(pathOrName))
        {
            return false;
        }

        var ext = Path.GetExtension(pathOrName ?? "").ToLowerInvariant();
        return ext is ".txt" or ".text" or ".md" or ".json" or ".csv" or ".log" or ".xml" or ".html" or ".htm";
    }

    public static bool TryRead(string? path, out string text)
    {
        text = "";
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path) || Directory.Exists(path) || !IsTextLike(path))
        {
            return false;
        }

        try
        {
            var info = new FileInfo(path);
            if (info.Length > ProtocolLimits.MaxTextBytes)
            {
                using var stream = File.OpenRead(path);
                using var reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: true);
                var buffer = new char[(int)ProtocolLimits.MaxTextBytes];
                var n = reader.Read(buffer, 0, buffer.Length);
                text = new string(buffer, 0, n);
                return true;
            }

            text = File.ReadAllText(path);
            return true;
        }
        catch (IOException)
        {
            return false;
        }
    }
}
