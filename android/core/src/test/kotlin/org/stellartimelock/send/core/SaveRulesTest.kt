package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveRulesTest {
    @Test
    fun saveFilesKeepBackupsAndBlockAMuchSmallerReplacement() {
        val directory = File(System.getProperty("java.io.tmpdir"), "ss-save-" + System.nanoTime())
        val file = File(directory, "settings.json")
        val original = "a".repeat(80).toByteArray()
        assertTrue(DurableFile.write(file, original, allowShrink = false))
        assertTrue(DurableFile.write(file, "b".repeat(80).toByteArray(), allowShrink = false))
        assertTrue(DurableFile.backup(file, 1).isFile)
        assertFalse(DurableFile.write(file, ByteArray(0), allowShrink = false))
        assertEquals(80, file.length())
        assertFalse(DurableFile.write(file, "tiny".toByteArray(), allowShrink = false))
        assertTrue(DurableFile.write(file, "tiny".toByteArray(), allowShrink = true))
        assertEquals("tiny", file.readText())
        DurableFile.deleteAll(file)
        assertFalse(file.exists())
        assertFalse(DurableFile.backup(file, 1).exists())
    }

    @Test
    fun datesUseMonthDayYear() {
        val formatted = UsDate.format(1_759_000_000_000)
        assertTrue(Regex("""\d{2}/\d{2}/\d{4}""").matches(formatted))
    }

    @Test
    fun shortCodeIsEightCharactersFromTheFingerprint() {
        val fingerprint = "abcd" + "e".repeat(60)
        assertEquals("ABCD-EEEE", ShortCode.format(fingerprint))
        assertTrue(ShortCode.looksLike("abcd-eeee"))
        assertTrue(ShortCode.matches("ABCD EEEE", fingerprint))
        assertFalse(ShortCode.looksLike("stellarsend://pair?v=1"))
        assertFalse(ShortCode.matches("ffff-ffff", fingerprint))
    }

    @Test
    fun pairingBeaconOmitsTheCodeUntilAWindowIsOpen() {
        val id = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")
        val plain = BeaconCodec.encode(id, 53321, "Desk").toString(Charsets.UTF_8)
        assertFalse(plain.contains("\"code\""))
        val parsed = BeaconCodec.tryParse(BeaconCodec.encode(id, 53321, "Desk", "ABCD-EEEE"))
        assertEquals("ABCD-EEEE", parsed!!.getString("code"))
    }

    @Test
    fun folderArchiveRoundTripsAndRejectsZipSlip() {
        val root = File(System.getProperty("java.io.tmpdir"), "ss-folder-" + System.nanoTime())
        val notes = File(root, "Notes").apply { mkdirs() }
        File(notes, "a.txt").writeText("hello")
        File(notes, "sub").mkdirs()
        File(File(notes, "sub"), "b.txt").writeText("there")
        val zip = File(root, "Notes.stellarsend-folder.zip")
        FolderArchive.pack(notes, zip)
        assertTrue(FolderArchive.isBundle(zip.name))
        val inbox = File(root, "inbox")
        val unpacked = FolderArchive.unpack(zip, inbox)
        assertEquals("hello", File(unpacked, "a.txt").readText())
        assertEquals("there", File(File(unpacked, "sub"), "b.txt").readText())

        val evil = File(root, "evil.stellarsend-folder.zip")
        ZipOutputStream(evil.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("../outside.txt"))
            zipOut.write("nope".toByteArray())
            zipOut.closeEntry()
        }
        assertThrows(java.io.IOException::class.java) { FolderArchive.unpack(evil, inbox) }
        assertFalse(File(root, "outside.txt").exists())
    }

    @Test
    fun anEmptySaveBlocksAppReplacement() {
        val directory = File(System.getProperty("java.io.tmpdir"), "ss-apk-" + System.nanoTime())
        directory.mkdirs()
        val identity = File(directory, "identity.bin")
        identity.writeBytes(ByteArray(40))
        assertNull(SaveFileCheck.problem(listOf(identity)))
        identity.writeBytes(ByteArray(0))
        assertTrue(SaveFileCheck.problem(listOf(identity))!!.contains("empty"))
    }

    @Test
    fun resumeMarkerMatchesOnlyTheSameHash() {
        val directory = File(System.getProperty("java.io.tmpdir"), "ss-resume-" + System.nanoTime())
        directory.mkdirs()
        val partial = File(directory, "note.txt.partial")
        partial.writeBytes(ByteArray(1000))
        val sha = "ab".repeat(32)
        ResumeFiles.preserve(partial, sha, 1000)
        assertEquals(1000, ResumeFiles.match(partial, sha, 5000))
        assertEquals(0, ResumeFiles.match(partial, "cd".repeat(32), 5000))
        ResumeFiles.discard(partial)
        val keyed = File(directory, "transfer-id.partial")
        keyed.writeBytes(ByteArray(1000))
        ResumeFiles.preserve(keyed, sha, 1000, "transfer-1", 5000)
        assertEquals(1000, ResumeFiles.match(keyed, sha, 5000, "transfer-1"))
        assertEquals(0, ResumeFiles.match(keyed, sha, 5000, "transfer-2"))
        assertEquals(0, ResumeFiles.match(keyed, sha, 4000, "transfer-1"))
        ResumeFiles.discard(keyed)
        assertFalse(partial.exists())
    }
}
