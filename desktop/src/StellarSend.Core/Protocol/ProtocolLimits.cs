namespace StellarSend.Core.Protocol;

public static class ProtocolLimits
{
    public const byte Version = 1;
    public const int HeaderSize = 12;
    public const int MaxFramePayload = 256 * 1024;
    public const int MaxChunkData = 64 * 1024;
    public const long MaxTextBytes = 1 * 1024 * 1024;
    public const long MaxFileBytes = 8L * 1024 * 1024 * 1024;
    public const int MaxNameChars = 240;
    public const int MaxDeviceNameChars = 64;
    public const int MaxMimeChars = 127;
    public const int DefaultTcpPort = 53321;
    public const int DiscoveryUdpPort = 53320;
    public const int MaxBeaconBytes = 512;
    public const string ServiceName = "_stellarsend._tcp.local";
    public const string QrScheme = "stellarsend://pair";
}
