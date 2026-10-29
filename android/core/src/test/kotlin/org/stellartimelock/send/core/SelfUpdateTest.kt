package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SelfUpdateTest {
    @Test
    fun silentSelfUpdateIsOnlyRequestedWhenFlipSendIsInTheForeground() {
        assertTrue(SelfUpdate.requestSilent(eligible = true, installerInForeground = true))
        assertFalse(SelfUpdate.requestSilent(eligible = true, installerInForeground = false))
        assertFalse(SelfUpdate.requestSilent(eligible = false, installerInForeground = true))
        assertFalse(SelfUpdate.requestSilent(eligible = false, installerInForeground = false))
    }

    @Test
    fun pendingJsonRoundTripsAndIgnoresJunk() {
        val pending = SelfUpdate.Pending(
            historyId = "abc",
            path = "/inbox/STELLAR_SEND-1.5.26.apk",
            versionCode = 37,
            versionName = "1.5.26",
            appName = "FlipSend",
            attempts = 2
        )
        val decoded = SelfUpdate.decode(SelfUpdate.encode(pending))
        assertEquals(pending, decoded)
        assertEquals("pending-self-update.json", SelfUpdate.FILE_NAME)
        assertNull(SelfUpdate.decode(ByteArray(0)))
        assertNull(SelfUpdate.decode("not json".toByteArray()))
        assertNull(SelfUpdate.decode("""{"historyId":"x"}""".toByteArray()))
    }

    @Test
    fun aNewerInstalledVersionSettlesThePendingUpdate() {
        val pending = SelfUpdate.Pending("id", "/inbox/FlipSend.apk", versionCode = 37)
        assertTrue(SelfUpdate.settled(37, pending))
        assertTrue(SelfUpdate.settled(38, pending))
        assertFalse(SelfUpdate.settled(36, pending))
        assertFalse(SelfUpdate.settled(-1, pending))
    }

    @Test
    fun retryKeepsTheApkAndStopsAfterAFewTries() {
        val pending = SelfUpdate.Pending("id", "/inbox/FlipSend.apk", versionCode = 37, attempts = 1)
        assertTrue(SelfUpdate.shouldRetry(pending, installedCode = 36, apkExists = true))
        assertFalse(SelfUpdate.shouldRetry(pending, installedCode = 37, apkExists = true))
        assertFalse(SelfUpdate.shouldRetry(pending, installedCode = 36, apkExists = false))
        assertFalse(
            SelfUpdate.shouldRetry(pending.copy(attempts = SelfUpdate.MAX_ATTEMPTS), 36, apkExists = true)
        )
        assertFalse(SelfUpdate.mayUninstallSelf())
        assertEquals(3, SelfUpdate.MAX_ATTEMPTS)
    }
}
