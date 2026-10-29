package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PairStatusTest {
    @Test
    fun pairedAndListeningDoesNotShowRelayWake() {
        assertEquals(
            Reachability.DIRECT,
            PairStatus.pathLabel(true, true, RelayWake.STATUS)
        )
        assertEquals(
            Reachability.DIRECT,
            PairStatus.pathLabel(true, true, "")
        )
        assertNull(PairStatus.detailError(true, true, RelayWake.GAVE_UP))
        assertEquals(Reachability.RELAY, PairStatus.pathLabel(true, true, Reachability.RELAY))
        assertEquals(RelayWake.STATUS, PairStatus.pathLabel(false, true, RelayWake.STATUS))
        assertEquals(RelayWake.GAVE_UP, PairStatus.detailError(true, false, RelayWake.GAVE_UP))
        assertTrue(PairStatus.isWakeText("The relay is still waking up. Same-Wi-Fi transfers stay free. Try again in a minute."))
        assertTrue(PairStatus.isWakeText(RelayWake.NO_PEER))
        assertNull(PairStatus.detailError(true, true, RelayWake.NO_PEER))
    }
}

class HomeStatusTest {
    @Test
    fun homeShowsConnectedDisconnectedOrNotConnected() {
        assertEquals(
            HomeStatus.CONNECTED,
            HomeStatus.headline(paired = true, listening = true, pairingWindowOpen = false, lastError = null)
        )
        assertEquals(
            HomeStatus.NOT_CONNECTED,
            HomeStatus.headline(paired = false, listening = false, pairingWindowOpen = false, lastError = null)
        )
        assertEquals(
            HomeStatus.WAITING,
            HomeStatus.headline(paired = false, listening = false, pairingWindowOpen = true, lastError = null)
        )
        assertEquals(
            HomeStatus.DISCONNECTED,
            HomeStatus.headline(paired = true, listening = false, pairingWindowOpen = false, lastError = ConnectionDrop.INCOMING)
        )
        assertEquals(
            HomeStatus.CONNECTED,
            HomeStatus.headline(paired = true, listening = true, pairingWindowOpen = false, lastError = RelayWake.STATUS)
        )
        assertEquals("Paired with Windows PC", HomeStatus.who(true, "Windows PC"))
        assertEquals(HomeStatus.NO_PEER, HomeStatus.who(false, null))
        assertEquals(HomeStatus.LINKED, HomeStatus.header(true, true, false, null))
        assertEquals(
            HomeStatus.KEYS_DONT_MATCH_SHORT,
            HomeStatus.header(
                paired = true,
                listening = true,
                pairingWindowOpen = false,
                lastError = "The secure connection closed"
            )
        )
        assertEquals(
            HomeStatus.KEYS_DONT_MATCH,
            HomeStatus.who(true, "Windows PC", "The secure connection closed")
        )
        assertTrue(HomeStatus.isDrop(ConnectionDrop.RELAY))
        assertTrue(HomeStatus.isDrop(RepairPrompt.MESSAGE))
        assertFalse(HomeStatus.isDrop(RelayWake.STATUS))
        assertFalse(HomeStatus.isDrop(NetworkHop.LINE))
        assertEquals(
            HomeStatus.CONNECTED,
            HomeStatus.headline(paired = true, listening = true, pairingWindowOpen = false, lastError = NetworkHop.LINE)
        )
        assertEquals(NetworkHop.LINE, PairStatus.detailError(true, true, NetworkHop.LINE))
        assertEquals(
            HomeStatus.KEYS_DONT_MATCH,
            HomeStatus.headline(
                paired = true,
                listening = true,
                pairingWindowOpen = false,
                lastError = "The phone answered on Wi-Fi, but the secure handshake did not finish. IOException: The secure connection closed"
            )
        )
        assertEquals(
            HomeStatus.KEYS_DONT_MATCH,
            HomeStatus.headline(
                paired = true,
                listening = true,
                pairingWindowOpen = false,
                lastError = "Disconnected. The other device hung up. javax.net.ssl.SSLHandshakeException: Handshake failed"
            )
        )
        assertFalse(HomeStatus.isDrop(TrustNewKey.message("Windows PC")))
    }
}

