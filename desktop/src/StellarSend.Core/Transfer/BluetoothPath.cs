namespace StellarSend.Core.Transfer;

/// <summary>
/// File sends try Wi-Fi, then Bluetooth RFCOMM, then the relay.
/// The RFCOMM socket carries the same paired TLS session.
/// </summary>
public static class BluetoothService
{
    public const string Name = "FlipSend";
    public const string UuidText = "f17b5e1d-4c0a-4e7a-9b21-a35f0000b101";
    public static readonly Guid Uuid = Guid.Parse(UuidText);
    public const int Channel = 25;
    public const int AttemptMs = 4000;

    public static bool IsMac(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return false;
        }

        var text = value.Trim();
        if (text.Equals("02:00:00:00:00:00", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        var parts = text.Split(':');
        if (parts.Length != 6)
        {
            return false;
        }

        foreach (var part in parts)
        {
            if (part.Length != 2 || !byte.TryParse(part, System.Globalization.NumberStyles.HexNumber, null, out _))
            {
                return false;
            }
        }

        return true;
    }

    public static ulong ToAddress(string mac)
    {
        var parts = mac.Split(':');
        ulong value = 0;
        for (var i = 0; i < 6; i++)
        {
            value |= (ulong)Convert.ToByte(parts[i], 16) << (8 * i);
        }

        return value;
    }

    public static string Format(ulong address)
    {
        var bytes = new byte[6];
        for (var i = 0; i < 6; i++)
        {
            bytes[i] = (byte)((address >> (8 * i)) & 0xFF);
        }

        return string.Join(":", bytes.Select(part => part.ToString("X2")));
    }
}

public static class SendRoute
{
    public const string Wifi = "wifi";
    public const string Bluetooth = "bluetooth";
    public const string Relay = "relay";
    public static readonly string[] Order = [Wifi, Bluetooth, Relay];

    public static string Choose(bool lan, bool bluetooth, bool forceRelay)
    {
        if (forceRelay)
        {
            return Relay;
        }

        if (lan)
        {
            return Wifi;
        }

        if (bluetooth)
        {
            return Bluetooth;
        }

        return Relay;
    }
}
