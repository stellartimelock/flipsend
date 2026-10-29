package org.stellartimelock.stellarsend

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.stellartimelock.send.PhoneInbox
import org.stellartimelock.send.ReceivedLog
import org.stellartimelock.send.core.PhoneSaveFolder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneInboxTest {
    @Test
    fun receiveUsesPrivateInboxWhenExportFolderIsNotWritable() {
        val context = RuntimeEnvironment.getApplication()
        val blocked = File(context.cacheDir, "blocked-export").apply { mkdirs() }
        blocked.setWritable(false)
        context.getSharedPreferences("stellarsend", Context.MODE_PRIVATE)
            .edit()
            .putString("phone_save_path", blocked.absolutePath)
            .apply()

        val dir = PhoneInbox.directory(context)
        val expected = File(context.filesDir, "inbox")
        assertEquals(expected.canonicalFile, dir.canonicalFile)
        assertEquals("inbox", dir.name)

        val apk = File(dir, "Notes.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            assertFalse(PhoneInbox.maybeExport(context, apk))
            assertTrue(apk.isFile)
            val entry = ReceivedLog(context).add(apk, "apk", 1_000)
            assertEquals(apk.canonicalFile, ReceivedLog(context).file(entry).canonicalFile)
        } finally {
            apk.delete()
            File(context.filesDir, "received.json").delete()
            blocked.setWritable(true)
            blocked.deleteRecursively()
        }
    }

    @Test
    fun settingsLabelNamesTheExportFolder() {
        assertEquals("StellarTimeLock backups/FlipSend", PhoneInbox.label())
    }

    @Test
    fun needsPromptWhenExportFolderExistsButIsNotWritableAndNoTree() {
        val context = RuntimeEnvironment.getApplication()
        val blocked = File(context.cacheDir, "blocked-export-prompt").apply { writeText("not-a-folder") }
        context.getSharedPreferences("stellarsend", Context.MODE_PRIVATE)
            .edit()
            .putString("phone_save_path", blocked.absolutePath)
            .putBoolean("phone_save_folder_prompted", true)
            .putBoolean("phone_export_tree_prompted", false)
            .apply()
        try {
            assertTrue(PhoneInbox.needsPrompt(context))
        } finally {
            blocked.delete()
        }
    }

    @Test
    fun rememberingASafTreeSuppressesThePrompt() {
        val context = RuntimeEnvironment.getApplication()
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AStellarTimeLock%20backups%2FFlipSend")
        PhoneInbox.acceptPickedTree(context, tree)
        assertEquals(tree.toString(), PhoneInbox.exportTree(context))
        assertFalse(PhoneInbox.needsPrompt(context))
    }
}
