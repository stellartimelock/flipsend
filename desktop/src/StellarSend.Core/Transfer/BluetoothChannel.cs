using System.Runtime.InteropServices;

namespace StellarSend.Core.Transfer;

/// <summary>
/// Windows RFCOMM. The stream is handed to the existing TLS handshake.
/// On any other system this stays quiet and the send uses the relay.
/// </summary>
public static class BluetoothChannel
{
    public static bool RadioOn()
    {
        if (!OperatingSystem.IsWindows())
        {
            return false;
        }

        try
        {
            Winsock.Ensure();
            return BluetoothRadio.HasRadio();
        }
        catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException or IOException)
        {
            return false;
        }
    }

    public static BluetoothStream? Dial(string? savedMac, CancellationToken ct)
    {
        if (!OperatingSystem.IsWindows() || ct.IsCancellationRequested)
        {
            return null;
        }

        try
        {
            Winsock.Ensure();
            var deadline = DateTime.UtcNow.AddMilliseconds(BluetoothService.AttemptMs);
            var targets = new List<ulong>();
            if (BluetoothService.IsMac(savedMac))
            {
                targets.Add(BluetoothService.ToAddress(savedMac!));
            }

            foreach (var device in BluetoothRadio.Devices(inquiry: targets.Count == 0))
            {
                if (!targets.Contains(device))
                {
                    targets.Add(device);
                }

                if (targets.Count >= 4)
                {
                    break;
                }
            }

            foreach (var address in targets)
            {
                ct.ThrowIfCancellationRequested();
                var left = (int)(deadline - DateTime.UtcNow).TotalMilliseconds;
                if (left < 400)
                {
                    return null;
                }

                var stream = Connect(address, BluetoothService.Uuid, 0, Math.Min(left, 2500));
                stream ??= Connect(address, Guid.Empty, BluetoothService.Channel, Math.Min(left, 1200));
                if (stream is not null)
                {
                    return stream;
                }
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception)
        {
            return null;
        }

        return null;
    }

    public static IDisposable Listen(Action<BluetoothStream> onClient, CancellationToken ct)
    {
        if (!OperatingSystem.IsWindows())
        {
            return Noop.Instance;
        }

        var gate = new ListenGate();
        var thread = new Thread(() => ListenLoop(onClient, gate, ct))
        {
            IsBackground = true,
            Name = "flipsend-bluetooth"
        };
        thread.Start();
        return gate;
    }

    private static void ListenLoop(Action<BluetoothStream> onClient, ListenGate gate, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && !gate.Stopped)
        {
            IntPtr listener = new(-1);
            try
            {
                Winsock.Ensure();
                if (!BluetoothRadio.HasRadio())
                {
                    if (!Sleep(ct, 1500)) return;
                    continue;
                }

                listener = Winsock.socket(Winsock.AfBth, Winsock.SockStream, Winsock.Rfcomm);
                if (listener == Winsock.Invalid)
                {
                    if (!Sleep(ct, 1500)) return;
                    continue;
                }

                var address = new SockAddrBth
                {
                    addressFamily = Winsock.AfBth,
                    serviceClassId = BluetoothService.Uuid,
                    port = BluetoothService.Channel
                };
                var size = Marshal.SizeOf<SockAddrBth>();
                if (Winsock.bind(listener, ref address, size) != 0)
                {
                    address.port = Winsock.PortAny;
                    if (Winsock.bind(listener, ref address, size) != 0)
                    {
                        Winsock.closesocket(listener);
                        listener = Winsock.Invalid;
                        if (!Sleep(ct, 1500)) return;
                        continue;
                    }
                }

                var named = size;
                if (Winsock.getsockname(listener, ref address, ref named) == 0)
                {
                    Sdp.Publish(BluetoothService.Name, BluetoothService.Uuid, address.port);
                }

                if (Winsock.listen(listener, 4) != 0)
                {
                    Winsock.closesocket(listener);
                    listener = Winsock.Invalid;
                    if (!Sleep(ct, 1500)) return;
                    continue;
                }

                gate.Socket = listener;
                while (!ct.IsCancellationRequested && !gate.Stopped)
                {
                    var remote = new SockAddrBth();
                    var remoteSize = Marshal.SizeOf<SockAddrBth>();
                    var accepted = Winsock.accept(listener, ref remote, ref remoteSize);
                    if (accepted == Winsock.Invalid)
                    {
                        break;
                    }

                    onClient(new BluetoothStream(accepted, remote.btAddr));
                }
            }
            catch (Exception)
            {
                if (ct.IsCancellationRequested || gate.Stopped)
                {
                    return;
                }
            }
            finally
            {
                if (listener != Winsock.Invalid)
                {
                    Winsock.closesocket(listener);
                }

                gate.Socket = Winsock.Invalid;
            }

            if (!Sleep(ct, 400))
            {
                return;
            }
        }
    }

    private static BluetoothStream? Connect(ulong address, Guid service, uint port, int timeoutMs)
    {
        var socket = Winsock.socket(Winsock.AfBth, Winsock.SockStream, Winsock.Rfcomm);
        if (socket == Winsock.Invalid)
        {
            return null;
        }

        var addr = new SockAddrBth
        {
            addressFamily = Winsock.AfBth,
            btAddr = address,
            serviceClassId = service,
            port = port
        };
        var done = 0;
        var worker = new Thread(() =>
        {
            var result = Winsock.connect(socket, ref addr, Marshal.SizeOf<SockAddrBth>());
            Interlocked.Exchange(ref done, result == 0 ? 1 : -1);
        })
        {
            IsBackground = true,
            Name = "flipsend-bt-dial"
        };
        worker.Start();
        worker.Join(Math.Max(timeoutMs, 1));
        if (Volatile.Read(ref done) != 1)
        {
            Winsock.closesocket(socket);
            return null;
        }

        return new BluetoothStream(socket, address);
    }

    private static bool Sleep(CancellationToken ct, int ms)
    {
        try
        {
            Task.Delay(ms, ct).GetAwaiter().GetResult();
            return !ct.IsCancellationRequested;
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    private sealed class ListenGate : IDisposable
    {
        public volatile bool Stopped;
        public IntPtr Socket = Winsock.Invalid;

        public void Dispose()
        {
            Stopped = true;
            var socket = Socket;
            if (socket != Winsock.Invalid)
            {
                Winsock.closesocket(socket);
            }
        }
    }

    private sealed class Noop : IDisposable
    {
        public static readonly Noop Instance = new();
        public void Dispose()
        {
        }
    }
}

public sealed class BluetoothStream : Stream
{
    private IntPtr _socket;
    private int _closed;

    internal BluetoothStream(IntPtr socket, ulong remoteAddress)
    {
        _socket = socket;
        RemoteAddress = remoteAddress;
    }

    public ulong RemoteAddress { get; }
    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => true;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override void Flush()
    {
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        var read = offset == 0
            ? Winsock.recv(_socket, buffer, count, 0)
            : ReadSlice(buffer, offset, count);
        if (read == 0)
        {
            return 0;
        }

        if (read < 0)
        {
            throw new IOException("Bluetooth read failed.");
        }

        return read;
    }

    public override void Write(byte[] buffer, int offset, int count)
    {
        var sent = 0;
        while (sent < count)
        {
            var slice = count - sent;
            int wrote;
            if (offset + sent == 0)
            {
                wrote = Winsock.send(_socket, buffer, slice, 0);
            }
            else
            {
                var temp = new byte[slice];
                Buffer.BlockCopy(buffer, offset + sent, temp, 0, slice);
                wrote = Winsock.send(_socket, temp, slice, 0);
            }

            if (wrote <= 0)
            {
                throw new IOException("Bluetooth write failed.");
            }

            sent += wrote;
        }
    }

    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (Interlocked.Exchange(ref _closed, 1) == 0 && _socket != Winsock.Invalid)
        {
            Winsock.closesocket(_socket);
            _socket = Winsock.Invalid;
        }

        base.Dispose(disposing);
    }

    private int ReadSlice(byte[] buffer, int offset, int count)
    {
        var temp = new byte[count];
        var read = Winsock.recv(_socket, temp, count, 0);
        if (read > 0)
        {
            Buffer.BlockCopy(temp, 0, buffer, offset, read);
        }

        return read;
    }
}

