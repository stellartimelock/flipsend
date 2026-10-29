package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path
import java.util.TimeZone

class SaveStoreTests {
    @TempDir
    lateinit var directory: Path

    @Test
    fun emptySaveAndUnexpectedFileNeedConfirmation() {
        val file = directory.resolve("notes.json").toFile()
        val full = """{"v":1,"items":[{"id":1},{"id":2},{"id":3}]}""".toByteArray()
        val created = SaveStore.commit(file, full, confirmed = false)
        assertEquals(OverwriteKind.ALLOW, created.kind)
        assertEquals(3, created.incomingItems)

        val empty = """{"v":1,"items":[]}""".toByteArray()
        val blocked = SaveStore.commit(file, empty, confirmed = false)
        assertEquals(OverwriteKind.NEEDS_CONFIRM, blocked.kind)
        assertEquals(3, blocked.existingItems)
        assertEquals(0, blocked.incomingItems)
        assertFalse(blocked.unexpected)
        assertTrue(blocked.reason.contains("empty", ignoreCase = true))
        assertTrue(file.readBytes().contentEquals(full))

        val big = ByteArray(full.size * 20) { 'X'.code.toByte() }
        val unexpected = SaveStore.commit(file, big, confirmed = false)
        assertEquals(OverwriteKind.NEEDS_CONFIRM, unexpected.kind)
        assertTrue(unexpected.unexpected)
        assertTrue(unexpected.reason.contains("larger", ignoreCase = true))
        assertEquals(3, unexpected.existingItems)
        assertTrue(file.readBytes().contentEquals(full))
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun confirmedReplaceKeepsABackupAndOlderSavesStillOpen() {
        val file = directory.resolve("notes.json").toFile()
        val legacy = """[{"id":"a"},{"id":"b"}]""".toByteArray()
        file.writeBytes(legacy)
        val opened = SaveDocuments.inspect(file.readBytes())
        assertTrue(opened.recognized)
        assertEquals(2, opened.itemCount)
        assertEquals(null, opened.version)

        val replacement = UserPreferencesCodec.write(UserPreferences("10.0.0.2", "Phone"))
        val written = SaveStore.commit(file, replacement, confirmed = false)
        assertEquals(OverwriteKind.ALLOW, written.kind)
        val backup = File(file.path + ".bak1")
        assertTrue(backup.isFile)
        assertTrue(backup.readBytes().contentEquals(legacy))
        assertEquals(2, SaveDocuments.inspect(backup.readBytes()).itemCount)
        assertEquals("10.0.0.2", UserPreferencesCodec.read(file.readBytes()).manualHost)
        assertEquals("Phone", UserPreferencesCodec.read(file.readBytes()).deviceName)

        val empty = """{"v":1,"items":[]}""".toByteArray()
        val replaced = SaveStore.commit(file, empty, confirmed = true)
        assertEquals(OverwriteKind.ALLOW, replaced.kind)
        assertEquals(0, SaveDocuments.inspect(file.readBytes()).itemCount)
        val restored = SaveStore.restore(file, 1, confirmed = true)
        assertEquals(OverwriteKind.ALLOW, restored.kind)
        assertEquals("10.0.0.2", UserPreferencesCodec.read(file.readBytes()).manualHost)
        assertTrue(file.isFile)
    }

    @Test
    fun aSimilarUpdateIsAllowedAndAMuchSmallerSaveIsNot() {
        val current = """{"v":1,"items":[{"id":1},{"id":2},{"id":3},{"id":4}]}""".toByteArray()
        val grown = """{"v":1,"items":[{"id":1},{"id":2},{"id":3},{"id":4},{"id":5}]}""".toByteArray()
        assertEquals(OverwriteKind.ALLOW, SaveStore.decide(current, grown).kind)
        val smaller = """{"v":1,"items":[{"id":1}]}""".toByteArray()
        val decision = SaveStore.decide(current, smaller)
        assertEquals(OverwriteKind.NEEDS_CONFIRM, decision.kind)
        assertEquals(4, decision.existingItems)
        assertEquals(1, decision.incomingItems)
    }

    @Test
    fun legacyPreferencesOpenAndPasswordsAreNotWrittenBack() {
        val flat = """{"manual_host":"10.0.0.9","device_name":"Phone","password":"secret"}""".toByteArray()
        val prefs = UserPreferencesCodec.read(flat)
        assertEquals("10.0.0.9", prefs.manualHost)
        assertEquals("Phone", prefs.deviceName)
        val written = UserPreferencesCodec.write(prefs).toString(Charsets.UTF_8)
        assertFalse(written.contains("secret"))
        assertFalse(written.contains("password"))
        val list = """[{"k":"manualHost","v":"10.0.0.8"}]""".toByteArray()
        assertEquals("10.0.0.8", UserPreferencesCodec.read(list).manualHost)
        assertEquals(1, SaveDocuments.inspect(list).itemCount)
    }

    @Test
    fun datesUseUsMonthDayAndTwelveHourTime() {
        val millis = java.time.Instant.parse("2026-10-02T19:04:00Z").toEpochMilli()
        assertEquals("10/02/2026 3:04 PM", UsDates.format(millis, TimeZone.getTimeZone("America/New_York")))
    }
}

class ApkReplaceTests {
    @Test
    fun previewShowsTheSaveAndWarnsWhenItIsMissing() {
        val present = SaveSummary(
            name = "com.example.app.json",
            exists = true,
            modifiedMillis = 1L,
            modifiedDisplay = "10/02/2026 3:04 PM",
            itemCount = 4,
            byteSize = 80
        )
        val preview = ApkReplacePreview.preview(
            apkName = "app-release.apk",
            incoming = ApkPackageInfo("com.example.app", "1.3", 13),
            installed = ApkPackageInfo("com.example.app", "1.2", 12),
            save = present,
            userFolderLabel = "Documents/Stellar",
            selfPackage = "org.stellartimelock.stellarsend"
        )
        assertFalse(preview.uninstallFirst)
        assertTrue(preview.offerCleanReplace)
        assertFalse(preview.saveMissing)
        assertFalse(ApkReplacePreview.deletesSaveFiles())
        assertTrue(preview.body.contains("com.example.app.json"))
        assertTrue(preview.body.contains("10/02/2026 3:04 PM"))
        assertTrue(preview.body.contains("4 items"))
        assertTrue(preview.body.contains("not deleted"))
        assertTrue(preview.body.contains("restores its data"))

        val missing = ApkReplacePreview.preview(
            apkName = "app-release.apk",
            incoming = ApkPackageInfo("com.example.app", "1.3", 13),
            installed = ApkPackageInfo("com.example.app", "1.2", 12),
            save = present.copy(exists = false, modifiedDisplay = null, itemCount = null, byteSize = 0),
            userFolderLabel = "Documents/Stellar",
            selfPackage = "org.stellartimelock.stellarsend"
        )
        assertTrue(missing.saveMissing)
        assertTrue(missing.body.contains("Warning"))
        assertTrue(missing.body.contains("com.example.app.json"))
        assertTrue(missing.body.contains("Documents/Stellar"))

        val self = ApkReplacePreview.preview(
            apkName = "stellarsend.apk",
            incoming = ApkPackageInfo("org.stellartimelock.stellarsend", "1.1.0", 3),
            installed = ApkPackageInfo("org.stellartimelock.stellarsend", "1.0.1", 2),
            save = present.copy(name = "org.stellartimelock.stellarsend.json"),
            userFolderLabel = "Documents/Stellar",
            selfPackage = "org.stellartimelock.stellarsend"
        )
        assertFalse(self.uninstallFirst)
        assertFalse(self.offerCleanReplace)
        assertTrue(self.body.contains("one install step"))
        assertTrue(self.body.contains("not deleted"))
        assertTrue(ApkReplacePreview.isApk("Folder/App.APK"))
        assertFalse(ApkReplacePreview.isApk("notes.apk.txt"))
        assertEquals("com.example.app.json", ApkReplacePreview.saveFileName("com.example.app"))
    }

