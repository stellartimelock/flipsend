package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReceiveBoardTest {
    @Test
    fun statusNamesTheFileInTheBatchAndNeverGoesBlank() {
        val board = ReceiveBoard()
        assertEquals(ReceiveStatus.READY, board.snap().status)
        assertTrue(board.snap().status.isNotBlank())

        board.noteSender("Windows PC")
        board.noteProgress("t1", "One.apk", 0, 100, batchIndex = 1, batchCount = 7)
        assertEquals("Receiving 1 of 7: One.apk", board.snap().status)

        board.noteFile("1", "One.apk", "apk", "/inbox/One.apk")
        board.noteIdle()
        val between = board.snap()
        assertEquals("Received 1 of 7", between.status)
        assertEquals("Received 1 app", between.barStatus)
        assertEquals("From Windows PC", between.senderLine)
        assertEquals(1, between.files.size)
        assertTrue(between.status.isNotBlank())

        board.noteProgress("t3", "AppName.apk", 40, 80, batchIndex = 3, batchCount = 7)
        val current = board.snap()
        assertEquals("Receiving 3 of 7: AppName.apk", current.status)
        assertEquals(50, current.percent)
        assertEquals("Install", ReceiveStatus.action("apk", "One.apk"))
        assertEquals("Open", ReceiveStatus.action("file", "notes.txt"))
    }

    @Test
    fun idleAfterInstallDoesNotSayWaiting() {
        val board = ReceiveBoard()
        board.noteSender("Windows PC")
        board.noteFile("1", "Events.apk", "apk", "/inbox/Events.apk", appName = "Events", packageName = "app.events")
        board.noteInstall("1", "success")
        board.noteIdle()
        val snap = board.snap()
        assertEquals("1 app installed", snap.barStatus)
        assertTrue(!snap.showBar)
        assertTrue(!snap.barStatus.contains("Waiting"))
    }

    @Test
    fun replacingShowsOneStatusAndNoFlipSendConfirm() {
        val board = ReceiveBoard()
        board.noteFile("1", "Events.apk", "apk", "/inbox/Events.apk", appName = "Events", packageName = "app.events")
        board.noteInstall("1", "replacing")
        board.noteIdle()
        board.noteInstallProgress("Events", 0, 100)
        val snap = board.snap()
        assertEquals("Replacing Events", snap.barStatus)
        assertTrue(snap.showBar)
        assertTrue(!snap.barStatus.contains("confirm", ignoreCase = true))
        assertTrue(!snap.barStatus.contains("Waiting", ignoreCase = true))
    }

    @Test
    fun installProgressMovesTheBar() {
        val board = ReceiveBoard()
        board.noteFile("1", "Notes.apk", "apk", "/inbox/Notes.apk", appName = "Notes")
        board.noteInstall("1", "installing")
        board.noteIdle()
        board.noteInstallProgress("Notes", 40, 80)
        val snap = board.snap()
        assertTrue(snap.installing)
        assertEquals(50, snap.installPercent)
        assertEquals(50, snap.barPercent)
        assertEquals("Installing Notes · 50%", snap.barStatus)
        assertEquals("Installing Notes · 50%", ReceiveStatus.installingLine("Notes", 50))
    }

    @Test
    fun filesWithoutAKnownTotalStillCountUp() {
        val board = ReceiveBoard()
        board.noteProgress("a", "A.apk", 1, 2, 0, 0)
        assertEquals("Receiving A.apk", board.snap().status)
        board.noteFile("1", "A.apk", "apk", "/inbox/A.apk")
        board.noteIdle()
        board.noteProgress("b", "B.apk", 1, 2, 0, 0)
        assertEquals("Receiving 2: B.apk", board.snap().status)
        assertEquals(1, board.snap().files.size)
    }

    @Test
    fun aNewBatchClearsThePreviousList() {
        val board = ReceiveBoard()
        board.noteProgress("a", "Old.apk", 1, 1, 1, 2)
        board.noteFile("1", "Old.apk", "apk", "/inbox/Old.apk")
        board.noteIdle()
        board.noteProgress("b", "New.apk", 1, 1, 1, 3)
        assertEquals("Receiving 1 of 3: New.apk", board.snap().status)
        assertTrue(board.snap().files.isEmpty())
    }
}