public static class BluetoothBook
{
    public static string? Load(string directory)
    {
        try
        {
            var path = Path.Combine(directory, "bluetooth-peer.txt");
            if (!File.Exists(path))
            {
                return null;
            }

            var text = File.ReadAllText(path).Trim();
            return BluetoothService.IsMac(text) ? text : null;
        }
        catch (IOException)
        {
            return null;
        }
    }

    public static void Save(string directory, ulong address)
    {
        if (address == 0)
        {
            return;
        }

        try
        {
            Directory.CreateDirectory(directory);
            File.WriteAllText(Path.Combine(directory, "bluetooth-peer.txt"), BluetoothService.Format(address));
        }
        catch (IOException)
        {
        }
    }
}

[StructLayout(LayoutKind.Sequential, Pack = 1)]
internal struct SockAddrBth
{
    public ushort addressFamily;
    public ulong btAddr;
    public Guid serviceClassId;
    public uint port;
}

internal static class Winsock
{
    public const int AfBth = 32;
    public const int SockStream = 1;
    public const int Rfcomm = 3;
    public const uint PortAny = 0xFFFFFFFF;
    public static readonly IntPtr Invalid = new(-1);
    private static int _started;

    public static void Ensure()
    {
        if (Interlocked.Exchange(ref _started, 1) == 1)
        {
            return;
        }

        var data = Marshal.AllocHGlobal(512);
        try
        {
            if (WSAStartup(0x0202, data) != 0)
            {
                throw new IOException("Bluetooth sockets did not start.");
            }
        }
        finally
        {
            Marshal.FreeHGlobal(data);
        }
    }

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern int WSAStartup(ushort version, IntPtr data);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern IntPtr socket(int af, int type, int protocol);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern int bind(IntPtr socket, ref SockAddrBth name, int nameLength);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern int connect(IntPtr socket, ref SockAddrBth name, int nameLength);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern int listen(IntPtr socket, int backlog);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern IntPtr accept(IntPtr socket, ref SockAddrBth addr, ref int addrLength);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern int getsockname(IntPtr socket, ref SockAddrBth name, ref int nameLength);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern int closesocket(IntPtr socket);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern int recv(IntPtr socket, byte[] buffer, int length, int flags);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern int send(IntPtr socket, byte[] buffer, int length, int flags);
}

