package org.stellartimelock.stellarsend

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoveOldAppTest {
    @Test
    fun settingsOpensTheScreen() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        activity.findViewById<View>(R.id.openSettings).performClick()
        shadowOf(activity).clearNextStartedActivities()
        val row = activity.findViewById<Button>(R.id.openRemoveOldApp)
        assertEquals(View.VISIBLE, row.visibility)
        assertEquals("Remove old app copy", row.text.toString())
        row.performClick()
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(RemoveOldAppActivity::class.java.name, next.component?.className)
    }

    @Test
    fun screenPrefillsStellarTimeLockAndAsksToUninstallEveryUser() {
        val activity = Robolectric.buildActivity(RemoveOldAppActivity::class.java).create().get()
        val field = activity.findViewById<EditText>(R.id.removeOldPackage)
        val status = activity.findViewById<TextView>(R.id.removeOldStatus)
        assertEquals("com.stellartimelock", field.text.toString())
        assertEquals(RemoveOldApp.NOT_VISIBLE, status.text.toString())
        assertFalse(status.text.toString().contains("not installed"))

        activity.findViewById<Button>(R.id.removeOldCopy).performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_UNINSTALL_PACKAGE, intent.action)
        assertEquals(Uri.parse("package:com.stellartimelock"), intent.data)
        assertTrue(intent.getBooleanExtra(RemoveOldApp.EXTRA_UNINSTALL_ALL_USERS, false))
        assertEquals("android.intent.extra.UNINSTALL_ALL_USERS", RemoveOldApp.EXTRA_UNINSTALL_ALL_USERS)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))

        activity.onUninstallReturned(Activity.RESULT_OK)
        assertEquals(RemoveOldApp.CLEARED, status.text.toString())
        assertEquals("Cleared - install from Play now.", status.text.toString())
        assertFalse(status.text.toString().contains("not installed"))
    }

    @Test
    fun packageFieldIsEditable() {
        val activity = Robolectric.buildActivity(RemoveOldAppActivity::class.java).create().get()
        val field = activity.findViewById<EditText>(R.id.removeOldPackage)
        field.setText("com.example.other")
        shadowOf(activity).clearNextStartedActivities()
        activity.findViewById<Button>(R.id.removeOldCopy).performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Uri.parse("package:com.example.other"), intent.data)
        assertTrue(intent.getBooleanExtra(RemoveOldApp.EXTRA_UNINSTALL_ALL_USERS, false))
    }

    @Test
    fun aBadPackageDoesNotOpenUninstall() {
        val activity = Robolectric.buildActivity(RemoveOldAppActivity::class.java).create().get()
        val field = activity.findViewById<EditText>(R.id.removeOldPackage)
        field.setText("not a package")
        shadowOf(activity).clearNextStartedActivities()
        activity.findViewById<Button>(R.id.removeOldCopy).performClick()
        assertNull(shadowOf(activity).nextStartedActivity)
        val status = activity.findViewById<TextView>(R.id.removeOldStatus).text.toString()
        assertEquals(RemoveOldApp.NOT_VISIBLE, status)
        assertFalse(status.contains("not installed anywhere"))
    }

    @Test
    fun cancelLeavesTheNotVisibleStatus() {
        val activity = Robolectric.buildActivity(RemoveOldAppActivity::class.java).create().get()
        activity.onUninstallReturned(Activity.RESULT_CANCELED)
        val status = activity.findViewById<TextView>(R.id.removeOldStatus).text.toString()
        assertEquals(RemoveOldApp.NOT_VISIBLE, status)
        assertFalse(status.contains("not installed anywhere"))
    }

    @Test
    fun shizukuCommandRemovesEveryUserAndDoesNotKeepData() {
        val args = RemoveOldApp.shizukuArgs("com.stellartimelock").toList()
        assertEquals(listOf("pm", "uninstall", "--user", "all", "com.stellartimelock"), args)
        assertFalse(args.contains("-k"))
        assertFalse(args.any { it.contains("keep") })
    }

    @Test
    fun manifestCanRequestDelete() {
        val activity = Robolectric.buildActivity(RemoveOldAppActivity::class.java).create().get()
        val info = activity.packageManager.getPackageInfo(
            activity.packageName,
            PackageManager.GET_PERMISSIONS
        )
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.REQUEST_DELETE_PACKAGES"))
    }
}