class ConnectionFaceTest {
    @Test
    fun everyIndicatorAgrees() {
        val cases = listOf(
            ConnectionInput(false, false, false, false, false, false, false, false, false, false, false),
            ConnectionInput(false, false, true, false, false, false, false, false, false, false, false),
            ConnectionInput(true, true, false, onLan = true, lanUp = false, directUp = false, directAvailable = false, relayUp = false, relayAvailable = true, usbUp = false, usbAvailable = false),
            ConnectionInput(true, true, false, onLan = true, lanUp = true, directUp = false, directAvailable = false, relayUp = false, relayAvailable = true, usbUp = false, usbAvailable = false),
            ConnectionInput(true, true, false, onLan = false, lanUp = false, directUp = false, directAvailable = true, relayUp = true, relayAvailable = true, usbUp = false, usbAvailable = false),
            ConnectionInput(true, true, false, onLan = true, lanUp = false, directUp = false, directAvailable = false, relayUp = true, relayAvailable = true, usbUp = false, usbAvailable = false),
            ConnectionInput(true, true, false, onLan = false, lanUp = false, directUp = false, directAvailable = false, relayUp = true, relayAvailable = true, usbUp = false, usbAvailable = false),
            ConnectionInput(true, false, false, onLan = false, lanUp = false, directUp = false, directAvailable = false, relayUp = false, relayAvailable = false, usbUp = true, usbAvailable = true)
        )
        cases.forEach { input ->
            val face = ConnectionState.face(input)
            assertTrue(face.agrees(), face.indicators().joinToString(" | "))
        }
        val relay = ConnectionState.face(cases[4])
        assertEquals("Connected", relay.headline)
        assertEquals("Relay", relay.pathPill)
        assertEquals("Relay now, Direct available (best), tap to switch", relay.routesLine)
        assertEquals(RouteKind.DIRECT, relay.switchTo)
        val missedLan = ConnectionState.face(cases[5])
        assertEquals("Same Wi-Fi did not answer.", missedLan.hint)
        assertTrue(missedLan.routesLine.contains("LAN available (best)"))
        val usb = ConnectionState.face(cases[7])
        assertEquals("USB", usb.pathPill)
        assertEquals("Connected", usb.headline)
        val closed = ConnectionState.face(
            ConnectionInput(
                paired = true,
                listening = true,
                pairingWindowOpen = false,
                onLan = true,
                lanUp = true,
                directUp = false,
                directAvailable = false,
                relayUp = false,
                relayAvailable = true,
                usbUp = false,
                usbAvailable = false,
                lastError = "The secure connection closed"
            )
        )
        assertTrue(closed.agrees(), closed.indicators().joinToString(" | "))
        assertEquals(HomeStatus.KEYS_DONT_MATCH, closed.headline)
        assertEquals(RouteKind.OFFLINE, closed.active)
        assertFalse(closed.connected)
        assertFalse(closed.indicators().any { it.contains("Not connected", ignoreCase = true) })
        assertTrue(closed.hint.contains("secure connection closed", ignoreCase = true))
        val rows = ConnectionMethods.rows(
            ConnectionInput(
                paired = true,
                listening = true,
                pairingWindowOpen = false,
                onLan = true,
                lanUp = true,
                directUp = false,
                directAvailable = false,
                relayUp = true,
                relayAvailable = true,
                usbUp = false,
                usbAvailable = false,
                lastError = "SSLHandshakeException: other device hung up"
            )
        )
        assertTrue(rows.none { it.mark == ConnectionMark.CONNECTED })
    }

