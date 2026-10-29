namespace StellarSend.Core.Protocol;

public static class Validators
{
    public static void ValidateHello(HelloBody hello)
    {
        if (hello.V != ProtocolLimits.Version)
        {
            throw new ProtocolException("Unsupported hello version.");
        }

        if (!Guid.TryParse(hello.DeviceId, out _))
        {
            throw new ProtocolException("Invalid device id.");
        }

        if (string.IsNullOrWhiteSpace(hello.Name) || hello.Name.Length > ProtocolLimits.MaxDeviceNameChars)
        {
            throw new ProtocolException("Invalid device name.");
        }

        if (!PinFormat.IsWellFormed(hello.Fingerprint))
        {
            throw new ProtocolException("Invalid fingerprint.");
        }
    }

    public static void ValidateOffer(OfferBody offer)
    {
        if (!Guid.TryParse(offer.TransferId, out _))
        {
            throw new ProtocolException("Invalid transfer id.");
        }

        if (offer.Kind is not (Kinds.File or Kinds.Text))
        {
            throw new ProtocolException("Unsupported content kind.");
        }

        if (offer.Size < 0)
        {
            throw new ProtocolException("Invalid content length.");
        }

        var max = offer.Kind == Kinds.Text ? ProtocolLimits.MaxTextBytes : ProtocolLimits.MaxFileBytes;
        if (offer.Size > max)
        {
            throw new ProtocolException("Content exceeds the size limit.", ErrorCodes.Limit);
        }

        if (offer.Name.Length > ProtocolLimits.MaxNameChars)
        {
            throw new ProtocolException("Name is too long.", ErrorCodes.Limit);
        }

        if (offer.Mime.Length > ProtocolLimits.MaxMimeChars || offer.Mime.Any(char.IsControl))
        {
            throw new ProtocolException("Invalid content type.");
        }

        if (!PinFormat.IsWellFormed(offer.Sha256))
        {
            throw new ProtocolException("Invalid integrity hash.");
        }
    }

    public static string TrimName(string name)
    {
        var trimmed = name.Trim();
        if (trimmed.Length == 0)
        {
            return "Device";
        }

        var chars = trimmed.Where(c => !char.IsControl(c)).ToArray();
        var clean = new string(chars);
        if (clean.Length > ProtocolLimits.MaxDeviceNameChars)
        {
            clean = clean[..ProtocolLimits.MaxDeviceNameChars];
        }

        return clean.Length == 0 ? "Device" : clean;
    }
}

public static class PinFormat
{
    public static bool IsWellFormed(string? fingerprint)
    {
        if (fingerprint is null || fingerprint.Length != 64)
        {
            return false;
        }

        foreach (var c in fingerprint)
        {
            var hex = (c is >= '0' and <= '9') || (c is >= 'a' and <= 'f');
            if (!hex)
            {
                return false;
            }
        }

        return true;
    }
}
