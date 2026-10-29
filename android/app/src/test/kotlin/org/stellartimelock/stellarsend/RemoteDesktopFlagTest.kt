package org.stellartimelock.stellarsend

import android.content.ComponentName
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.stellartimelock.send.PcScreenActivity
import org.stellartimelock.send.RemoteDesktopBuild
import org.stellartimelock.send.core.CompanionServices
import org.stellartimelock.send.core.RemoteControl
import org.stellartimelock.send.core.RemoteDesktopGate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteDesktopFlagTest {
    @Test
    fun versionCodeIsTheSingleBuild() {
        assertEquals("1.5.63", BuildConfig.VERSION_NAME)
        assertTrue(BuildConfig.REMOTE_DESKTOP)
        assertEquals(73, BuildConfig.VERSION_CODE)
    }

    @Test
    fun flagHidesRemoteDesktopOnPlayAndLabelsItBetaOtherwise() {
        if (BuildConfig.REMOTE_DESKTOP) installDesktopWords()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        assertEquals(BuildConfig.REMOTE_DESKTOP, RemoteDesktopBuild.enabled(activity))

        val label = activity.getString(R.string.remote_desktop_label)
        val line = activity.getString(R.string.remote_desktop_line)
        val lead = activity.getString(R.string.settings_lead)
        val phoneDetail = activity.getString(R.string.phone_control_detail)
        val channel = activity.getString(org.stellartimelock.send.R.string.channel_remote)
        val serviceDescription = activity.getString(org.stellartimelock.send.R.string.remote_control_service_description)
        val home = activity.findViewById<View>(R.id.transferPage)
        val settings = activity.findViewById<View>(R.id.settingsPage)
        activity.findViewById<View>(R.id.openSettings).performClick()

        if (!BuildConfig.REMOTE_DESKTOP) {
            assertEquals("", label)
            assertFalse(mentionsDesktop(label))
            assertFalse(mentionsDesktop(line))
            assertFalse(mentionsDesktop(lead))
            assertFalse(mentionsDesktop(phoneDetail))
            assertFalse(mentionsDesktop(channel))
            assertFalse(mentionsDesktop(serviceDescription))
            assertEquals(View.GONE, activity.findViewById<View>(R.id.remoteDesktopCard).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.controlPc).visibility)
            assertFalse(activity.findViewById<View>(R.id.controlPc).isEnabled)
            assertFalse(activity.findViewById<View>(R.id.openRemoteDesktop).isEnabled)
            assertNoDesktopCopy(home)
            assertNoDesktopCopy(settings)
            shadowOf(activity).clearNextStartedActivities()
            activity.findViewById<Button>(R.id.controlPc).performClick()
            activity.findViewById<Button>(R.id.openRemoteDesktop).performClick()
            val started = shadowOf(activity).nextStartedActivity
            assertTrue(started == null || started.component?.className != PcScreenActivity::class.java.name)
            assertFalse(
                RemoteDesktopGate.allow(
                    false,
                    CompanionServices.REMOTE_CONTROL,
                    RemoteControl.OP_REQUEST,
                    RemoteControl.FROM_PHONE
                )
            )
            assertFalse(
                RemoteDesktopGate.allow(
                    false,
                    CompanionServices.REMOTE_CONTROL,
                    RemoteControl.OP_STATUS,
                    RemoteControl.FROM_PHONE
                )
            )
            assertFalse(
                RemoteDesktopGate.allow(
                    false,
                    CompanionServices.REMOTE_CONTROL,
                    RemoteControl.OP_WATCH,
                    RemoteControl.FROM_PHONE
                )
            )
        } else {
            assertEquals("Remote desktop", label)
            assertEquals(RemoteDesktopGate.LINE, line)
            assertTrue(mentionsDesktop(lead))
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.remoteDesktopCard).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.controlPc).visibility)
            assertEquals("Remote desktop", activity.findViewById<Button>(R.id.controlPc).text.toString())
            assertEquals("Remote desktop", activity.findViewById<Button>(R.id.openRemoteDesktop).text.toString())
            assertEquals("Remote desktop", activity.findViewById<TextView>(R.id.remoteDesktopTitle).text.toString())
            val open = activity.findViewById<Button>(R.id.openRemoteDesktop)
            open.isEnabled = true
            open.performClick()
            val next = shadowOf(activity).nextStartedActivity
            assertEquals(PcScreenActivity::class.java.name, next?.component?.className)
        }
    }

    @Test
    fun manifestMatchesTheFlag() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val info = activity.packageManager.getApplicationInfo(activity.packageName, PackageManager.GET_META_DATA)
        assertEquals(BuildConfig.REMOTE_DESKTOP, info.metaData.getBoolean(RemoteDesktopBuild.META, false))

        val desktop = runCatching {
            activity.packageManager.getActivityInfo(
                ComponentName(activity, PcScreenActivity::class.java),
                0
            )
        }.getOrNull()
        if (BuildConfig.REMOTE_DESKTOP) {
            assertTrue(desktop != null)
        } else {
            assertNull(desktop)
        }

        val service = runCatching {
            activity.packageManager.getServiceInfo(
                ComponentName(activity, "org.stellartimelock.send.RemoteControlService"),
                0
            )
        }.getOrNull()
        assertTrue(service != null)
    }

    private fun installDesktopWords() {
        Class.forName("org.stellartimelock.send.DesktopWords").getMethod("install").invoke(null)
    }

    private fun mentionsDesktop(value: String): Boolean = value.contains("remote desktop", ignoreCase = true)

    private fun assertNoDesktopCopy(root: View) {
        if (root is TextView) {
            assertFalse(mentionsDesktop(root.text?.toString().orEmpty()))
            assertFalse(mentionsDesktop(root.contentDescription?.toString().orEmpty()))
        } else if (root.contentDescription != null) {
            assertFalse(mentionsDesktop(root.contentDescription.toString()))
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) assertNoDesktopCopy(root.getChildAt(i))
        }
    }
}
