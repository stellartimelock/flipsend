package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PhoneSaveFolderTest {
    @Test
    fun canonicalPathIsBackupsThenFlipSend() {
        val root = File("/storage/emulated/0")
        assertEquals(
            File(File(root, "StellarTimeLock backups"), "FlipSend"),
            PhoneSaveFolder.canonical(root)
        )
        assertEquals("StellarTimeLock backups/FlipSend", PhoneSaveFolder.LABEL)
    }

    @Test
    fun oldLooseFoldersAreListedAndNeverCreatedByLookup() {
        val root = File(System.getProperty("java.io.tmpdir"), "stl-old-" + System.nanoTime())
        try {
            assertTrue(root.mkdirs())
            assertNull(PhoneSaveFolder.findExistingOld(root))
            val flip = File(root, "FlipSend")
            assertTrue(flip.mkdirs())
            assertEquals(flip, PhoneSaveFolder.findExistingOld(root))
            assertFalse(File(root, "StellarTimeLock backups").exists())
            assertFalse(File(root, "Download/StellarSend").exists())
            assertFalse(File(root, "Documents/Stellar").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exportPromptFiresWhenTheFolderExistsButCannotBeWritten() {
        assertTrue(
            PhoneSaveFolder.needsExportPrompt(
                alreadyPrompted = false, hasPersistedTree = false, fileExportWritable = false
            )
        )
        assertFalse(
            PhoneSaveFolder.needsExportPrompt(
                alreadyPrompted = true, hasPersistedTree = false, fileExportWritable = false
            )
        )
        assertFalse(
            PhoneSaveFolder.needsExportPrompt(
                alreadyPrompted = false, hasPersistedTree = true, fileExportWritable = false
            )
        )
        assertFalse(
            PhoneSaveFolder.needsExportPrompt(
                alreadyPrompted = false, hasPersistedTree = false, fileExportWritable = true
            )
        )
    }

    @Test
    fun mediaStoreRejectsTheTopLevelBackupsFolderOnAndroid15() {
        assertFalse(PhoneSaveFolder.mediaStoreRelativePathAllowed(PhoneSaveFolder.LABEL))
        assertFalse(PhoneSaveFolder.mediaStoreRelativePathAllowed("StellarTimeLock backups/FlipSend/"))
        assertTrue(PhoneSaveFolder.mediaStoreRelativePathAllowed("Download/FlipSend"))
        assertTrue(PhoneSaveFolder.mediaStoreRelativePathAllowed("Documents/StellarTimeLock backups/FlipSend"))
    }

    @Test
    fun treeChildSegmentsPointAtFlipSendUnderBackups() {
        assertEquals(emptyList<String>(), PhoneSaveFolder.treeChildSegments("FlipSend", "StellarTimeLock backups"))
        assertEquals(emptyList<String>(), PhoneSaveFolder.treeChildSegments("FlipSend", null))
        assertEquals(listOf("FlipSend"), PhoneSaveFolder.treeChildSegments("StellarTimeLock backups", null))
        assertEquals(
            listOf("StellarTimeLock backups", "FlipSend"),
            PhoneSaveFolder.treeChildSegments("primary", null)
        )
    }

    @Test
    fun normalizePickedUsesFlipSendUnderBackups() {
        val root = File(System.getProperty("java.io.tmpdir"), "stl-norm-" + System.nanoTime())
        try {
            val backups = File(root, PhoneSaveFolder.ROOT_NAME).apply { mkdirs() }
            val flip = File(backups, PhoneSaveFolder.APP_NAME)
            assertEquals(flip, PhoneSaveFolder.normalizePicked(backups))
            assertTrue(flip.mkdirs())
            assertEquals(flip, PhoneSaveFolder.normalizePicked(flip))
            assertTrue(PhoneSaveFolder.isInsideAppFolder(File(flip, "Notes.apk"), root))
            assertFalse(PhoneSaveFolder.isInsideAppFolder(File(root, "FlipSend/Notes.apk"), root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ensureWritableCreatesOnlyTheGivenFolder() {
        val root = File(System.getProperty("java.io.tmpdir"), "stl-write-" + System.nanoTime())
        try {
            val dest = PhoneSaveFolder.canonical(root)
            assertTrue(PhoneSaveFolder.ensureWritable(dest))
            assertTrue(dest.isDirectory)
            assertFalse(File(root, "FlipSend").exists())
            assertFalse(File(root, "Documents").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ensureWritableRejectsAPathThatCannotCreateFiles() {
        val file = File.createTempFile("stl-not-dir", ".tmp")
        try {
            assertFalse(PhoneSaveFolder.ensureWritable(file))
        } finally {
            file.delete()
        }
    }
}
