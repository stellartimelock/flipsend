package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BluetoothPathTest {
    @Test
    fun fileSendsTryWifiThenBluetoothThenRelay() {
        assertEquals(listOf(SendRoute.WIFI, SendRoute.BLUETOOTH, SendRoute.RELAY), SendRoute.order)
        assertEquals(SendRoute.WIFI, SendRoute.choose(lan = true, bluetooth = true, forceRelay = false))
        assertEquals(SendRoute.BLUETOOTH, SendRoute.choose(lan = false, bluetooth = true, forceRelay = false))
        assertEquals(SendRoute.RELAY, SendRoute.choose(lan = false, bluetooth = false, forceRelay = false))
        assertEquals(SendRoute.RELAY, SendRoute.choose(lan = true, bluetooth = true, forceRelay = true))
    }

    @Test
    fun bluetoothBeatsTheRelayWhenWifiIsDown() {
        val face = ConnectionState.face(
            ConnectionInput(
                paired = true,
                listening = true,
                pairingWindowOpen = false,
                onLan = false,
                lanUp = false,
                directUp = false,
                directAvailable = true,
                relayUp = true,
                relayAvailable = true,
                usbUp = false,
                usbAvailable = false,
                bluetoothUp = false,
                bluetoothAvailable = true
            )
        )
        assertEquals(RouteKind.BLUETOOTH, face.switchTo)
        assertEquals(ConnectionState.BLUETOOTH, face.routes.first { it.best }.label)
        assertTrue(face.agrees())
        val live = ConnectionState.face(
            ConnectionInput(
                paired = true,
                listening = true,
                pairingWindowOpen = false,
                onLan = true,
                lanUp = false,
                directUp = false,
                directAvailable = false,
                relayUp = false,
                relayAvailable = true,
                usbUp = false,
                usbAvailable = false,
                bluetoothUp = true,
                bluetoothAvailable = true
            )
        )
        assertEquals("Bluetooth", live.pathPill)
        assertEquals("Same Wi-Fi did not answer.", live.hint)
        assertEquals(RouteKind.LAN, live.switchTo)
        assertTrue(live.agrees())
    }

    @Test
    fun checklistOpensTheMatchingPhoneSettings() {
        val ask = BluetoothChecklist.step(permissionGranted = false, radioOn = false, askedAlready = false, shouldShowRationale = false)
        assertEquals(BluetoothSetup.ASK, ask.action)
        assertEquals("Allow Bluetooth", ask.label)
        val denied = BluetoothChecklist.step(permissionGranted = false, radioOn = true, askedAlready = true, shouldShowRationale = false)
        assertEquals(BluetoothSetup.APP_SETTINGS, denied.action)
        val again = BluetoothChecklist.step(permissionGranted = false, radioOn = false, askedAlready = true, shouldShowRationale = true)
        assertEquals(BluetoothSetup.ASK, again.action)
        val radio = BluetoothChecklist.step(permissionGranted = true, radioOn = false, askedAlready = true, shouldShowRationale = false)
        assertEquals(BluetoothSetup.BLUETOOTH_SETTINGS, radio.action)
        assertEquals("Turn on Bluetooth", radio.label)
        val ready = BluetoothChecklist.step(permissionGranted = true, radioOn = true, askedAlready = true, shouldShowRationale = false)
        assertEquals(BluetoothSetup.BLUETOOTH_SETTINGS, ready.action)
        assertTrue(ready.detail.contains("Pair"))
    }

    @Test
    fun aDummyBluetoothAddressIsNotStored() {
        assertFalse(BluetoothService.isMac(null))
        assertFalse(BluetoothService.isMac("02:00:00:00:00:00"))
        assertFalse(BluetoothService.isMac("not-a-mac"))
        assertTrue(BluetoothService.isMac("A1:B2:C3:D4:E5:F6"))
        assertEquals(25, BluetoothService.CHANNEL)
        assertEquals("f17b5e1d-4c0a-4e7a-9b21-a35f0000b101", BluetoothService.UUID_TEXT)
    }
}
