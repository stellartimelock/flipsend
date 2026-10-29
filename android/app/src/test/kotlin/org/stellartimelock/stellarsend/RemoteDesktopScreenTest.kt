package org.stellartimelock.stellarsend

import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.stellartimelock.send.PcScreenActivity
import org.stellartimelock.send.core.PhoneControlChecklist

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteDesktopScreenTest {
    @Test
    fun remoteDesktopDoesNotOpenAndroidSettings() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        if (!BuildConfig.REMOTE_DESKTOP) {
            assertEquals(View.GONE, activity.findViewById<View>(R.id.controlPc).visibility)
            shadowOf(activity).clearNextStartedActivities()
            activity.findViewById<Button>(R.id.controlPc).performClick()
            val started = shadowOf(activity).nextStartedActivity
            assertTrue(started == null || started.component?.className != PcScreenActivity::class.java.name)
            return
        }
        val button = activity.findViewById<Button>(R.id.controlPc)
        button.isEnabled = true
        button.performClick()
        val next = shadowOf(activity).nextStartedActivity
        assertNotNull(next)
        assertEquals(PcScreenActivity::class.java.name, next.component?.className)
        assertNotEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, next.action)
        assertNotEquals(PhoneControlChecklist.GENERIC_PAGE, next.action)
        assertNotEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, next.action)
    }

    @Test
    fun phoneControlOpensTheServicePageOnly() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        activity.findViewById<View>(R.id.openSettings).performClick()
        activity.findViewById<Button>(R.id.openAccessibility).performClick()
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(PhoneControlChecklist.DETAILS_PAGE, next.action)
        assertEquals(
            activity.packageName + "/org.stellartimelock.send.RemoteControlService",
            next.getStringExtra(PhoneControlChecklist.COMPONENT_EXTRA)
        )
        assertNotEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, next.action)

        shadowOf(activity).clearNextStartedActivities()
        activity.findViewById<Button>(R.id.openRestricted).performClick()
        val info = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, info.action)
        assertEquals("package:" + activity.packageName, info.data?.toString())
    }
}
