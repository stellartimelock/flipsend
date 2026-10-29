package org.stellartimelock.send.core

import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayPlanTests {
    @Test
    fun paidTiersStayInsideTheWorstCaseMargin() {
        val table = load()
        assertEquals(1_000_000_000L, table.bytesPerGb)
        assertEquals(BigDecimal("0.15"), table.egressUsdPerGb)
        assertEquals(BigDecimal("0.15"), table.playFeeRate)
        assertEquals(BigDecimal("2.9999"), table.appPriceUsd)
        val appNet = table.appPriceUsd.multiply(BigDecimal.ONE.subtract(table.playFeeRate))
        assertTrue(table.free.egressCost(table.egressUsdPerGb) <= appNet, "one free month must fit inside the app price after the store fee")
        assertTrue(table.paid.isNotEmpty())
        for (tier in table.paid) {
            assertTrue(tier.staysProfitable(table.egressUsdPerGb, table.playFeeRate), tier.id)
        }
        assertEquals("plus", table.recommended?.id)
        assertEquals(listOf("relay_pro", "relay_plus", "relay_max"), table.paid.map { it.productId })
    }

    @Test
    fun anAwakeRelayIsNotStillWakingUp() {
        assertEquals(RelayWake.PHONE_MISSING, RelayWake.afterAttempts(true))
        assertEquals(RelayWake.GAVE_UP, RelayWake.afterAttempts(false))
        assertEquals(1_000L, RelayWake.rejoinMs(onWifi = true))
        assertEquals(1_000L, RelayWake.rejoinMs(onWifi = false))
        assertEquals(1_000L, RelayWake.rejoinMs(0))
        assertEquals(2_000L, RelayWake.rejoinMs(1))
        assertEquals(5_000L, RelayWake.rejoinMs(2))
        assertEquals(15_000L, RelayWake.rejoinMs(3))
        assertEquals(30_000L, RelayWake.rejoinMs(4))
        assertEquals(60_000L, RelayWake.rejoinMs(5))
        assertEquals(60_000L, RelayWake.rejoinMs(99))
        assertEquals(45_000L, RelayWake.CLIENT_PING_MS)
        assertTrue(RelayWake.rejoinMs(true) < 30_000L)
    }

    @Test
    fun wakeBudgetRetriesThenStops() {
        val plan = RelayWake.attempts()
        assertTrue(plan.size > 1)
        assertEquals(0L, plan.first().delayMs)
        assertTrue(RelayWake.budgetOf(plan) <= RelayWake.BUDGET_MS)
    }

    @Test
    fun localTransfersAreNeverCapped() {
        assertTrue(RelayPlans.localTransfersAllowed())
        assertFalse(RelayPlans.overCap(used = 999, limit = 10, enforced = false))
        assertTrue(RelayPlans.overCap(used = 10, limit = 10, enforced = true))
        assertEquals(0L, RelayPlans.bytesThisPeriod("2026-09", 50, "2026-10"))
        assertEquals(50L, RelayPlans.bytesThisPeriod("2026-10", 50, "2026-10"))
        assertFalse(RelayPlans.countsRelay(Reachability.DIRECT))
        assertFalse(RelayPlans.countsRelay(MethodIds.USB))
        assertFalse(RelayPlans.countsRelay("USB"))
        assertFalse(RelayPlans.countsRelay("LAN"))
        assertTrue(RelayPlans.countsRelay(Reachability.RELAY))
    }

    @Test
    fun fairUseLabelUsesTheMonthlyCap() {
        val table = load()
        assertEquals(100_000_000L, table.fairUseBytes)
        assertEquals("Relay: 0 of 100 MB used this month", RelayMeter.row(0, table.fairUseBytes, unlimited = false))
        assertEquals("Relay: 1000 of 1000 MB used this month", RelayMeter.row(1_000_000_000, 1_000_000_000, unlimited = false))
        assertEquals("Relay: unlimited", RelayMeter.row(5, 0, unlimited = true))
        assertEquals(
            "Relay 0 of 100 MB · Nothing sending",
            RelayMeter.homeMeta(0, table.fairUseBytes, unlimited = false, sending = "")
        )
        assertEquals("Resets on the 1st.", RelayPlans.RESETS)
        assertTrue(RelayPlans.FAIR_USE_MESSAGE.contains("Wi-Fi transfers still work"))
        assertEquals("Relay busy, Wi-Fi transfers still work", RelayPlans.BUSY_MESSAGE)
    }

    @Test
    fun joinCarriesThePurchaseAndNotTheFile() {
        val room = RelayRoom.paired(
            "11111111-1111-1111-1111-111111111111",
            "22222222-2222-2222-2222-222222222222"
        )
        val plain = JSONObject(RelayJoin.write(room, "dial"))
        assertEquals(1, plain.getInt("v"))
        assertEquals(room, plain.getString("room"))
        assertEquals("dial", plain.getString("role"))
        assertEquals(3, plain.length())
        val withDevice = JSONObject(RelayJoin.write(room, "dial", deviceId = "11111111-1111-1111-1111-111111111111"))
        assertEquals("11111111-1111-1111-1111-111111111111", withDevice.getString("device"))
        val key = RelayPlans.entitlementKey("11111111-1111-1111-1111-111111111111")
        assertEquals(64, key.length)
        assertEquals("c30c56297025c9d0cdca357ebae9c3526f97f1155bf7938d10e243bd44bd6875", key)
        val auth = RelayAuth(
            purchaseToken = "play-token",
            productId = "relay_plus",
            basePlanId = "monthly",
            packageName = RelayPlans.PACKAGE,
            entitlementKey = key
        )
        val joined = RelayJoin.write(room, "listen", auth)
        assertTrue(joined.contains("play-token"))
        assertFalse(joined.contains("hello from a file"))
        val reply = RelayJoin.parseReply("""{"v":1,"ok":false,"error":"quota","message":"used up"}""")
        assertTrue(reply.quota)
        assertEquals("[redacted]", LogRedaction.redact("purchasetoken=play-token"))
    }

    private fun load(): TierTable {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(8) {
            val candidate = dir?.let { File(it, "relay/tiers.json") }
            if (candidate != null && candidate.isFile) return TierTable.parse(candidate.readText())
            dir = dir?.parentFile
        }
        error("relay/tiers.json was not found")
    }
}
