using StellarSend.Core.Transfer;

namespace StellarSend.Core.Tests;

public class BluetoothPathTests
{
    [Fact]
    public void FileSendsTryWifiThenBluetoothThenRelay()
    {
        Assert.Equal(new[] { SendRoute.Wifi, SendRoute.Bluetooth, SendRoute.Relay }, SendRoute.Order);
        Assert.Equal(SendRoute.Wifi, SendRoute.Choose(lan: true, bluetooth: true, forceRelay: false));
        Assert.Equal(SendRoute.Bluetooth, SendRoute.Choose(lan: false, bluetooth: true, forceRelay: false));
        Assert.Equal(SendRoute.Relay, SendRoute.Choose(lan: false, bluetooth: false, forceRelay: false));
        Assert.Equal(SendRoute.Relay, SendRoute.Choose(lan: true, bluetooth: true, forceRelay: true));
    }

    [Fact]
    public void BluetoothBeatsTheRelayWhenWifiIsDown()
    {
        var face = ConnectionState.Face(new ConnectionInput(
            true, true, false, false, false, false, true, true, true, false, false,
            BluetoothAvailable: true));
        Assert.Equal(RouteKind.Bluetooth, face.SwitchTo);
        Assert.Equal("Bluetooth", face.Routes.First(route => route.Best).Label);
        Assert.True(face.Agrees());

        var live = ConnectionState.Face(new ConnectionInput(
            true, true, false, true, false, false, false, false, true, false, false,
            BluetoothUp: true, BluetoothAvailable: true));
        Assert.Equal("Bluetooth", live.PathPill);
        Assert.Equal("Same Wi-Fi did not answer.", live.Hint);
        Assert.Equal(RouteKind.Lan, live.SwitchTo);
        Assert.True(live.Agrees());
    }

    [Fact]
    public void MacAddressesRoundTripAndTheDummyAddressIsRejected()
    {
        Assert.False(BluetoothService.IsMac(null));
        Assert.False(BluetoothService.IsMac("02:00:00:00:00:00"));
        Assert.False(BluetoothService.IsMac("nope"));
        Assert.True(BluetoothService.IsMac("A1:B2:C3:D4:E5:F6"));
        Assert.Equal("A1:B2:C3:D4:E5:F6", BluetoothService.Format(BluetoothService.ToAddress("a1:b2:c3:d4:e5:f6")));
        Assert.Equal(25, BluetoothService.Channel);
        Assert.Equal("f17b5e1d-4c0a-4e7a-9b21-a35f0000b101", BluetoothService.UuidText);
    }
}