internal static class Sdp
{
    public static void Publish(string name, Guid uuid, uint port)
    {
        var address = new SockAddrBth
        {
            addressFamily = Winsock.AfBth,
            serviceClassId = uuid,
            port = port
        };
        var addressSize = Marshal.SizeOf<SockAddrBth>();
        var addressPtr = Marshal.AllocHGlobal(addressSize);
        var infoPtr = IntPtr.Zero;
        var guidPtr = IntPtr.Zero;
        var namePtr = IntPtr.Zero;
        try
        {
            Marshal.StructureToPtr(address, addressPtr, false);
            var info = new CsAddrInfo
            {
                LocalAddr = new SocketAddressNative { lpSockaddr = addressPtr, iSockaddrLength = addressSize },
                RemoteAddr = new SocketAddressNative { lpSockaddr = addressPtr, iSockaddrLength = addressSize },
                iSocketType = Winsock.SockStream,
                iProtocol = Winsock.Rfcomm
            };
            infoPtr = Marshal.AllocHGlobal(Marshal.SizeOf<CsAddrInfo>());
            Marshal.StructureToPtr(info, infoPtr, false);
            guidPtr = Marshal.AllocHGlobal(Marshal.SizeOf<Guid>());
            Marshal.StructureToPtr(uuid, guidPtr, false);
            namePtr = Marshal.StringToHGlobalUni(name);
            var query = new WsaQuerySet
            {
                dwSize = Marshal.SizeOf<WsaQuerySet>(),
                lpszServiceInstanceName = namePtr,
                lpServiceClassId = guidPtr,
                dwNameSpace = 16,
                dwNumberOfCsAddrs = 1,
                lpcsaBuffer = infoPtr
            };
            _ = WSASetService(ref query, 0, 0);
        }
        catch (Exception)
        {
        }
        finally
        {
            if (namePtr != IntPtr.Zero) Marshal.FreeHGlobal(namePtr);
            if (guidPtr != IntPtr.Zero) Marshal.FreeHGlobal(guidPtr);
            if (infoPtr != IntPtr.Zero) Marshal.FreeHGlobal(infoPtr);
            Marshal.FreeHGlobal(addressPtr);
        }
    }

