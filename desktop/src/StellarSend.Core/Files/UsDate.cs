using System.Globalization;

namespace StellarSend.Core.Files;

public static class UsDate
{
    public static string Format(DateTimeOffset value) =>
        value.ToLocalTime().ToString("MM/dd/yyyy", CultureInfo.InvariantCulture);
}
