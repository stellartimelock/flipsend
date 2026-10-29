package org.stellartimelock.stellarsend

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Button
import com.google.android.material.switchmaterial.SwitchMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.stellartimelock.send.BackgroundListen
import org.stellartimelock.send.BootReceiver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundServiceTest {

    /** Write the listen pref directly so we never call sync() and never touch the Engine. */
    private fun setListenPref(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences("stellarsend", Context.MODE_PRIVATE)
            .edit().putBoolean("listen_in_background", value).apply()
    }

    @Test
    fun listenToggleDefaultIsOn() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        assertTrue("Stay connected should be on by default", BackgroundListen.enabled(activity))
        val switch = activity.findViewById<SwitchMaterial>(R.id.listenBackground)
        assertTrue(switch.isChecked)
    }

    @Test
    fun listenToggleLabelMatchesSpec() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val switch = activity.findViewById<SwitchMaterial>(R.id.listenBackground)
        assertEquals("Stay connected in background", switch.text.toString())
    }

    @Test
    fun listenPrefCanBeWrittenAndRead() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        setListenPref(activity, false)
        assertFalse(BackgroundListen.enabled(activity))
        setListenPref(activity, true)
        assertTrue(BackgroundListen.enabled(activity))
    }

    @Test
    fun bootReceiverStartsServiceWhenEnabled() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        setListenPref(activity, true)

        val receiver = BootReceiver()
        receiver.onReceive(activity.applicationContext, Intent(Intent.ACTION_BOOT_COMPLETED))

        val started = Shadows.shadowOf(activity.application).nextStartedService
        assertNotNull("Service should be started at boot", started)
    }

    @Test
    fun bootReceiverDoesNotStartServiceWhenDisabled() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        setListenPref(activity, false)

        val receiver = BootReceiver()
        receiver.onReceive(activity.applicationContext, Intent(Intent.ACTION_BOOT_COMPLETED))

        val started = Shadows.shadowOf(activity.application).nextStartedService
        assertNull("Service must not start when disabled", started)
    }

    @Test
    fun packageReplacedReceiverStartsServiceWhenEnabled() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        setListenPref(activity, true)

        val receiver = BootReceiver()
        receiver.onReceive(activity.applicationContext, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val started = Shadows.shadowOf(activity.application).nextStartedService
        assertNotNull("Service should restart after app update", started)
    }

    @Test
    fun batteryOptButtonIsInSettingsPage() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        activity.findViewById<View>(R.id.openSettings).performClick()
        val btn = activity.findViewById<Button>(R.id.batteryOptButton)
        assertNotNull(btn)
        assertEquals("Skip battery restrictions", btn.text.toString())
        assertEquals(View.VISIBLE, btn.visibility)
    }
}
