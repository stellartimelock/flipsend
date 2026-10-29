using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using Avalonia.VisualTree;
using StellarSend.App;
using Xunit;

namespace StellarSend.App.Tests;

[Collection("avalonia")]
public sealed class DesignCWindowTests
{
    private static readonly object Gate = new();

    [Theory]
    [InlineData(480, 720)]
    [InlineData(465, 860)]
    public void SendReceiveAndRecentTransfersFitWithoutScrolling(int width, int height)
    {
        lock (Gate)
        {
            var window = ShowHome(width, height, TwoFinished());
            var layout = MeasureHome(window, height);
            Assert.True(layout.SendTop < layout.ReceiveTop, "Send should sit above Receive.");
            Assert.True(layout.ReceiveTop < layout.TransfersTop, "Receive should sit above recent transfers.");
            Assert.True(layout.TransfersBottom <= height, "Recent transfers must fit in the window without scrolling.");
            AssertHomeControlsVisible(layout, height);
            var gold = layout.Send.Background as Avalonia.Media.ISolidColorBrush;
            Assert.NotNull(gold);
            Assert.Equal(0xF5, gold!.Color.R);
            Assert.Equal(0xC8, gold.Color.G);
            Assert.Equal(0x42, gold.Color.B);
            Assert.Contains(window.GetVisualDescendants().OfType<TextBlock>(), block => block.Text == "Receive");
            Assert.Contains(window.GetVisualDescendants().OfType<TextBlock>(), block => block.Text == "Remote desktop");

            var frame = window.CaptureRenderedFrame();
            Assert.NotNull(frame);
            Assert.Equal(width, frame!.PixelSize.Width);
            Assert.Equal(height, frame.PixelSize.Height);
            SaveSnapshot(frame, $"home-{width}x{height}.png");
            window.Close();
        }
    }

    [Theory]
    [InlineData(1440, 753)]
    [InlineData(1936, 1048)]
    public void PairedHomeShowsSendTextAndRemoteDesktopWithoutPageScroll(int width, int height)
    {
        lock (Gate)
        {
            var window = ShowHome(width, height, ThirteenFinished());
            var layout = MeasureHome(window, height);

            Assert.True(layout.Transfers.Bounds.Width >= width - 48, "Transfers should use the window width.");
            Assert.True(layout.Transfers.Bounds.Height >= 160, "Transfers should use leftover height.");
            AssertHomeControlsVisible(layout, height);
            Assert.False(layout.Pair.IsVisible, "Pairing/QR collapses when already paired.");
            Assert.True(layout.SendTextBottom <= height + 1, "Send text must stay fully visible.");
            Assert.True(layout.RemoteDesktopBottom <= height + 1, "Remote desktop must stay fully visible.");
            Assert.Null(window.FindControl<ScrollViewer>("LowerScroll"));

            var frame = window.CaptureRenderedFrame();
            Assert.NotNull(frame);
            Assert.Equal(width, frame!.PixelSize.Width);
            Assert.Equal(height, frame.PixelSize.Height);
            SaveSnapshot(frame, $"home-after-{width}x{height}.png");
            window.Close();
        }
    }

    [Fact]
    public void ExtraWindowHeightGoesToTheTransfersList()
    {
        lock (Gate)
        {
            var shortWindow = ShowHome(1440, 753, ThirteenFinished());
            var tallWindow = ShowHome(1936, 1048, ThirteenFinished());
            var shortLayout = MeasureHome(shortWindow, 753);
            var tallLayout = MeasureHome(tallWindow, 1048);

            var extraWindow = 1048 - 753;
            var extraList = tallLayout.Transfers.Bounds.Height - shortLayout.Transfers.Bounds.Height;
            Assert.True(extraList >= extraWindow - 24, "All extra window height should go to the transfers list.");
            AssertHomeControlsVisible(shortLayout, 753);
            AssertHomeControlsVisible(tallLayout, 1048);

            SaveSnapshot(shortWindow.CaptureRenderedFrame()!, "home-after-1440x753-13rows.png");
            SaveSnapshot(tallWindow.CaptureRenderedFrame()!, "home-after-1936x1048-13rows.png");
            shortWindow.Close();
            tallWindow.Close();
        }
    }