    @Test
    fun eachMethodIsItsOwnRowAndBluetoothIsARealRoute() {
        val relay = ConnectionInput(
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
            usbAvailable = false
        )
        val rows = ConnectionMethods.rows(relay)
        assertEquals(listOf("Wi-Fi", "Direct", "Relay", "USB", "Bluetooth"), rows.map { it.name })
        assertEquals(ConnectionMark.OFF, rows[0].mark)
        assertEquals("Off", rows[0].label)
        assertEquals(ConnectionMark.AVAILABLE, rows[1].mark)
        assertEquals("Available", rows[1].label)
        assertTrue(rows[1].canSwitch)
        assertEquals("Direct", rows[1].route)
        assertEquals(ConnectionMark.CONNECTED, rows[2].mark)
        assertEquals("Connected", rows[2].label)
        assertFalse(rows[2].canSwitch)
        assertEquals(ConnectionMark.NOT_SET_UP, rows[3].mark)
        assertEquals("Not set up", rows[3].label)
        assertEquals(ConnectionMark.OFF, rows[4].mark)
        assertEquals("Off", rows[4].label)
        assertFalse(rows[4].canSwitch)
        val ready = ConnectionMethods.rows(relay.copy(bluetoothAvailable = true))
        assertEquals(ConnectionMark.AVAILABLE, ready.first { it.name == "Bluetooth" }.mark)
        assertEquals("Available", ready.first { it.name == "Bluetooth" }.label)
        assertTrue(ready.first { it.name == "Bluetooth" }.canSwitch)
        assertEquals("Bluetooth", ready.first { it.name == "Bluetooth" }.route)
        val live = ConnectionMethods.rows(relay.copy(bluetoothUp = true, bluetoothAvailable = true, relayUp = false))
        assertEquals(ConnectionMark.CONNECTED, live.first { it.name == "Bluetooth" }.mark)
        assertEquals("Connected", live.first { it.name == "Bluetooth" }.label)
        val setup = ConnectionMethods.rows(relay.copy(bluetoothNeedsSetup = true))
        assertEquals(ConnectionMark.NOT_SET_UP, setup.first { it.name == "Bluetooth" }.mark)
        assertEquals("Not set up", setup.first { it.name == "Bluetooth" }.label)
        val usb = ConnectionMethods.rows(
            ConnectionInput(true, false, false, false, false, false, false, false, false, usbUp = true, usbAvailable = true)
        )
        assertEquals(ConnectionMark.CONNECTED, usb.first { it.name == "USB" }.mark)
    }
}

class TransferQueueTest {
    @Test
    fun groupsInProgressPendingAndFinished() {
        val board = ReceiveBoard()
        board.noteProgress("1", "Notes.apk", 50, 100, 1, 1)
        board.noteFile("2", "Mail.apk", "apk", "/mail.apk", appName = "Mail", packageName = "mail")
        board.noteInstall("2", "pending")
        board.noteFile("3", "Maps.apk", "apk", "/maps.apk", appName = "Maps", packageName = "maps")
        board.noteInstall("3", "success")
        board.noteFile("4", "Keep.apk", "apk", "/keep.apk", appName = "Keep", packageName = "keep")
        board.noteInstall("4", "needs-tap")
        val rows = TransferQueue.rows(board.snap())
        assertEquals(TransferQueue.IN_PROGRESS, rows[0].section)
        assertEquals("50%", rows[0].detail)
        assertEquals(TransferQueue.PENDING, rows.first { it.title == "Mail" }.section)
        assertEquals(TransferQueue.FINISHED, rows.first { it.title == "Maps" }.section)
        assertEquals(TransferQueue.INSTALLED, rows.first { it.title == "Maps" }.detail)
        assertEquals(TransferQueue.TAP, rows.first { it.title == "Keep" }.detail)
    }
}

