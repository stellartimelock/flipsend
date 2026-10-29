package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApkBatchTest {
    @Test
    fun statusComparesIncomingAndInstalledVersions() {
        val newer = apk(code = 12, installed = 8)
        val same = apk(code = 8, installed = 8)
        val older = apk(code = 3, installed = 8)
        val missing = apk(code = 12, installed = null)
        val failed = apk(code = 12, installed = 8).copy(installMark = "failed")
        val done = apk(code = 12, installed = 8).copy(installMark = "success")

        assertEquals(ApkBatch.UPDATE, ApkBatch.status(newer))
        assertEquals(ApkBatch.INSTALLED, ApkBatch.status(same))
        assertEquals(ApkBatch.OLDER, ApkBatch.status(older))
        assertEquals(ApkBatch.NOT_INSTALLED, ApkBatch.status(missing))
        assertEquals(ApkBatch.FAILED, ApkBatch.status(failed))
        assertEquals(ApkBatch.INSTALLED, ApkBatch.status(done))
        assertEquals("1.4 (12) vs installed 1.2 (8)", ApkBatch.versionLine(newer))
        assertEquals("1.4 (12) vs not installed", ApkBatch.versionLine(missing))
        assertEquals("Notes", ApkBatch.appName(newer))
        assertEquals("Notes", ApkBatch.displayName(newer))
        assertEquals("app release", ApkBatch.displayName(newer.copy(appName = "", name = "app-release.apk")))
        assertEquals("Notes", ApkBatch.displayName(newer.copy(appName = "Notes.apk", name = "Notes.apk")))
    }

    @Test
    fun installAllSkipsInstalledAndOlderAndNeverNeedsAnUninstall() {
        val files = listOf(
            apk("a", code = 2, installed = 1),
            apk("b", code = 1, installed = 5),
            apk("c", code = 4, installed = 4),
            apk("d", code = 9, installed = null).copy(installMark = "failed"),
            apk("e", code = 1, installed = null).copy(packageName = "")
        )
        assertEquals(listOf("a", "d"), ApkBatch.actionable(files).map { it.id })
        assertTrue(ReceivedHistory.keepsAppData())
    }

    @Test
    fun summaryCountsInstalledAndWhateverStillNeedsAttention() {
        val files = (1..6).map { apk("ok$it", code = 2, installed = 2) } +
            apk("old", code = 1, installed = 3)
        assertEquals("6 of 7 installed", ApkBatch.summary(files))
        assertEquals("5 of 6 installed", ApkBatch.summary(files.drop(1)))
        val twoLeft = files.drop(2) + apk("fail", code = 2, installed = 1).copy(installMark = "failed")
        assertEquals("4 of 6 installed", ApkBatch.summary(twoLeft))
        assertEquals("1 of 1 installed", ApkBatch.summary(listOf(apk("only", code = 1, installed = 1))))
        assertEquals("", ApkBatch.summary(listOf(ReceiveFile("1", "notes.txt", "text", "/inbox/notes.txt"))))
    }

    @Test
    fun silentUpdateIsOnlyForAndroid12UpdatesFlipSendInstalled() {
        assertTrue(ApkBatch.silentUpdate(sdk = 31, alreadyInstalled = true, installedBySelf = true, signatureMatch = ApkSignatures.MATCH, targetSdk = 29))
        assertTrue(ApkBatch.silentUpdate(sdk = 35, alreadyInstalled = true, installedBySelf = true, signatureMatch = ApkSignatures.MATCH, targetSdk = 33))
        assertFalse(ApkBatch.silentUpdate(sdk = 30, alreadyInstalled = true, installedBySelf = true, signatureMatch = ApkSignatures.MATCH, targetSdk = 33))
        assertFalse(ApkBatch.silentUpdate(sdk = 31, alreadyInstalled = false, installedBySelf = true, signatureMatch = ApkSignatures.MATCH, targetSdk = 29))
        assertFalse(ApkBatch.silentUpdate(sdk = 31, alreadyInstalled = true, installedBySelf = false, signatureMatch = ApkSignatures.MATCH, targetSdk = 29))
        assertFalse(ApkBatch.silentUpdate(sdk = 35, alreadyInstalled = true, installedBySelf = true, signatureMatch = ApkSignatures.DIFFER, targetSdk = 35))
    }

    @Test
    fun theBoardKeepsVersionDetailsAndATerminalResult() {
        val board = ReceiveBoard()
        board.noteFile("1", "Notes.apk", "apk", "/inbox/Notes.apk")
        board.notePackage("1", "Notes", "app.notes", "1.4", 12, "1.2", 8, true, signatureMatch = ApkSignatures.MATCH)
        board.notePackage("1", "Notes", "app.notes", "1.4", 12, "1.2", 8, true)
        board.noteInstall("1", "success")
        val file = board.snap().files.single()
        assertEquals(ApkBatch.INSTALLED, ApkBatch.status(file))
        assertEquals(12, file.installedCode)
        assertEquals("1.4", file.installedVersion)
        assertEquals(ApkSignatures.MATCH, file.signatureMatch)
        board.notePackage("1", "Notes", "app.notes", "1.4", 12, "1.2", 8, true, signatureMatch = ApkSignatures.DIFFER)
        assertEquals(ApkSignatures.DIFFER, board.snap().files.single().signatureMatch)
        board.noteInstall("1", "failed", "signature mismatch")
        assertEquals(ApkBatch.FAILED, ApkBatch.status(board.snap().files.single()))
        assertEquals("Failed: signature mismatch", ApkBatch.statusLine(board.snap().files.single()))
        board.noteInstall("1", "failed")
        assertEquals("signature mismatch", board.snap().files.single().installDetail)
    }

    @Test
    fun duplicateOlderCopiesAreHiddenAndALowerVersionIsNotInstalled() {
        val olderCopy = apk("old", code = 2, installed = 1).copy(name = "Notes-old.apk", packageName = "app.notes")
        val newest = apk("new", code = 5, installed = 1).copy(name = "Notes-new.apk", packageName = "app.notes")
        val tiedLater = apk("tie", code = 5, installed = 1).copy(name = "Notes-tie.apk", packageName = "app.notes")
        val files = listOf(olderCopy, newest, tiedLater)
        assertEquals(listOf("tie"), ApkBatch.offered(files).map { it.id })
        assertEquals(listOf("old", "new"), ApkBatch.hidden(files).map { it.id })
        assertEquals(listOf("tie"), ApkBatch.actionable(files).map { it.id })
        assertEquals("0 of 1 installed", ApkBatch.summary(files))
        assertEquals(1, ApkBatch.attentionCount(files))
        assertEquals("Failed: duplicate older APK", ApkBatch.statusLine(olderCopy, duplicate = true))
        assertEquals(emptyList<String>(), ApkRowActions.labels(olderCopy, duplicate = true))
        val behind = apk("behind", code = 1, installed = 8)
        assertEquals(ApkBatch.OLDER, ApkBatch.status(behind))
        assertEquals(ApkBatch.REASON_OLDER, ApkBatch.explanation(behind))
        assertTrue(ApkBatch.actionable(listOf(behind)).isEmpty())
    }

    @Test
    fun statusLineNamesTheReasonAndInstallingIsVisible() {
        val installing = apk(code = 9, installed = 1).copy(installMark = "installing")
        assertEquals(ApkBatch.INSTALLING, ApkBatch.status(installing))
        val settled = apk(code = 9, installed = 9).copy(installMark = "installing")
        assertEquals(ApkBatch.INSTALLED, ApkBatch.status(settled))
        assertEquals(ApkBatch.INSTALLING, ApkBatch.statusLine(installing))
        val mismatch = apk("m", code = 9, installed = 1).copy(signatureMatch = ApkSignatures.DIFFER)
        assertEquals(ApkBatch.UPDATE, ApkBatch.statusLine(mismatch))
        assertEquals(listOf(ApkRowActions.INSTALL), ApkRowActions.labels(mismatch))
        val blocked = apk("b", code = 9, installed = null).copy(
            installMark = "needs-tap",
            installDetail = ApkBatch.REASON_UNKNOWN_APPS
        )
        assertEquals("Needs your tap: Install unknown apps is off", ApkBatch.statusLine(blocked))
        assertEquals(listOf(ApkRowActions.ALLOW), ApkRowActions.labels(blocked))
        val failed = apk("f", code = 9, installed = 1).copy(installMark = "failed", installDetail = ApkBatch.REASON_STORAGE)
        assertEquals("Failed: not enough storage", ApkBatch.statusLine(failed))
        assertEquals("install was cancelled", ApkBatch.detailFor(InstallOutcome.ABORTED))
        assertEquals(ApkBatch.REASON_SIGNATURE, ApkBatch.detailFor(InstallOutcome.CONFLICT))
        val files = listOf(installing, apk("done", code = 2, installed = 2))
        assertEquals("Installing. 1 of 2 installed", ApkBatch.summary(files))
        assertEquals(emptyList<String>(), ApkRowActions.labels(installing))
        assertEquals("Installing.", InstallStatuses.plain(InstallOutcome.PENDING, null))
        assertTrue(!InstallStatuses.plain(InstallOutcome.PENDING, null).contains("confirm"))
    }

    private fun apk(id: String = "1", code: Long, installed: Long?) = ReceiveFile(
        id = id,
        name = "Notes.apk",
        kind = "apk",
        path = "/inbox/Notes.apk",
        appName = "Notes",
        packageName = "app.$id",
        incomingVersion = "1.4",
        incomingCode = code,
        installedVersion = if (installed == null) "" else "1.2",
        installedCode = installed ?: -1,
        hasInstalled = installed != null
    )
}