    [Fact]
    public void PortBusyBannerClearsOnceLinked()
    {
        lock (Gate)
        {
            MainWindow.SkipAutoStart = true;
            var window = Dispatcher.UIThread.Invoke(() => new MainWindow());
            window.Model.ApplyHomeState(
                "Connected",
                "Nothing sending",
                linked: true,
                who: "Paired with Android",
                errorMessage: "Only one usage of each socket address (protocol/network address/port) is normally permitted.");
            Assert.True(window.Model.HasErrorBanner);
            Assert.Equal(PlainError.PortBusyTitle, window.Model.ErrorTitle);
            Assert.DoesNotContain("Something went wrong", window.Model.ErrorTitle, StringComparison.OrdinalIgnoreCase);

            window.Model.ApplyHomeState(
                "Connected",
                "Nothing sending",
                linked: true,
                who: "Paired with Android",
                errorMessage: null);
            Assert.False(window.Model.HasErrorBanner);
            window.Close();
        }
    }

    [Fact]
    public void OffWifiIsAStatusNotABrokenPairing()
    {
        lock (Gate)
        {
            MainWindow.SkipAutoStart = true;
            var window = Dispatcher.UIThread.Invoke(() => new MainWindow());
            window.Model.ApplyHomeState(
                HomeStatus.PhoneAway,
                "Nothing sending",
                linked: true,
                who: "Paired with Android",
                errorMessage: "Could not find the paired device. Checked mDNS, UDP broadcast.");
            window.Show();
            Dispatcher.UIThread.RunJobs();
            Assert.False(window.Model.HasErrorBanner);
            Assert.True(window.Model.HasDetails);
            Assert.Contains("mDNS", window.Model.DetailsText, StringComparison.Ordinal);
            Assert.DoesNotContain("paired device", window.Model.HeaderStatus, StringComparison.OrdinalIgnoreCase);
            window.Model.ToggleDetailsCommand.Execute(null);
            Assert.True(window.Model.ShowDetails);
            Assert.True(window.Model.HasPeer);
            Assert.NotNull(window.Model.UnpairCommand);

            var more = window.GetVisualDescendants().OfType<Button>()
                .FirstOrDefault(button => string.Equals(button.Content?.ToString(), "···", StringComparison.Ordinal));
            Assert.NotNull(more);
            var flyout = Assert.IsType<MenuFlyout>(more!.Flyout);
            var disconnect = flyout.Items.OfType<MenuItem>()
                .FirstOrDefault(item => string.Equals(item.Header?.ToString(), "Disconnect phone", StringComparison.Ordinal));
            Assert.NotNull(disconnect);
            window.Close();
        }
    }

    [Fact]
    public void ShortStatusLinesMatchThePhoneApp()
    {
        Assert.Equal("Connected", HomeStatus.Line(true, true, false, "LAN", null));
        Assert.Equal("Phone away, using relay", HomeStatus.Line(true, true, false, "Relay", null));
        Assert.Equal("Can't reach phone", HomeStatus.Line(true, false, false, "", null));
        Assert.Equal(
            PlainError.OffWifi,
            HomeStatus.Line(true, false, false, "", "Could not find the paired device. Checked mDNS"));
        Assert.Equal(
            PlainError.RelayDropped,
            HomeStatus.Line(true, false, false, "Relay", "The relay closed the connection."));
        Assert.Equal("Retrying.", HomeStatus.Line(true, false, false, "Relay", "Retrying."));
        Assert.Equal(
            HomeStatus.VpnOn,
            HomeStatus.Line(true, false, false, "", null, vpnUp: true));
        Assert.Equal(
            "A VPN is on. FlipSend may not find your phone over Wi-Fi.",
            HomeStatus.VpnOn);
        Assert.Equal("Connected", HomeStatus.Line(true, true, false, "LAN", null, vpnUp: true));
        Assert.Equal(HomeStatus.VpnOn, HomeStatus.Line(true, true, false, "Relay", null, vpnUp: true));
        Assert.Equal("Nothing sending", HomeStatus.Meta(true, false, null));
    }

