package org.stellartimelock.stellarsend

import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.stellartimelock.send.AppsActivity
import org.stellartimelock.send.ReceiveLive
import org.stellartimelock.send.ReceivedActivity
import org.stellartimelock.send.ReceivedLog
import org.stellartimelock.send.ReceivingActivity
import org.stellartimelock.send.SendToPcActivity
import org.stellartimelock.send.TextViewerActivity
import org.stellartimelock.send.core.ApkSignatures
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class ScreenFitTest {
    @Test
    fun homeAndSettingsFitANormalPhoneWidth() {
        SavedCommands.save(
            RuntimeEnvironment.getApplication(),
            listOf("powershell -NoProfile -Command Get-ChildItem C:\\Users\\user\\Documents")
        )
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val home = activity.findViewById<View>(android.R.id.content)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transferPage).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsPage).visibility)
        assertTrue(findText(home, "Send to iPhone") != null)
        assertTrue(findText(home, "Apps") != null)
        assertTrue(findText(home, "Settings") != null)
        assertTrue(findText(home, "Send text") != null)
        assertTrue(findText(home, "Send copied text") != null)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.homeSendCard).visibility)
        layout(home)
        assertFits(home)
        assertBodyAtLeast16sp(home)

        activity.findViewById<View>(R.id.openSettings).performClick()
        val content = activity.findViewById<View>(android.R.id.content)
        layout(content)
        assertFits(content)
        assertBodyAtLeast16sp(content)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsPage).visibility)
        if (BuildConfig.REMOTE_DESKTOP) {
            assertTrue(findText(content, "Remote desktop") != null)
            assertTrue(findText(content, "Remote desktop (beta)") == null)
        } else {
            assertTrue(findText(content, "Remote desktop") == null)
            assertTrue(findText(content, "Remote desktop (beta)") == null)
        }
        assertTrue(findText(content, "Check phone screen") != null)
        assertTrue(findText(content, "Cast to TV") != null)
        assertTrue(findText(content, "Turn on phone control") != null)

        activity.findViewById<View>(R.id.tabTerminal).performClick()
        layout(content)
        assertFits(content)
        assertBodyAtLeast16sp(content)
    }

    @Test
    fun receivedFileButtonsStackInsteadOfOverflowing() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "received.json").delete()
        val inbox = File(context.filesDir, "inbox").apply { mkdirs() }
        val longName = "very-long-received-filename-that-should-wrap-on-a-normal-phone-width.txt"
        val file = File(inbox, longName).apply { writeText("hello") }
        ReceivedLog(context).add(file, "text", 5_000)

        val activity = Robolectric.buildActivity(ReceivedActivity::class.java).create().start().get()
        val content = activity.findViewById<View>(android.R.id.content)
        layout(content)
        assertFits(content)
        assertBodyAtLeast16sp(content)
        assertTrue(findText(content, "Install unknown apps") != null)
        assertTrue(findText(content, "Show in folder") != null || findText(content, longName) != null)
        assertTrue(findText(content, longName) != null)
    }

    @Test
    fun appsAndReceivingActionButtonsFit() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "received.json").delete()
        val apk = File(context.filesDir, "inbox/Notes.apk").also {
            it.parentFile?.mkdirs()
            it.writeBytes(byteArrayOf(1, 2, 3))
        }
        val log = ReceivedLog(context)
        val entry = log.add(apk, "apk", 1_000)
        log.noteApp(entry.id, "Notes With A Long Display Name", "app.notes", "2.0", 20, "1.0", 10, true, ApkSignatures.MATCH)

        val apps = Robolectric.buildActivity(AppsActivity::class.java).create().get()
        val appsContent = apps.findViewById<View>(android.R.id.content)
        layout(appsContent)
        assertFits(appsContent)
        assertBodyAtLeast16sp(appsContent)
        assertTrue(findText(appsContent, "Install") != null)
        assertTrue(findText(appsContent, "Clean replace") == null)

        ReceiveLive.clear()
        ReceiveLive.edit {
            noteFile(
                "9",
                "Notes.apk",
                "apk",
                apk.absolutePath,
                appName = "Notes With A Long Display Name",
                packageName = "app.notes",
                incomingVersion = "2.0",
                incomingCode = 20,
                installedVersion = "1.0",
                installedCode = 10,
                hasInstalled = true
            )
            noteIdle()
        }
        val receiving = Robolectric.buildActivity(ReceivingActivity::class.java).create().start().get()
        val receiveContent = receiving.findViewById<View>(android.R.id.content)
        layout(receiveContent)
        assertFits(receiveContent)
        assertBodyAtLeast16sp(receiveContent)
        ReceiveLive.clear()
    }

    @Test
    fun sendScreensUseBodySizeAndFit() {
        val send = Robolectric.buildActivity(
            SendToPcActivity::class.java,
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "a-very-long-note-name-that-should-wrap.txt")
            }
        ).create().get()
        val sendContent = send.findViewById<View>(android.R.id.content)
        layout(sendContent)
        assertFits(sendContent)
        assertBodyAtLeast16sp(sendContent)

        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "received.json").delete()
        val note = File(context.filesDir, "inbox/notes.txt").also {
            it.parentFile?.mkdirs()
            it.writeText("hello from the computer")
        }
        val entry = ReceivedLog(context).add(note, "text", 6_000)
        val viewer = Robolectric.buildActivity(
            TextViewerActivity::class.java,
            Intent().putExtra(TextViewerActivity.EXTRA_ID, entry.id)
        ).create().get()
        val viewerContent = viewer.findViewById<View>(android.R.id.content)
        layout(viewerContent)
        assertFits(viewerContent)
        assertBodyAtLeast16sp(viewerContent)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 1.3f)
    fun largeTextStillFitsTheHomeScreen() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val content = activity.findViewById<View>(android.R.id.content)
        layout(content)
        assertFits(content)
        assertBodyAtLeast16sp(content)
        activity.findViewById<View>(R.id.openSettings).performClick()
        layout(content)
        assertFits(content)
        assertBodyAtLeast16sp(content)
    }

    private fun layout(view: View) {
        val metrics = view.resources.displayMetrics
        view.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun assertFits(root: View) {
        val failures = mutableListOf<String>()
        fun walk(parent: ViewGroup, allowWide: Boolean) {
            val rightLimit = parent.width - parent.paddingRight
            val leftLimit = parent.paddingLeft
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child.visibility == View.GONE) continue
                if (!allowWide && parent.width > 0 && child.width > 0) {
                    if (child.right > rightLimit + 1) {
                        failures += "\"${label(child)}\" right ${child.right} past ${rightLimit} in ${parent.javaClass.simpleName}"
                    }
                    if (child.left < leftLimit - 1) {
                        failures += "\"${label(child)}\" left ${child.left} before ${leftLimit} in ${parent.javaClass.simpleName}"
                    }
                }
                if (child is ViewGroup) {
                    walk(child, allowWide || child is HorizontalScrollView)
                }
            }
        }
        if (root is ViewGroup) walk(root, false)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun assertBodyAtLeast16sp(root: View) {
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, root.resources.displayMetrics)
        val sectionPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, root.resources.displayMetrics)
        walk(root) { view ->
            if (view is TextView && view.visibility == View.VISIBLE && !view.text.isNullOrBlank()) {
                if (kotlin.math.abs(view.textSize - sectionPx) < 1.5f) return@walk
                assertTrue(
                    "'${view.text}' is smaller than 16sp body text (${view.textSize})",
                    view.textSize + 0.5f >= minPx
                )
            }
        }
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

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
        }
    }

    private fun label(view: View): String = when (view) {
        is TextView -> view.text?.toString().orEmpty().ifBlank { view.javaClass.simpleName }
        else -> view.javaClass.simpleName
    }
}
