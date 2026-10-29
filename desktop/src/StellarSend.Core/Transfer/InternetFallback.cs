namespace StellarSend.Core.Transfer;

public static class InternetFallback
{
    public const bool Available = true;

    public const string Summary =
        "When direct Wi-Fi does not connect, both devices can dial an outbound wss relay on port 443. The relay forwards ciphertext only and does not see the files. Turn on the VPN's allow local network option for a faster direct transfer.";
}