class NetworkHopTest {
    @Test
    fun aNetworkChangeIsASwitchAndNeighboringWifiIsNotTheSameLan() {
        val home = NetworkPlace(NetTransport.WIFI, "192.168.1.20")
        assertFalse(NetworkHop.changed(null, home))
        assertFalse(NetworkHop.changed(home, home.copy()))
        assertTrue(NetworkHop.changed(home, NetworkPlace(NetTransport.WIFI, "10.0.0.8")))
        assertTrue(NetworkHop.changed(home, NetworkPlace(NetTransport.CELLULAR, "100.64.1.2")))
        assertTrue(NetworkHop.changed(NetworkPlace(NetTransport.CELLULAR, null), home))
        assertTrue(NetworkHop.changed(home, NetworkPlace(NetTransport.NONE, null)))
        assertEquals(Reachability.DIRECT, NetworkHop.path(foundOnLan = true))
        assertEquals(Reachability.RELAY, NetworkHop.path(foundOnLan = false))
        assertTrue(NetworkHop.sameSubnet("192.168.1.20", "192.168.1.77"))
        assertFalse(NetworkHop.sameSubnet("192.168.1.20", "192.168.2.20"))
        assertTrue(NetworkHop.isSwitch(NetworkHop.LINE))
        assertFalse(HomeStatus.isDrop(NetworkHop.LINE))
    }
}

class ConnectionDropTest {
    @Test
    fun disconnectTextIsPlainEnglishAndKeepsAReason() {
        assertEquals(
            "Disconnected. The other device hung up.",
            ConnectionDrop.INCOMING
        )
        assertEquals(
            ConnectionDrop.RELAY,
            ConnectionDrop.line(ConnectionDrop.RELAY, "")
        )
        assertTrue(ConnectionDrop.line(ConnectionDrop.WIFI_LISTEN, "Connection reset").contains("starting it again"))
        assertTrue(ConnectionDrop.line(ConnectionDrop.WIFI_LISTEN, "Connection reset").contains("Connection reset"))
        assertEquals("[redacted]", ConnectionDrop.line(ConnectionDrop.INCOMING, "stellarsend://pair?v=1").substringAfter("hung up. ").trim())
        assertEquals(
            "${ConnectionDrop.INCOMING} ${ConnectionDrop.SECURE_RETRY}",
            ConnectionDrop.line(ConnectionDrop.INCOMING, "Read error: SSLV3_ALERT_CERTIFICATE_UNKNOWN (tls_record.cc:489)")
        )
        assertFalse(ConnectionDrop.line(ConnectionDrop.INCOMING, "SSLV3_ALERT_CERTIFICATE_UNKNOWN").contains("no longer recognizes"))
    }
}

class ApkLeftoversTest {
    @Test
    fun olderLogRowsAreTheOnesThatAreNotNewest() {
        val old = ReceivedEntry("1", "A-1.apk", 10, 1, "apk", packageName = "app.a", incomingCode = 1)
        val neu = ReceivedEntry("2", "A-2.apk", 10, 2, "apk", packageName = "app.a", incomingCode = 9)
        val other = ReceivedEntry("3", "B.apk", 10, 3, "apk", packageName = "app.b", incomingCode = 1)
        assertEquals(listOf("1"), ApkLeftovers.olderLogEntries(listOf(old, neu, other)).map { it.id })
    }

    @Test
    fun orphanDiskFilesKeepTheNewestCopyOfEachPackage() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "ss-apk-" + System.nanoTime())
        dir.mkdirs()
        val old = java.io.File(dir, "App-1.apk").apply { writeText("old") }
        val neu = java.io.File(dir, "App-9.apk").apply { writeText("new") }
        val kept = listOf(
            ReceivedEntry("2", "App-9.apk", 3, 2, "apk", packageName = "app.notes", incomingCode = 9)
        )
        val drop = ApkLeftovers.orphanFiles(listOf(old, neu), kept) { file ->
            if (file.name.contains("1")) "app.notes" to 1L else "app.notes" to 9L
        }
        assertEquals(listOf(old), drop)
        dir.deleteRecursively()
    }
}

