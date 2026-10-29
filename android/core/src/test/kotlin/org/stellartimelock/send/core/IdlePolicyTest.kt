package org.stellartimelock.send.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class IdlePolicyTest {
    private val events = mutableListOf<String>()

    @BeforeEach
    fun reset() {
        events.clear()
        TransferHold.resetForTest()
        LanAnnounce.resetForTest()
        TransferHold.hooks = object : TransferHold.Hooks {
            override fun acquire() { events += "acquire" }
            override fun release() { events += "release" }
        }
    }

    @AfterEach
    fun clear() {
        TransferHold.resetForTest()
        LanAnnounce.resetForTest()
    }

    @Test
    fun locksAreOnlyHeldDuringATransfer() {
        assertFalse(TransferHold.held)
        assertEquals(0, events.size)
        val result = TransferHold.during {
            assertTrue(TransferHold.held)
            assertEquals(listOf("acquire"), events)
            "ok"
        }
        assertEquals("ok", result)
        assertFalse(TransferHold.held)
        assertEquals(listOf("acquire", "release"), events)
    }

    @Test
    fun locksAreReleasedOnError() {
        assertThrows(IllegalStateException::class.java) {
            TransferHold.during {
                assertTrue(TransferHold.held)
                error("boom")
            }
        }
        assertFalse(TransferHold.held)
        assertEquals(listOf("acquire", "release"), events)
    }

    @Test
    fun concurrentTransfersShareOneHold() {
        TransferHold.enter()
        TransferHold.enter()
        assertTrue(TransferHold.held)
        assertEquals(2, TransferHold.count)
        assertEquals(listOf("acquire"), events)
        TransferHold.leave()
        assertTrue(TransferHold.held)
        assertEquals(listOf("acquire"), events)
        TransferHold.leave()
        assertFalse(TransferHold.held)
        assertEquals(listOf("acquire", "release"), events)
    }

    @Test
    fun announceIsSkippedOffWifi() {
        val now = 1_000_000L
        assertNull(LanAnnounce.intervalMs(onWifi = false, pairingOrRepair = true, nowMs = now, lastBoostMs = now))
        assertNull(LanAnnounce.intervalMs(onWifi = false, pairingOrRepair = false, nowMs = now, lastBoostMs = now))
        assertEquals(LanAnnounce.SLOW_MS, LanAnnounce.sleepMs(null))
    }

    @Test
    fun announceIsFastDuringPairingAndTheActivityWindow() {
        val now = 5_000_000L
        assertEquals(
            LanAnnounce.FAST_MS,
            LanAnnounce.intervalMs(onWifi = true, pairingOrRepair = true, nowMs = now, lastBoostMs = 0)
        )
        assertEquals(
            LanAnnounce.FAST_MS,
            LanAnnounce.intervalMs(onWifi = true, pairingOrRepair = false, nowMs = now, lastBoostMs = now - 119_000)
        )
        assertEquals(
            LanAnnounce.SLOW_MS,
            LanAnnounce.intervalMs(onWifi = true, pairingOrRepair = false, nowMs = now, lastBoostMs = now - 121_000)
        )
    }

    @Test
    fun boostOpensTheFastWindow() {
        val now = 9_000_000L
        LanAnnounce.boost(now)
        assertEquals(
            LanAnnounce.FAST_MS,
            LanAnnounce.intervalMs(onWifi = true, pairingOrRepair = false, nowMs = now + 1_000)
        )
        assertEquals(
            LanAnnounce.SLOW_MS,
            LanAnnounce.intervalMs(onWifi = true, pairingOrRepair = false, nowMs = now + LanAnnounce.WINDOW_MS + 1)
        )
    }

    @Test
    fun relayBackoffIsExponentialAndResetsAfterAStableJoin() {
        val rejoin = RelayRejoin()
        assertEquals(1_000L, rejoin.delayAfterClose(upForMs = 0))
        assertEquals(2_000L, rejoin.delayAfterClose(upForMs = 0))
        assertEquals(5_000L, rejoin.delayAfterClose(upForMs = 0))
        assertEquals(15_000L, rejoin.delayAfterClose(upForMs = 0))
        assertEquals(30_000L, rejoin.delayAfterClose(upForMs = 0))
        assertEquals(60_000L, rejoin.delayAfterClose(upForMs = 0))
        assertEquals(60_000L, rejoin.delayAfterClose(upForMs = 10_000))
        assertEquals(1_000L, rejoin.delayAfterClose(upForMs = 30_001))
        assertEquals(2_000L, rejoin.delayAfterClose(upForMs = 0))
    }

    @Test
    fun relayCloseDescribesCodeAndReason() {
        assertEquals("code=none", RelayClose.describe(null))
        assertEquals("code=none", RelayClose.describe(ByteArray(0)))
        val payload = byteArrayOf(0x03.toByte(), 0xE8.toByte()) + "idle".toByteArray(Charsets.UTF_8)
        assertEquals("code=1000 idle", RelayClose.describe(payload))
        assertEquals("code=1001", RelayClose.describe(byteArrayOf(0x03.toByte(), 0xE9.toByte())))
    }

    @Test
    fun relayPresenceKicksOnlyWhenNotInTheRoomAndHoldIsBounded() {
        RelayPresence.resetForTest()
        val reasons = mutableListOf<String>()
        RelayPresence.rejoin = { reasons += it }
        RelayPresence.kick(RelayPresence.SCREEN_ON)
        RelayPresence.kick(RelayPresence.NETWORK)
        RelayPresence.kick(RelayPresence.APP_OPEN)
        assertEquals(listOf("screen on", "network change", "app open"), reasons)
        assertTrue(RelayPresence.needsRejoin(waitingInRoom = false))
        assertFalse(RelayPresence.needsRejoin(waitingInRoom = true))
        assertTrue(RelayPresence.holdsAfterDrop(1))
        assertTrue(RelayPresence.holdsAfterDrop(3))
        assertFalse(RelayPresence.holdsAfterDrop(4))
        assertFalse(RelayPresence.holdsAfterDrop(0))
        assertTrue(RelayPresence.HOLD_MS <= 90_000L)
        assertTrue(RelayPresence.ALARM_MS >= 60_000L)
        assertEquals(30_000L, LanAnnounce.SLOW_MS)
        assertEquals(45_000L, RelayWake.CLIENT_PING_MS)
        RelayPresence.resetForTest()
        RelayPresence.kick(RelayPresence.ALARM)
        assertEquals(3, reasons.size)
    }
}
