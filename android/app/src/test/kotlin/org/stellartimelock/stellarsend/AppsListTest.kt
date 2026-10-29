package org.stellartimelock.stellarsend

import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.stellartimelock.send.AppsActivity
import org.stellartimelock.send.ReceivedLog
import org.stellartimelock.send.core.ApkBatch
import org.stellartimelock.send.core.ApkSignatures
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class AppsListTest {
    @Test
    fun mainScreenAppsButtonShowsTheAttentionCountAndOpensTheList() {
        val context = RuntimeEnvironment.getApplication()
        val notes = apk(context, "Notes.apk")
        val old = apk(context, "Maps-old.apk")
        val neu = apk(context, "Maps-new.apk")
        val log = ReceivedLog(context)
        val notesEntry = log.add(notes, "apk", 1_000)
        log.noteApp(notesEntry.id, "Notes", "app.notes", "2.0", 20, "1.0", 10, true, ApkSignatures.DIFFER)
        log.mark(notesEntry.id, "failed", ApkBatch.REASON_SIGNATURE)
        val oldEntry = log.add(old, "apk", 2_000)
        log.noteApp(oldEntry.id, "Maps", "app.maps", "1.0", 1, "", -1, false, "")
        val newEntry = log.add(neu, "apk", 3_000)
        log.noteApp(newEntry.id, "Maps", "app.maps", "3.0", 3, "", -1, false, "")
        log.mark(newEntry.id, "needs-tap", ApkBatch.REASON_UNKNOWN_APPS)

        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val apps = activity.findViewById<Button>(R.id.tabApps)
        assertEquals(View.VISIBLE, apps.visibility)
        assertEquals("Apps", apps.text.toString())
        assertEquals("Apps", apps.contentDescription.toString())
        apps.performClick()
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(AppsActivity::class.java.name, next.component?.className)

        val list = Robolectric.buildActivity(AppsActivity::class.java, Intent().putExtra(AppsActivity.EXTRA_ATTENTION, true))
            .create().get()
        val content = list.findViewById<View>(android.R.id.content)
        assertEquals("Apps", list.findViewById<TextView>(org.stellartimelock.send.R.id.apps_title).text.toString())
        assertTrue(findText(content, "Notes") != null)
        assertTrue(findText(content, "Failed: signature mismatch") != null)
        assertTrue(findButton(content, "Clean replace") == null)
        assertTrue(findText(content, "Needs your tap: Install unknown apps is off") != null)
        assertTrue(findButton(content, "Allow installs") != null)
        assertTrue(findText(content, "Older copies hidden") != null)
        assertTrue(findText(content, "Failed: duplicate older APK") != null)
        findButton(content, "Allow installs")!!.performClick()
        val settings = shadowOf(list).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.action)
    }

    private fun apk(context: android.content.Context, name: String): File {
        val file = File(context.filesDir, "inbox/$name")
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        return file
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
}