    [Theory]
    [InlineData(480, 720)]
    [InlineData(1440, 753)]
    public void PairedHomeShowsConnectionLineAndRepairButtonNearTheTop(int width, int height)
    {
        lock (Gate)
        {
            var window = ShowHome(width, height, ThirteenFinished());
            var layout = MeasureHome(window, height);
            var line = window.FindControl<TextBlock>("HeaderStatusText");
            var repair = window.FindControl<Button>("RepairButton");
            Assert.NotNull(line);
            Assert.NotNull(repair);
            Assert.Equal(HomeStatus.ConnectedWifi, line!.Text);
            Assert.True(repair!.IsEffectivelyVisible, "Re-pair (show QR) must be visible on the home screen when paired.");
            Assert.Equal("Re-pair (show QR)", repair.Content?.ToString());
            var repairTop = repair.TranslatePoint(new Point(0, 0), window)!.Value.Y;
            Assert.True(repairTop + repair.Bounds.Height <= layout.SendTop, "Re-pair sits above Send, near the top.");
            AssertHomeControlsVisible(layout, height);
            Assert.False(layout.Pair.IsVisible, "QR stays hidden while paired and no re-pair is open.");
            SaveSnapshot(window.CaptureRenderedFrame()!, $"home-1559-{width}x{height}.png");
            window.Close();
        }
    }

    [Fact]
    public void RepairOpenShowsTheQrEvenWhileStillPaired()
    {
        lock (Gate)
        {
            MainWindow.SkipAutoStart = true;
            var window = Dispatcher.UIThread.Invoke(() => new MainWindow { Width = 480, Height = 720 });
            window.Model.ApplyHomeState(
                "Connected",
                "Nothing sending",
                linked: true,
                who: "Paired with Android",
                errorMessage: null,
                pairingOpen: true);
            window.Show();
            Dispatcher.UIThread.RunJobs();
            Assert.True(window.Model.HasPeer);
            Assert.True(window.Model.ShowPairCard);
            var pair = window.FindControl<Border>("PairCard");
            Assert.NotNull(pair);
            Assert.True(pair!.IsVisible, "Re-pair must show the pairing QR even though the old pairing stays until the phone confirms.");
            Assert.Equal(HomeStatus.RepairWaiting, window.Model.ConnectionLine);

            window.Model.ApplyHomeState("Connected", "Nothing sending", linked: true, who: "Paired with Android", errorMessage: null);
            Dispatcher.UIThread.RunJobs();
            Assert.False(pair.IsVisible);
            window.Close();
        }
    }

    [Fact]
    public void ConnectionLinesArePlain()
    {
        Assert.Equal("Connected on Wi-Fi", HomeStatus.Connection(true, true, Core.Transfer.RouteKind.Lan, false));
        Assert.Equal("Connected on Wi-Fi", HomeStatus.Connection(true, true, Core.Transfer.RouteKind.Direct, false));
        Assert.Equal("Connected through relay", HomeStatus.Connection(true, true, Core.Transfer.RouteKind.Relay, false));
        Assert.Equal("Not connected", HomeStatus.Connection(true, true, Core.Transfer.RouteKind.Offline, false));
        Assert.Equal("Not connected", HomeStatus.Connection(true, false, Core.Transfer.RouteKind.Lan, false));
        Assert.Equal(HomeStatus.NotPaired, HomeStatus.Connection(false, true, Core.Transfer.RouteKind.Offline, false));
        Assert.Equal(HomeStatus.RepairWaiting, HomeStatus.Connection(true, true, Core.Transfer.RouteKind.Lan, true));
    }

