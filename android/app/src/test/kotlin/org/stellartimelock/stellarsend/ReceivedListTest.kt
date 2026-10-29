package org.stellartimelock.stellarsend

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.stellartimelock.send.ReceiveLive
import org.stellartimelock.send.ReceivedActivity
import org.stellartimelock.send.ReceivedLog
import org.stellartimelock.send.core.ApkSignatures
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class ReceivedListTest {

    @Test
    fun emptyListShowsPlaceholder() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val activity = Robolectric.buildActivity(ReceivedActivity::class.java).create().start().get()
        val content = activity.findViewById<View>(android.R.id.content)
        assertNotNull(findText(content, "No files yet. When your computer sends something, it shows up here."))
        assertNull(findButton(content, "Install all updates"))
    }

    @Test
    fun updateTagAppearsForNewerApkAndInstallAllButtonIsVisible() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val log = ReceivedLog(context)
        val apk = apkFile(context, "Notes-2.apk")
        val entry = log.add(apk, "apk", 1_000)
        log.noteApp(entry.id, "Notes", "app.notes", "2.0", 20, "1.0", 10, hasInstalled = true, signatureMatch = ApkSignatures.MATCH)

        val activity = Robolectric.buildActivity(ReceivedActivity::class.java).create().start().get()
        val content = activity.findViewById<View>(android.R.id.content)
        assertNotNull("Should show app name Notes", findText(content, "Notes"))
        assertNotNull("Should show Update tag", findText(content, "Update"))
        assertNotNull("Install all updates button should be visible", findButton(content, "Install all updates"))
        assertNull("Should not show Older tag for a newer APK", findText(content, "Older"))
    }

    @Test
    fun installedTagAppearsForSameVersionAndClearButtonIsVisible() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val log = ReceivedLog(context)
        val apk = apkFile(context, "Maps-3.apk")
        val entry = log.add(apk, "apk", 2_000)
        log.noteApp(entry.id, "Maps", "app.maps", "3.0", 30, "3.0", 30, hasInstalled = true, signatureMatch = ApkSignatures.MATCH)

        val activity = Robolectric.buildActivity(ReceivedActivity::class.java).create().start().get()
        val content = activity.findViewById<View>(android.R.id.content)
        assertNotNull("Should show app name Maps", findText(content, "Maps"))
        assertNotNull("Should show Installed tag", findText(content, "Installed"))
        assertNotNull("Should offer Clear installed button", findButton(content, "Clear installed"))
        assertNull("Install all updates should not appear when no updates", findButton(content, "Install all updates"))
    }

    @Test
    fun notInstalledTagAppearsForFirstInstall() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val log = ReceivedLog(context)
        val apk = apkFile(context, "Keyboard-1.apk")
        val entry = log.add(apk, "apk", 3_000)
        log.noteApp(entry.id, "Keyboard", "app.keyboard", "1.0", 1, "", -1, hasInstalled = false, signatureMatch = "")

        val activity = Robolectric.buildActivity(ReceivedActivity::class.java).create().start().get()
        val content = activity.findViewById<View>(android.R.id.content)
        assertNotNull("Should show app name Keyboard", findText(content, "Keyboard"))
        assertNotNull("Should show Not installed tag", findText(content, "Not installed"))
        assertNotNull("Install all updates should appear for not-installed apps", findButton(content, "Install all updates"))
    }

    @Test
    fun olderApkIsHiddenBehindToggle() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val log = ReceivedLog(context)
        val apk = apkFile(context, "Browser-old.apk")
        val entry = log.add(apk, "apk", 1_500)
        log.noteApp(entry.id, "Browser", "app.browser", "1.0", 1, "3.0", 30, hasInstalled = true, signatureMatch = ApkSignatures.MATCH)

        val activity = Robolectric.buildActivity(ReceivedActivity::class.java).create().start().get()
        val content = activity.findViewById<View>(android.R.id.content)
        // Should NOT show the Older tag row directly
        assertNull("Older APK should be hidden by default", findText(content, "Older"))
        // Should show the toggle button
        assertNotNull("Toggle button should appear for older APKs", findButton(content, "Show 1 older APK"))
        // Install all updates should not include older APKs
        assertNull("Install all updates should not appear for only-older APKs", findButton(content, "Install all updates"))
    }

    @Test
    fun updatesSortedBeforeInstalledAndNotInstalled() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val log = ReceivedLog(context)

        val installed = apkFile(context, "AppA.apk")
        val eA = log.add(installed, "apk", 1_000)
        log.noteApp(eA.id, "AppA", "app.a", "2.0", 20, "2.0", 20, hasInstalled = true, signatureMatch = ApkSignatures.MATCH)

        val update = apkFile(context, "AppB.apk")
        val eB = log.add(update, "apk", 2_000)
        log.noteApp(eB.id, "AppB", "app.b", "4.0", 40, "3.0", 30, hasInstalled = true, signatureMatch = ApkSignatures.MATCH)

        val activity = Robolectric.buildActivity(ReceivedActivity::class.java).create().start().get()
        val content = activity.findViewById<View>(android.R.id.content)

        // Both should be visible
        assertNotNull(findText(content, "AppA"))
        assertNotNull(findText(content, "AppB"))
        // Update status should appear
        assertNotNull(findText(content, "Update"))
        // Installed status should appear
        assertNotNull(findText(content, "Installed"))
        // AppB (Update) should appear before AppA (Installed)
        val updateIdx = textIndex(content, "Update")
        val installedIdx = textIndex(content, "Installed")
        assertTrue("Update should appear before Installed in the list", updateIdx < installedIdx)
    }

    @Test
    fun oneRowPerPackageWhenMultipleVersionsReceived() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val log = ReceivedLog(context)

        // Two entries for the same package (simulating no pruning yet on old data)
        val old = apkFile(context, "App-v1.apk")
        val eOld = log.add(old, "apk", 1_000)
        log.noteApp(eOld.id, "MyApp", "app.myapp", "1.0", 1, "1.0", 1, hasInstalled = true, signatureMatch = ApkSignatures.MATCH)

        val neu = apkFile(context, "App-v3.apk")
        val eNew = log.add(neu, "apk", 2_000)
        log.noteApp(eNew.id, "MyApp", "app.myapp", "3.0", 3, "1.0", 1, hasInstalled = true, signatureMatch = ApkSignatures.MATCH)

        val activity = Robolectric.buildActivity(ReceivedActivity::class.java).create().start().get()
        val content = activity.findViewById<View>(android.R.id.content)

        // Only one Update row, not two
        val updateCount = countText(content, "Update")
        assertEquals("Should show exactly one Update row per package", 1, updateCount)
        // Shows the newer version
        assertNotNull("Should show version 3.0", findText(content, "3.0 (3) · installed 1.0 (1)"))
    }

    @Test
    fun pruneOlderApksRemovesOlderLogEntryAndKeepsNewest() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val log = ReceivedLog(context)

        val old = apkFile(context, "App-v1.apk")
        val eOld = log.add(old, "apk", 1_000)
        log.noteApp(eOld.id, "MyApp", "app.myapp", "1.0", 1, "", -1, hasInstalled = false, signatureMatch = "")

        val neu = apkFile(context, "App-v3.apk")
        val eNew = log.add(neu, "apk", 2_000)
        log.noteApp(eNew.id, "MyApp", "app.myapp", "3.0", 3, "", -1, hasInstalled = false, signatureMatch = "")

        val pruned = log.pruneOlderApks("app.myapp")
        assertEquals("pruneOlderApks should return exactly one older entry", 1, pruned.size)
        assertEquals("The older entry (v1) should be pruned", eOld.id, pruned[0].id)

        val remaining = log.list()
        assertEquals("Only the newer entry should remain in the log", 1, remaining.size)
        assertEquals(eNew.id, remaining[0].id)
    }

    @Test
    fun pruneOlderApkDeletesFileFromDisk() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val log = ReceivedLog(context)

        val old = apkFile(context, "App-v1.apk")
        val eOld = log.add(old, "apk", 1_000)
        log.noteApp(eOld.id, "MyApp", "app.myapp", "1.0", 1, "", -1, hasInstalled = false, signatureMatch = "")

        val neu = apkFile(context, "App-v3.apk")
        val eNew = log.add(neu, "apk", 2_000)
        log.noteApp(eNew.id, "MyApp", "app.myapp", "3.0", 3, "", -1, hasInstalled = false, signatureMatch = "")

        val pruned = log.pruneOlderApks("app.myapp")
        for (p in pruned) runCatching { log.file(p).delete() }

        assertFalse("Old APK file should be deleted from disk", old.exists())
        assertTrue("Newer APK file should still be on disk", neu.exists())
    }

    @Test
    fun sweepOlderCopiesClearsLeftoverRowsForEveryPackage() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val log = ReceivedLog(context)
        val a1 = apkFile(context, "A-1.apk")
        val a2 = apkFile(context, "A-2.apk")
        val b1 = apkFile(context, "B-1.apk")
        val eA1 = log.add(a1, "apk", 1_000)
        log.noteApp(eA1.id, "A", "app.a", "1.0", 1, "", -1, false, "")
        val eA2 = log.add(a2, "apk", 2_000)
        log.noteApp(eA2.id, "A", "app.a", "2.0", 2, "", -1, false, "")
        val eB1 = log.add(b1, "apk", 3_000)
        log.noteApp(eB1.id, "B", "app.b", "1.0", 1, "", -1, false, "")

        val pruned = log.sweepOlderCopies()
        for (old in pruned) runCatching { log.file(old).delete() }
        assertEquals(1, pruned.size)
        assertEquals(eA1.id, pruned[0].id)
        assertFalse(a1.exists())
        assertTrue(a2.exists())
        assertTrue(b1.exists())
        assertEquals(2, log.list().size)
    }

    @Test
    fun sweepInboxFilesDeletesAnOlderCopyThatIsOnlyOnDisk() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val log = ReceivedLog(context)
        val old = apkFile(context, "Notes-1.apk")
        val neu = apkFile(context, "Notes-9.apk")
        val entry = log.add(neu, "apk", 2_000)
        log.noteApp(entry.id, "Notes", "app.notes", "9.0", 9, "", -1, false, "")

        val dropped = log.sweepInboxFiles { file ->
            if (file.name.contains("1")) "app.notes" to 1L else "app.notes" to 9L
        }
        assertEquals(1, dropped.size)
        assertEquals(old.name, dropped[0].name)
        assertFalse(old.exists())
        assertTrue(neu.exists())
    }

    @Test
    fun clearInstalledDeletesFileAndRemovesLogEntry() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val log = ReceivedLog(context)

        val apk = apkFile(context, "Notes-3.apk")
        val entry = log.add(apk, "apk", 1_000)
        log.noteApp(entry.id, "Notes", "app.notes", "3.0", 30, "3.0", 30, hasInstalled = true, signatureMatch = "match")

        assertTrue("APK file should exist before clearing", apk.exists())

        val removed = log.clearApkEntry(entry.id)
        assertNotNull("clearApkEntry should return the removed entry", removed)
        runCatching { log.file(removed!!).delete() }

        assertFalse("APK file should be deleted after clearing", apk.exists())
        assertTrue("Log should be empty after clearing", log.list().isEmpty())
    }

    @Test
    fun installingRowShowsAProgressBar() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        ReceiveLive.clear()
        val log = ReceivedLog(context)
        val apk = apkFile(context, "Press-2.apk")
        val entry = log.add(apk, "apk", 4_000)
        log.noteApp(entry.id, "Press", "app.press", "2.0", 20, "1.0", 10, hasInstalled = true, signatureMatch = ApkSignatures.MATCH)
        log.mark(entry.id, "installing", "")
        ReceiveLive.edit { noteInstallProgress("Press", 40, 100) }

        val controller = Robolectric.buildActivity(ReceivedActivity::class.java).create().start()
        val activity = controller.get()
        val content = activity.findViewById<View>(android.R.id.content)
        assertNotNull(findText(content, org.stellartimelock.send.core.ReceiveStatus.installingLine("Press", 40)))
        val bar = findProgress(content, "Install progress bar for Press")
        assertNotNull(bar)
        assertEquals(View.VISIBLE, bar!!.visibility)
        assertEquals(40, bar.progress)
        assertFalse(bar.isIndeterminate)
        controller.pause().destroy()
        ReceiveLive.clear()
    }

    @Test
    fun nonApkFilesStillAppear() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val log = ReceivedLog(context)
        val txt = File(context.filesDir, "inbox/note.txt").also { it.parentFile?.mkdirs(); it.writeText("hello") }
        log.add(txt, "text", 5_000)

        val activity = Robolectric.buildActivity(ReceivedActivity::class.java).create().start().get()
        val content = activity.findViewById<View>(android.R.id.content)
        assertNotNull("Text file should appear in the list", findText(content, "note.txt"))
        assertNotNull("Open button should appear for text file", findButton(content, "Open"))
    }

    @Test
    fun aLongFileHistoryPagesInsteadOfInflatingEveryRow() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val log = ReceivedLog(context)
        repeat(45) { index ->
            val file = File(context.filesDir, "inbox/photo-$index.jpg").also {
                it.parentFile?.mkdirs()
                it.writeBytes(byteArrayOf(1, 2, 3))
            }
            log.add(file, "file", 1_000L + index)
        }
        val activity = Robolectric.buildActivity(ReceivedActivity::class.java).create().start().get()
        val content = activity.findViewById<View>(android.R.id.content)
        assertNotNull(findText(content, "photo-44.jpg"))
        assertNotNull(findText(content, "photo-25.jpg"))
        assertNull("The first page should not inflate the whole history", findText(content, "photo-0.jpg"))
        val more = findButton(content, org.stellartimelock.send.core.ListWindow.SHOW_MORE)
        assertNotNull(more)
        more!!.performClick()
        assertNotNull(findText(activity.findViewById(android.R.id.content), "photo-5.jpg"))
        assertNull(findText(activity.findViewById(android.R.id.content), "photo-0.jpg"))
        findButton(activity.findViewById(android.R.id.content), org.stellartimelock.send.core.ListWindow.SHOW_MORE)!!.performClick()
        assertNotNull(findText(activity.findViewById(android.R.id.content), "photo-0.jpg"))
        assertNull(findButton(activity.findViewById(android.R.id.content), org.stellartimelock.send.core.ListWindow.SHOW_MORE))
    }

    @Test
    fun allTextIsMeetMinimumBodySize() {
        val context = RuntimeEnvironment.getApplication()
        clearLog(context)
        val log = ReceivedLog(context)
        val apk = apkFile(context, "Check.apk")
        val entry = log.add(apk, "apk", 1_000)
        log.noteApp(entry.id, "Check App", "app.check", "1.0", 1, "1.0", 1, hasInstalled = true, signatureMatch = ApkSignatures.MATCH)

        val activity = Robolectric.buildActivity(ReceivedActivity::class.java).create().start().get()
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, activity.resources.displayMetrics)
        val sectionPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, activity.resources.displayMetrics)
        walk(activity.findViewById(android.R.id.content)) { view ->
            if (view is TextView && !view.text.isNullOrBlank()) {
                if (kotlin.math.abs(view.textSize - sectionPx) < 1.5f) return@walk
                assertTrue("'${view.text}' is smaller than 16sp body text", view.textSize + 0.5f >= minPx)
            }
        }
    }

    // --- helpers ---

    private fun clearLog(context: android.content.Context) {
        File(context.filesDir, "received.json").delete()
        File(context.filesDir, "inbox").deleteRecursively()
        File(context.filesDir, "inbox").mkdirs()
        val dest = org.stellartimelock.send.PhoneInbox.directory(context)
        if (dest.canonicalPath != File(context.filesDir, "inbox").canonicalPath) {
            dest.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    private fun apkFile(context: android.content.Context, name: String): File {
        val file = File(context.filesDir, "inbox/$name")
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        return file
    }

    private fun findButton(root: View, label: String): Button? {
        if (root is Button && root.text?.toString() == label && root.visibility == View.VISIBLE) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findButton(root.getChildAt(i), label)?.let { return it }
            }
        }
        return null
    }

    private fun findProgress(root: View, description: String): ProgressBar? {
        if (root is ProgressBar && root.contentDescription?.toString() == description) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findProgress(root.getChildAt(i), description)?.let { return it }
            }
        }
        return null
    }

    private fun findText(root: View, value: String): TextView? {
        if (root is TextView && root.text?.toString() == value) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findText(root.getChildAt(i), value)?.let { return it }
            }
        }
        return null
    }

    private fun countText(root: View, value: String): Int {
        var count = 0
        if (root is TextView && root.text?.toString() == value) count++
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) count += countText(root.getChildAt(i), value)
        }
        return count
    }

    private fun textIndex(root: View, value: String): Int {
        var index = 0
        fun search(v: View): Int {
            if (v is TextView && v.text?.toString() == value) return index
            index++
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val r = v.getChildAt(i)?.let { search(it) } ?: -1
                    if (r >= 0) return r
                }
            }
            return -1
        }
        return search(root)
    }

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
        }
    }
}
