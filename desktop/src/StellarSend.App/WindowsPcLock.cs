using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using StellarSend.Core.Memo;

namespace StellarSend.App;

public sealed class WindowsPcLock : IPcLock
{
    public bool CanLock => OperatingSystem.IsWindows();

    public void Lock()
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new InvalidOperationException("Lock is not available.");
        }

        if (!LockWorkStation())
        {
            throw new InvalidOperationException("This PC could not be locked.");
        }
    }

    [SupportedOSPlatform("windows")]
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool LockWorkStation();
}