    private static MainWindow ShowHome(int width, int height, TransferRow[] rows)
    {
        MainWindow.SkipAutoStart = true;
        var window = Dispatcher.UIThread.Invoke(() => new MainWindow
        {
            MinWidth = Math.Min(width, 480),
            MinHeight = Math.Min(height, 720),
            Width = width,
            Height = height
        });
        window.Model.ApplyHomeState(
            "Connected",
            "Nothing sending",
            linked: true,
            who: "Paired with Android",
            errorMessage: null,
            rows: rows);
        window.Show();
        Dispatcher.UIThread.RunJobs();
        return window;
    }

    private static HomeLayout MeasureHome(MainWindow window, int height)
    {
        var send = window.FindControl<Button>("SendRow");
        var receive = window.FindControl<Button>("ReceiveRow");
        var sendText = window.FindControl<Button>("SendTextRow");
        var clipboard = window.FindControl<Button>("SendClipboardRow");
        var remote = window.FindControl<Button>("RemoteDesktopRow");
        var more = window.GetVisualDescendants().OfType<Button>()
            .FirstOrDefault(button => string.Equals(button.Content?.ToString(), "···", StringComparison.Ordinal));
        var status = window.FindControl<TextBlock>("HeaderStatusText");
        var transfers = window.FindControl<Border>("TransfersCard");
        var pair = window.FindControl<Border>("PairCard");
        var footer = window.FindControl<TextBlock>("HomeFooter");
        Assert.NotNull(send);
        Assert.NotNull(receive);
        Assert.NotNull(sendText);
        Assert.NotNull(clipboard);
        Assert.NotNull(remote);
        Assert.NotNull(more);
        Assert.NotNull(status);
        Assert.NotNull(transfers);
        Assert.NotNull(pair);
        Assert.NotNull(footer);

        Point Top(Control control) => control.TranslatePoint(new Point(0, 0), window)!.Value;
        var sendTop = Top(send!).Y;
        var receiveTop = Top(receive!).Y;
        var sendTextTop = Top(sendText!).Y;
        var clipboardTop = Top(clipboard!).Y;
        var remoteTop = Top(remote!).Y;
        var moreTop = Top(more!).Y;
        var statusTop = Top(status!).Y;
        var transfersTop = Top(transfers!).Y;
        var footerTop = Top(footer!).Y;
        return new HomeLayout(
            send,
            receive,
            sendText!,
            remote!,
            transfers!,
            pair!,
            sendTop,
            receiveTop,
            transfersTop,
            transfersTop + transfers!.Bounds.Height,
            sendTextTop,
            sendTextTop + sendText!.Bounds.Height,
            clipboardTop,
            clipboardTop + clipboard!.Bounds.Height,
            remoteTop,
            remoteTop + remote!.Bounds.Height,
            moreTop,
            moreTop + more!.Bounds.Height,
            statusTop,
            statusTop + status!.Bounds.Height,
            footerTop,
            footerTop + footer!.Bounds.Height,
            height);
    }

    private static void AssertHomeControlsVisible(HomeLayout layout, int height)
    {
        Assert.True(layout.Send.Bounds.Height > 0 && layout.ReceiveTop < height, "Send file must stay on screen.");
        Assert.True(layout.SendTextBottom <= height + 1, "Send text must stay fully visible.");
        Assert.True(layout.ClipboardBottom <= height + 1, "Send copied text must stay fully visible.");
        Assert.True(layout.RemoteDesktopBottom <= height + 1, "Remote desktop must stay fully visible.");
        Assert.True(layout.MoreBottom <= height + 1, "The ··· menu must stay fully visible.");
        Assert.True(layout.StatusBottom <= height + 1, "Connection status must stay fully visible.");
        Assert.True(layout.TransfersBottom <= height + 1, "The transfers card must stay on screen.");
        Assert.True(layout.FooterBottom <= height + 1, "The footer must stay fully visible.");
        Assert.True(layout.RemoteDesktopTop < layout.TransfersTop, "Remote desktop sits in the action bar above transfers.");
        Assert.True(layout.FooterTop >= layout.TransfersBottom - 1, "The footer sits below the list.");
    }

