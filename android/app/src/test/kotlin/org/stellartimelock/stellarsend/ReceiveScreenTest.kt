package org.stellartimelock.stellarsend

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.stellartimelock.send.ReceiveLive
import org.stellartimelock.send.ReceivingActivity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class ReceiveScreenTest {
    @Test
    fun receivingShowsOneStatusAndAMovingBar() {
        ReceiveLive.clear()
        ReceiveLive.edit {
            noteSender("Windows PC")
            noteProgress("t1", "One.apk", 10, 10, batchIndex = 1, batchCount = 7)
            noteFile("1", "One.apk", "apk", "/inbox/One.apk")
            noteIdle()
            noteProgress("t3", "AppName.apk", 40, 80, batchIndex = 3, batchCount = 7)
        }
        val activity = Robolectric.buildActivity(ReceivingActivity::class.java).create().start().get()
        val status = activity.findViewById<TextView>(org.stellartimelock.send.R.id.receive_status)
        val sender = activity.findViewById<TextView>(org.stellartimelock.send.R.id.receive_sender)
        val bar = activity.findViewById<ProgressBar>(org.stellartimelock.send.R.id.receive_bar)
        assertEquals("Receiving 3 of 7: AppName.apk", status.text.toString())
        assertEquals("From Windows PC", sender.text.toString())
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals(50, bar.progress)
        val content = activity.findViewById<View>(android.R.id.content)
        assertNull(findButton(content, "Install"))
        assertNull(findButton(content, "Clean"))
        assertNull(findButton(content, "Clean replace"))
        assertNull(findButton(content, "Install all"))
        assertNull(findText(content, "Needs your tap"))
        assertNull(findText(content, "Waiting for the next file"))
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, activity.resources.displayMetrics)
        walk(content) { view ->
            if (view is TextView && view.visibility == View.VISIBLE && !view.text.isNullOrBlank()) {
                assertTrue("${view.text} is smaller than body text", view.textSize + 0.5f >= minPx)
            }
        }
        ReceiveLive.clear()
    }

    @Test
    fun idleAfterABatchDoesNotAskForATap() {
        ReceiveLive.clear()
        ReceiveLive.edit {
            noteSender("Windows PC")
            noteFile(
                "7",
                "Events.apk",
                "apk",
                "/inbox/Events.apk",
                appName = "Events",
                packageName = "app.events",
                incomingVersion = "1.0.2",
                incomingCode = 3,
                installedVersion = "1.0.0",
                installedCode = 1,
                hasInstalled = true
            )
            noteIdle()
        }
        val activity = Robolectric.buildActivity(ReceivingActivity::class.java).create().start().get()
        val status = activity.findViewById<TextView>(org.stellartimelock.send.R.id.receive_status)
        val bar = activity.findViewById<ProgressBar>(org.stellartimelock.send.R.id.receive_bar)
        val content = activity.findViewById<View>(android.R.id.content)
        assertEquals("Received 1 app", status.text.toString())
        assertEquals(View.GONE, bar.visibility)
        assertNull(findText(content, "Waiting for the next file"))
        assertNull(findText(content, "Needs your tap"))
        assertNull(findText(content, "Tap for details"))
        assertNull(findButton(content, "Install"))
        assertNull(findButton(content, "Clean"))
        ReceiveLive.clear()
    }

    @Test
    fun photosCanStillBeOpenedAndApksDoNotGetClean() {
        ReceiveLive.clear()
        ReceiveLive.edit {
            noteFile(
                "9",
                "Notes.apk",
                "apk",
                "/inbox/Notes.apk",
                appName = "Notes",
                packageName = "app.notes",
                incomingVersion = "1.4",
                incomingCode = 12,
                installedVersion = "1.2",
                installedCode = 8,
                hasInstalled = true
            )
            noteFile("11", "Picture.png", "file", "/inbox/Picture.png")
            noteIdle()
        }
        val activity = Robolectric.buildActivity(ReceivingActivity::class.java).create().start().get()
        val content = activity.findViewById<View>(android.R.id.content)
        assertEquals(
            "Received 2 files",
            activity.findViewById<TextView>(org.stellartimelock.send.R.id.receive_status).text.toString()
        )
        assertTrue(findText(content, "Picture.png") != null)
        assertTrue(findButton(content, "Open") != null)
        assertNull(findText(content, "Notes"))
        assertNull(findButton(content, "Install"))
        assertNull(findButton(content, "Clean replace"))
        ReceiveLive.clear()
    }

    @Test
    fun anEmptyScreenIsReadyNotWaiting() {
        ReceiveLive.clear()
        val activity = Robolectric.buildActivity(ReceivingActivity::class.java).create().get()
        val status = activity.findViewById<TextView>(org.stellartimelock.send.R.id.receive_status)
        val bar = activity.findViewById<ProgressBar>(org.stellartimelock.send.R.id.receive_bar)
        assertEquals("Ready to receive", status.text.toString())
        assertTrue(status.text.toString().isNotBlank())
        assertEquals(View.GONE, bar.visibility)
        assertNull(findText(activity.findViewById(android.R.id.content), "Waiting for the next file"))
        ReceiveLive.clear()
    }

    @Test
    fun installingShowsAMovingProgressBar() {
        ReceiveLive.clear()
        ReceiveLive.edit {
            noteSender("Windows PC")
            noteFile(
                "1",
                "Notes.apk",
                "apk",
                "/inbox/Notes.apk",
                appName = "Notes",
                packageName = "app.notes",
                incomingVersion = "2.0",
                incomingCode = 20,
                installedVersion = "1.0",
                installedCode = 10,
                hasInstalled = true
            )
            noteInstall("1", "installing")
            noteIdle()
            noteInstallProgress("Notes", 25, 100)
        }
        val activity = Robolectric.buildActivity(ReceivingActivity::class.java).create().start().get()
        val status = activity.findViewById<TextView>(org.stellartimelock.send.R.id.receive_status)
        val bar = activity.findViewById<ProgressBar>(org.stellartimelock.send.R.id.receive_bar)
        assertEquals("Installing Notes · 25%", status.text.toString())
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals(25, bar.progress)
        assertTrue(!bar.isIndeterminate)
        ReceiveLive.clear()
    }

    private fun findButton(root: View, label: String): Button? {
        if (root is Button && root.text?.toString() == label) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findButton(root.getChildAt(i), label)?.let { return it }
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

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
        }
    }
}
