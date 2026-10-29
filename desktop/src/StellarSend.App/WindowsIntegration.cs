using Microsoft.Win32;
using StellarSend.Core.Shell;
using System.Diagnostics;

namespace StellarSend.App;

public static class WindowsIntegration
{
    public static void RegisterShell() => ShellRegistration.Register(new WindowsRegistry(), ExePath());

    public static void UnregisterShell() => ShellRegistration.Unregister(new WindowsRegistry());

    public static void EnableStartup() => StartupRegistration.Enable(new WindowsRegistry(), ExePath());

    public static void DisableStartup() => StartupRegistration.Disable(new WindowsRegistry());

    public static bool StartupOn()
    {
        try
        {
            return StartupRegistration.IsEnabled(new WindowsRegistry());
        }
        catch (PlatformNotSupportedException)
        {
            return false;
        }
    }

    public static string ExePath()
    {
        var path = Environment.ProcessPath ?? AppContext.BaseDirectory;
        if (!path.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
        {
            path += ".exe";
        }

        return path;
    }

    public static string OpenCastToTv()
    {
        if (!OperatingSystem.IsWindows())
        {
            return StellarSend.Core.Companion.TvCast.OpenFailed("Cast to TV is on the Windows app.");
        }

        try
        {
            Process.Start(new ProcessStartInfo(StellarSend.Core.Companion.TvCast.ProjectUri) { UseShellExecute = true });
            return StellarSend.Core.Companion.TvCast.Started;
        }
        catch (Exception ex)
        {
            return StellarSend.Core.Companion.TvCast.OpenFailed(ex.Message);
        }
    }
}

public sealed class WindowsRegistry : IRegistry
{
    public void SetString(string subKey, string name, string value)
    {
        RequireWindows();
        using var key = Registry.CurrentUser.CreateSubKey(subKey, true);
        key?.SetValue(name.Length == 0 ? "" : name, value);
    }

    public bool TryGetString(string subKey, string name, out string? value)
    {
        value = null;
        if (!OperatingSystem.IsWindows())
        {
            return false;
        }

        using var key = Registry.CurrentUser.OpenSubKey(subKey, writable: false);
        value = key?.GetValue(name.Length == 0 ? "" : name) as string;
        return value is not null;
    }

    public void DeleteValue(string subKey, string name)
    {
        RequireWindows();
        using var key = Registry.CurrentUser.OpenSubKey(subKey, writable: true);
        key?.DeleteValue(name, throwOnMissingValue: false);
    }

    public void DeleteTree(string subKey)
    {
        RequireWindows();
        Registry.CurrentUser.DeleteSubKeyTree(subKey, throwOnMissingSubKey: false);
    }

    private static void RequireWindows()
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("Explorer and startup registration are available on Windows.");
        }
    }
}