    private static TransferRow[] TwoFinished() =>
    [
        new() { Title = "notes.txt", Detail = "Sent", Section = "Finished", Percent = 100 },
        new() { Title = "photo.jpg", Detail = "Received", Section = "Finished", Percent = 100 }
    ];

    private static TransferRow[] ThirteenFinished() =>
    [
        new() { Title = "clipboard", Detail = "Pair a device before sending.", Section = "Finished", Percent = 100 },
        new() { Title = "clipboard", Detail = "Pair a device before sending.", Section = "Finished", Percent = 100 },
        new() { Title = "clipboard", Detail = "Pair a device before sending.", Section = "Finished", Percent = 100 },
        new() { Title = "PRIVATE_APP-1.6.15-main.apk", Detail = "Sent", Section = "Finished", Percent = 100 },
        new() { Title = "STELLAR_VAULT-1.4.21-main.apk", Detail = "Sent", Section = "Finished", Percent = 100 },
        new() { Title = "LOCAL_AI-1.0.2-main.apk", Detail = "Sent", Section = "Finished", Percent = 100 },
        new() { Title = "FINANCIAL_OS-1.0.3-main.apk", Detail = "Sent", Section = "Finished", Percent = 100 },
        new() { Title = "financial_os-editions-2026-10-19.json", Detail = "Sent", Section = "Finished", Percent = 100 },
        new() { Title = "FINANCIAL_OS-1.0.2-main.apk", Detail = "Sent", Section = "Finished", Percent = 100 },
        new() { Title = "FINANCIAL_OS-1.0.11-main.apk", Detail = "Sent", Section = "Finished", Percent = 100 },
        new() { Title = "FINANCIAL_OS-1.0.1-main.apk", Detail = "Sent", Section = "Finished", Percent = 100 },
        new() { Title = "fs1557-pc-test.txt", Detail = "Sent", Section = "Finished", Percent = 100 },
        new() { Title = "REMINDERS-1.0.0-4-main.apk", Detail = "Sent", Section = "Finished", Percent = 100 }
    ];

    private static void SaveSnapshot(Bitmap frame, string name)
    {
        var folder = Path.Combine(AppContext.BaseDirectory, "snapshots");
        Directory.CreateDirectory(folder);
        var dest = Path.Combine(folder, name);
        frame.Save(dest);
        foreach (var artifact in new[]
                 {
                     Path.Combine("/tmp/cursor/artifacts", "flipsend-1.5.58-snapshots"),
                     "/opt/cursor/artifacts"
                 })
        {
            try
            {
                Directory.CreateDirectory(artifact);
                File.Copy(dest, Path.Combine(artifact, name), overwrite: true);
            }
            catch (IOException)
            {
            }
        }
    }

    private readonly record struct HomeLayout(
        Button Send,
        Button Receive,
        Button SendText,
        Button RemoteDesktop,
        Border Transfers,
        Border Pair,
        double SendTop,
        double ReceiveTop,
        double TransfersTop,
        double TransfersBottom,
        double SendTextTop,
        double SendTextBottom,
        double ClipboardTop,
        double ClipboardBottom,
        double RemoteDesktopTop,
        double RemoteDesktopBottom,
        double MoreTop,
        double MoreBottom,
        double StatusTop,
        double StatusBottom,
        double FooterTop,
        double FooterBottom,
        int WindowHeight);
}