    @Test
    fun matchingSignaturesUpdateInPlaceAndAMismatchOrAChoiceCleans() {
        val incoming = ApkPackageInfo("com.example.app", "1.3", 13, certificates = listOf("aa"))
        val installed = ApkPackageInfo("com.example.app", "1.2", 12, certificates = listOf("AA"))
        val save = SaveSummary("com.example.app.json", true, 1L, "10/02/2026 3:04 PM", 4, 80)
        val match = ApkReplacePreview.preview(
            "app-release.apk", incoming, installed, save, "Documents/Stellar",
            "org.stellartimelock.stellarsend", signatureMatch = ApkSignatures.MATCH
        )
        assertFalse(match.uninstallFirst)
        assertTrue(match.offerCleanReplace)
        assertTrue(match.body.contains("in place"))

        val differ = ApkReplacePreview.preview(
            "app-release.apk", incoming, installed, save, "Documents/Stellar",
            "org.stellartimelock.stellarsend", signatureMatch = ApkSignatures.DIFFER
        )
        assertTrue(differ.uninstallFirst)
        assertFalse(differ.offerCleanReplace)
        assertTrue(differ.body.contains("Clean replace"))
        assertTrue(differ.body.contains("not deleted"))

        val picked = ApkReplacePreview.preview(
            "app-release.apk", incoming, installed, save, "Documents/Stellar",
            "org.stellartimelock.stellarsend", signatureMatch = ApkSignatures.MATCH, preferClean = true
        )
        assertTrue(picked.uninstallFirst)
        assertFalse(picked.offerCleanReplace)

        val fresh = ApkReplacePreview.preview(
            "app-release.apk", incoming, null, save, "Documents/Stellar",
            "org.stellartimelock.stellarsend", signatureMatch = ApkSignatures.DIFFER, preferClean = true
        )
        assertFalse(fresh.uninstallFirst)
        assertFalse(fresh.offerCleanReplace)
        assertTrue(fresh.body.contains("No uninstall is needed"))

        assertFalse(ApkReplacePreview.offerAfterFailure(alreadyInstalled = false, self = false, wasCleanReplace = false))
        assertFalse(ApkReplacePreview.offerAfterFailure(alreadyInstalled = true, self = true, wasCleanReplace = false))
        assertFalse(ApkReplacePreview.offerAfterFailure(alreadyInstalled = true, self = false, wasCleanReplace = true))
        assertTrue(ApkReplacePreview.offerAfterFailure(alreadyInstalled = true, self = false, wasCleanReplace = false))
        assertFalse(ApkReplacePreview.deletesSaveFiles())
    }