    [DllImport("ws2_32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern int WSASetService(ref WsaQuerySet query, int operation, int controlFlags);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WsaQuerySet
    {
        public int dwSize;
        public IntPtr lpszServiceInstanceName;
        public IntPtr lpServiceClassId;
        public IntPtr lpVersion;
        public IntPtr lpszComment;
        public int dwNameSpace;
        public IntPtr lpNSProviderId;
        public IntPtr lpszContext;
        public int dwNumberOfProtocols;
        public IntPtr lpafpProtocols;
        public IntPtr lpszQueryString;
        public int dwNumberOfCsAddrs;
        public IntPtr lpcsaBuffer;
        public int dwOutputFlags;
        public IntPtr lpBlob;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SocketAddressNative
    {
        public IntPtr lpSockaddr;
        public int iSockaddrLength;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CsAddrInfo
    {
        public SocketAddressNative LocalAddr;
        public SocketAddressNative RemoteAddr;
        public int iSocketType;
        public int iProtocol;
    }
}

internal static class BluetoothRadio
{
    public static bool HasRadio()
    {
        var parameters = new FindRadioParams { dwSize = Marshal.SizeOf<FindRadioParams>() };
        var find = BluetoothFindFirstRadio(ref parameters, out var radio);
        if (find == IntPtr.Zero || radio == IntPtr.Zero)
        {
            return false;
        }

        CloseHandle(radio);
        BluetoothFindRadioClose(find);
        return true;
    }

    public static List<ulong> Devices(bool inquiry)
    {
        var found = new List<ulong>();
        try
        {
            var parameters = new FindRadioParams { dwSize = Marshal.SizeOf<FindRadioParams>() };
            var radios = BluetoothFindFirstRadio(ref parameters, out var radio);
            if (radios == IntPtr.Zero || radio == IntPtr.Zero)
            {
                return found;
            }

            try
            {
                var search = new DeviceSearchParams
                {
                    dwSize = Marshal.SizeOf<DeviceSearchParams>(),
                    fReturnAuthenticated = 1,
                    fReturnRemembered = 1,
                    fReturnUnknown = inquiry ? 1 : 0,
                    fReturnConnected = 1,
                    fIssueInquiry = inquiry ? 1 : 0,
                    cTimeoutMultiplier = (byte)(inquiry ? 1 : 0),
                    hRadio = radio
                };
                var info = NewDevice();
                var devices = BluetoothFindFirstDevice(ref search, ref info);
                if (devices == IntPtr.Zero)
                {
                    return found;
                }

                try
                {
                    Add(found, info.Address);
                    while (found.Count < 4 && BluetoothFindNextDevice(devices, ref info))
                    {
                        Add(found, info.Address);
                    }
                }
                finally
                {
                    BluetoothFindDeviceClose(devices);
                }
            }
            finally
            {
                CloseHandle(radio);
                BluetoothFindRadioClose(radios);
            }
        }
        catch (Exception)
        {
        }

        return found;
    }

    private static DeviceInfo NewDevice()
    {
        var info = new DeviceInfo();
        info.dwSize = Marshal.SizeOf<DeviceInfo>();
        return info;
    }

    private static void Add(List<ulong> found, ulong address)
    {
        if (address != 0 && !found.Contains(address))
        {
            found.Add(address);
        }
    }

    [DllImport("bthprops.cpl", SetLastError = true)]
    private static extern IntPtr BluetoothFindFirstRadio(ref FindRadioParams parameters, out IntPtr radio);

    [DllImport("bthprops.cpl", SetLastError = true)]
    private static extern bool BluetoothFindRadioClose(IntPtr find);

    [DllImport("bthprops.cpl", SetLastError = true)]
    private static extern IntPtr BluetoothFindFirstDevice(ref DeviceSearchParams parameters, ref DeviceInfo info);

    [DllImport("bthprops.cpl", SetLastError = true)]
    private static extern bool BluetoothFindNextDevice(IntPtr find, ref DeviceInfo info);

    [DllImport("bthprops.cpl", SetLastError = true)]
    private static extern bool BluetoothFindDeviceClose(IntPtr find);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);

    [StructLayout(LayoutKind.Sequential)]
    private struct FindRadioParams
    {
        public int dwSize;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DeviceSearchParams
    {
        public int dwSize;
        public int fReturnAuthenticated;
        public int fReturnRemembered;
        public int fReturnUnknown;
        public int fReturnConnected;
        public int fIssueInquiry;
        public byte cTimeoutMultiplier;
        public IntPtr hRadio;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DeviceInfo
    {
        public int dwSize;
        public ulong Address;
        public uint ClassOfDevice;
        public int Connected;
        public int Remembered;
        public int Authenticated;
        public long LastSeen;
        public long LastSeenHigh;
        public long LastUsed;
        public long LastUsedHigh;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)]
        public string Name;
    }
}
