package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

class ReceivedHistoryTest {
    @Test
    fun newestFileIsFirstAndShowsSizeAndUsDate() {
        val older = entry("a", "notes.txt", 2048, 1_700_000_000_000)
        val newer = entry("b", "photo.bin", 12, 1_759_000_000_000)
        val listed = ReceivedHistory.sorted(listOf(older, newer))
        assertEquals("b", listed.first().id)
        val zone = TimeZone.getTimeZone("America/New_York")
        val line = ReceivedHistory.summary(newer, zone)
        assertTrue(line.startsWith("photo.bin · 12 B · "))
        assertTrue(line.contains(UsDates.format(newer.receivedAtMillis, zone)))
        assertTrue(Regex("""\d{2}/\d{2}/\d{4} \d{1,2}:\d{2} [AP]M""").containsMatchIn(line))
        assertEquals("2 KB", ReceivedHistory.sizeLabel(older.bytes))
    }

    @Test
    fun textAndApkAreDistinctAndUpdatesKeepData() {
        assertTrue(ReceivedHistory.isText("message.txt"))
        assertTrue(ReceivedHistory.isText("Notes.MD"))
        assertFalse(ReceivedHistory.isText("app.apk"))
        assertTrue(ApkReplacePreview.isApk("app.apk"))
        assertFalse(ReceivedHistory.userActionRequired(autoInstall = true))
        assertTrue(ReceivedHistory.userActionRequired(autoInstall = false))
        assertTrue(ReceivedHistory.keepsAppData())
        assertTrue(ReceivedHistory.EXPLANATION.contains("install on their own"))
        assertTrue(ReceivedHistory.EXPLANATION.contains("first install"))
        assertTrue(ReceivedHistory.EXPLANATION.contains("Photos and other files still ask"))
        assertTrue(ReceivedHistory.EXPLANATION.contains("never uninstalled"))
    }

    @Test
    fun installingRowShowsProgressUntilThePackageVersionMatches() {
        val updating = ReceivedEntry(
            id = "1",
            name = "Press.apk",
            bytes = 10,
            receivedAtMillis = 1,
            kind = "apk",
            installStatus = "installing",
            appName = "Press",
            packageName = "app.press",
            incomingVersion = "2.0",
            incomingCode = 20,
            installedVersion = "1.0",
            installedCode = 10,
            hasInstalled = true
        )
        assertEquals(ReceivedHistory.TAG_INSTALLING, ReceivedHistory.apkTag(updating))
        assertEquals(ReceiveStatus.installingLine("Press", 40), ReceivedHistory.installProgressLine(updating, 40))
        val done = updating.copy(installedVersion = "2.0", installedCode = 20)
        assertEquals(ReceivedHistory.TAG_INSTALLED, ReceivedHistory.apkTag(done))
        val pending = updating.copy(installStatus = "pending", hasInstalled = false, installedCode = -1, installedVersion = "")
        assertEquals(ReceivedHistory.TAG_INSTALLING, ReceivedHistory.apkTag(pending))
        assertEquals(ReceiveStatus.installingLine("Press", 0), ReceivedHistory.installProgressLine(pending, 0))
    }

    private fun entry(id: String, name: String, bytes: Long, at: Long) = ReceivedEntry(
        id = id,
        name = name,
        bytes = bytes,
        receivedAtMillis = at,
        kind = "file"
    )
}