    @Test
    fun certificateSetsMatchIgnoreCaseAndBlankMeansUnknown() {
        assertEquals("", ApkSignatures.compare(emptyList(), listOf("AA")))
        assertEquals("", ApkSignatures.compare(listOf("  "), listOf("aa")))
        assertEquals(ApkSignatures.MATCH, ApkSignatures.compare(listOf(" AA "), listOf("aa")))
        assertEquals(ApkSignatures.MATCH, ApkSignatures.compare(listOf("BB", "aa"), listOf("aa", "bb")))
        assertEquals(ApkSignatures.DIFFER, ApkSignatures.compare(listOf("aa", "bb"), listOf("aa")))
    }

    @Test
    fun receiveRowsOfferCleanReplaceOnlyWhenTheAppIsAlreadyInstalled() {
        val fresh = ReceiveFile(
            "1", "Notes.apk", "apk", "/inbox/Notes.apk",
            packageName = "app.notes", incomingCode = 12, hasInstalled = false
        )
        assertEquals(listOf(ApkRowActions.INSTALL), ApkRowActions.labels(fresh))

        val update = fresh.copy(hasInstalled = true, installedCode = 8, signatureMatch = ApkSignatures.MATCH)
        assertEquals(listOf(ApkRowActions.INSTALL), ApkRowActions.labels(update))
        assertEquals(listOf(ApkRowActions.INSTALL), ApkRowActions.labels(update.copy(signatureMatch = "")))
        assertEquals(listOf(ApkRowActions.INSTALL), ApkRowActions.labels(update.copy(signatureMatch = ApkSignatures.DIFFER)))
        assertEquals(emptyList<String>(), ApkRowActions.labels(update.copy(installMark = "success")))
        assertEquals(emptyList<String>(), ApkRowActions.labels(update.copy(incomingCode = 3, installedCode = 8)))
        assertEquals(
            listOf(ApkRowActions.INSTALL),
            ApkRowActions.labels(update.copy(packageName = "org.stellartimelock.stellarsend"), "org.stellartimelock.stellarsend")
        )
        assertEquals(listOf("Open"), ApkRowActions.labels(ReceiveFile("2", "notes.txt", "text", "/inbox/notes.txt")))
        assertTrue(ReceivedHistory.keepsAppData())
    }

    @Test
    fun installErrorsStayShortAndPlain() {
        assertEquals("Install was cancelled.", InstallStatuses.plain(InstallOutcome.ABORTED, "user aborted"))
        assertEquals("The APK is not valid.", InstallStatuses.plain(InstallOutcome.INVALID, null))
        assertEquals("Installed.", InstallStatuses.plain(InstallOutcome.SUCCESS, "ok"))
        val leaked = InstallStatuses.plain(InstallOutcome.FAILED, "see {\"password\":\"secret\"}")
        assertEquals("The install did not finish. [redacted]", leaked)
        assertFalse(leaked.contains("secret"))
    }
}

class PaymentPolicyTests {
    @Test
    fun feesAreXlmWithTenPercentToTheDevelopmentFund() {
        assertTrue(PaymentPolicy.accepts("xlm"))
        assertFalse(PaymentPolicy.accepts("USD"))
        assertFalse(PaymentPolicy.mayStorePassword("secret"))
        val split = PaymentPolicy.split(BigDecimal("10.0000000"))
        assertEquals(BigDecimal("1.0000000"), split.developmentFund)
        assertEquals(BigDecimal("9.0000000"), split.recipient)
        assertEquals("XLM", split.asset)
        assertEquals("Chime", split.payout)
        assertEquals(PaymentPolicy.DEVELOPMENT_FUND_SHARE, BigDecimal("0.10"))
    }
}