class StartupPermissionsTest {
    @Test
    fun startAsksEveryRuntimePermissionTogether() {
        val modern = StartupPermissions.runtime(35)
        assertEquals(
            listOf(
                StartupPermissions.CAMERA,
                StartupPermissions.BLUETOOTH_CONNECT,
                StartupPermissions.BLUETOOTH_SCAN,
                StartupPermissions.POST_NOTIFICATIONS,
                StartupPermissions.NEARBY_WIFI_DEVICES
            ),
            modern
        )
        val older = StartupPermissions.runtime(29)
        assertTrue(older.contains(StartupPermissions.CAMERA))
        assertTrue(older.contains(StartupPermissions.READ_EXTERNAL_STORAGE))
        assertFalse(older.contains(StartupPermissions.POST_NOTIFICATIONS))
        assertTrue(StartupPermissions.needsUnknownApps(35, false))
        assertFalse(StartupPermissions.needsUnknownApps(35, true))
        val missing = StartupPermissions.missing(35) { it == StartupPermissions.CAMERA }
        assertEquals(
            listOf(
                StartupPermissions.BLUETOOTH_CONNECT,
                StartupPermissions.BLUETOOTH_SCAN,
                StartupPermissions.POST_NOTIFICATIONS,
                StartupPermissions.NEARBY_WIFI_DEVICES
            ),
            missing
        )
        assertFalse(StartupPermissions.runtime(29).contains(StartupPermissions.BLUETOOTH_CONNECT))
    }
}

class ScreenShareTest {
    @Test
    fun onlyARealJpegIsSent() {
        assertFalse(ScreenShare.acceptJpeg(null))
        assertFalse(ScreenShare.acceptJpeg(byteArrayOf(1, 2, 3)))
        assertFalse(ScreenShare.acceptJpeg(ByteArray(ScreenShare.MAX_JPEG_BYTES + 1) { 0xFF.toByte() }))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        assertTrue(ScreenShare.acceptJpeg(jpeg))
        assertEquals("screen.share", CompanionServices.SCREEN_SHARE)
        assertEquals(13, MessageType.SCREEN_FRAME.code)
        assertEquals("remote.control", CompanionServices.REMOTE_CONTROL)
        assertEquals(14, MessageType.REMOTE_INPUT.code)
    }
}

class TvCastTest {
    @Test
    fun chromecastFriendlyNameComesFromFnAndDropsAreNeverSilent() {
        assertEquals("Living Room", TvCast.friendlyName(mapOf("fn" to "Living Room", "md" to "Chromecast"), "Chromecast-abcd"))
        assertEquals("Bedroom", TvCast.friendlyName(emptyMap(), "Bedroom"))
        assertEquals("Chromecast", TvCast.friendlyName(emptyMap(), "  "))
        assertTrue(TvCast.isDefaultRouteName("Phone"))
        assertFalse(TvCast.isDefaultRouteName("Living Room TV"))
        assertEquals(TvCast.STOPPED, TvCast.dropReason(userStop = true, displayGone = false, unselected = false))
        assertEquals(TvCast.DROPPED, TvCast.dropReason(userStop = false, displayGone = true, unselected = false))
        assertEquals(TvCast.UNSELECTED, TvCast.dropReason(userStop = false, displayGone = false, unselected = true))
        assertEquals(TvCast.GOOGLE_CAST_SERVICE, "_googlecast._tcp")
        assertEquals("Cast to TV", TvCast.BUTTON)
        assertEquals("Pick Living Room on the next sheet. Android will mirror this phone.", TvCast.pickNamed("Living Room"))
        assertEquals("Showing this phone on Kitchen.", TvCast.showing("Kitchen"))
        assertTrue(TvCast.noneFound(0).contains("No TV found"))
        assertEquals("", TvCast.noneFound(2))
    }
}

class AppPriceTest {
    @Test
    fun phoneAppPriceIsTwoNinetyNineNinetyNine() {
        assertEquals("$2.9999", AppPrice.TEXT)
        assertEquals("$2.9999", AppPrice.label(BigDecimal("2.9999")))
        assertEquals("$2.9999 app", AppPrice.withApp(BigDecimal("2.9999")))
    }
}
