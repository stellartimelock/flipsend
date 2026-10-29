package org.stellartimelock.stellarsend

import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.TextView
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
import org.robolectric.annotation.Config
import org.stellartimelock.send.core.HomeStatus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class HomeScreenTest {
    @Test
    fun firstLoadIsAHomePageNotASettingsDump() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val send = activity.findViewById<Button>(R.id.sendToIphone)
        val apps = activity.findViewById<Button>(R.id.tabApps)
        val settings = activity.findViewById<Button>(R.id.openSettings)
        val settingsPage = activity.findViewById<View>(R.id.settingsPage)
        val home = activity.findViewById<View>(R.id.transferPage)
        val pairing = activity.findViewById<View>(R.id.pairingCard)
        val status = activity.findViewById<TextView>(R.id.status)

        assertEquals(View.VISIBLE, home.visibility)
        assertEquals(View.GONE, settingsPage.visibility)
        assertEquals("Send to iPhone", send.text.toString())
        assertTrue(isChildOf(settingsPage, send))
        assertEquals(View.VISIBLE, apps.visibility)
        assertEquals("Apps", apps.text.toString())
        assertEquals(View.VISIBLE, settings.visibility)
        assertEquals("···", settings.text.toString())
        assertEquals("More", settings.contentDescription.toString())
        assertNull(findVisibleText(home, "FlipSend by StellarTimeLock"))
        assertNull(findVisibleText(home, "Other apps by this developer"))
        assertEquals(View.VISIBLE, pairing.visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.qr).visibility)
        assertEquals("Enlarge", activity.findViewById<Button>(R.id.showCode).text.toString())
        assertEquals("Send", activity.findViewById<Button>(R.id.addFile).text.toString())
        assertEquals("Check phone screen", activity.findViewById<Button>(R.id.shareScreen).text.toString())
        assertEquals("Received", activity.findViewById<Button>(R.id.openReceived).text.toString())
        assertNotNull(findVisibleText(home, "Show code to PC"))
        assertNotNull(findVisibleText(home, "File to PC"))
        assertNotNull(findVisibleText(home, "Scan a code"))
        assertNotNull(findVisibleText(home, "Type a code"))
        assertNull(findVisibleText(home, "SEND"))
        assertNull(findVisibleText(home, "MORE"))
        assertNotNull(findVisibleText(home, "Relay 0 of 100 MB · Nothing sending"))
        assertNull(findVisibleText(home, "Connections"))
        assertNull(findVisibleText(home, "Relay: 0 of 100 MB used this month"))
        assertNull(findVisibleText(home, "Not supported"))
        assertEquals("Set up Bluetooth", activity.findViewById<Button>(R.id.setupBluetooth).contentDescription.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transfersCard).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.homeAppsCard).visibility)
        assertNotNull(findVisibleText(home, "Beta"))
        if (BuildConfig.REMOTE_DESKTOP) {
            assertNotNull(findVisibleText(home, "Remote desktop"))
        } else {
            assertNull(findVisibleText(home, "Remote desktop"))
        }
        assertNull(findVisibleText(home, "Control PC"))
        assertNull(findVisibleText(home, "Mirror to PC"))
        assertNull(findVisibleText(home, "Share screen"))
        assertNotNull(findVisibleText(home, "StellarTimeLock"))
        assertNotNull(findVisibleText(home, "Play Store"))
        assertEquals(View.GONE, activity.findViewById<TextView>(R.id.homeHint).visibility)
        assertEquals(View.VISIBLE, status.visibility)
        assertEquals(HomeStatus.NOT_CONNECTED, status.text.toString())
        assertNull(findVisibleText(home, "Bot and AI connections"))
        assertNull(findVisibleText(home, "Internet relay address"))
        val sendCard = activity.findViewById<View>(R.id.homeSendCard)
        assertEquals(View.VISIBLE, sendCard.visibility)
        assertTrue(isChildOf(home, sendCard))
        assertTrue(isChildOf(home, activity.findViewById(R.id.sendText)))
        assertTrue(isChildOf(home, activity.findViewById(R.id.sendClipboard)))
        assertTrue(isChildOf(home, activity.findViewById(R.id.addFile)))
        assertTrue(isChildOf(home, activity.findViewById(R.id.shareScreen)))
        assertEquals("Send text", activity.findViewById<Button>(R.id.sendText).text.toString())
        assertEquals("Send copied text", activity.findViewById<Button>(R.id.sendClipboard).text.toString())
        val note = activity.findViewById<android.widget.EditText>(R.id.message)
        assertEquals(View.VISIBLE, note.visibility)
        assertEquals(
            "Add a note with your next send (optional)…",
            note.hint.toString()
        )
        assertTrue(isChildOf(sendCard, note))
        assertNull(findChild(settingsPage, R.id.sendText))
        assertNull(findChild(settingsPage, R.id.message))
        val min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, activity.resources.displayMetrics) - 1
        assertTrue(apps.textSize >= min)
        assertTrue(activity.findViewById<Button>(R.id.addFile).textSize >= min)
        assertTrue(activity.findViewById<Button>(R.id.scanCode).textSize >= min)
        assertTrue(activity.findViewById<Button>(R.id.sendText).textSize >= min)
        assertTrue(activity.findViewById<Button>(R.id.sendClipboard).textSize >= min)
        assertTrue(note.textSize >= min)
        assertEquals(1, status.maxLines)
        val maxWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 160f, activity.resources.displayMetrics)
        assertTrue(status.maxWidth <= maxWidth + 1)
    }

    @Test
    fun settingsOpensARealScreen() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val settingsPage = activity.findViewById<View>(R.id.settingsPage)
        val home = activity.findViewById<View>(R.id.transferPage)
        assertEquals(View.GONE, settingsPage.visibility)
        assertEquals(View.VISIBLE, home.visibility)

        activity.findViewById<View>(R.id.openSettings).performClick()
        assertEquals(View.VISIBLE, settingsPage.visibility)
        assertEquals(View.GONE, home.visibility)
        assertNotNull(findVisibleText(settingsPage, "Settings"))
        if (BuildConfig.REMOTE_DESKTOP) {
            assertNotNull(findVisibleText(settingsPage, "Remote desktop"))
            assertNull(findVisibleText(settingsPage, "Remote desktop (beta)"))
            assertNotNull(findVisibleText(settingsPage, "See your computer's screen and control it from this phone."))
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.controlPc).visibility)
        } else {
            assertNull(findVisibleText(settingsPage, "Remote desktop (beta)"))
            assertNull(findVisibleText(settingsPage, "Remote desktop"))
            assertEquals(View.GONE, activity.findViewById<View>(R.id.remoteDesktopCard).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.controlPc).visibility)
        }
        assertEquals("FlipSend by StellarTimeLock", activity.findViewById<TextView>(R.id.settingsDeveloperBranding).text.toString())
        assertNotNull(findVisibleText(settingsPage, "FlipSend by StellarTimeLock"))
        assertNull(findVisibleText(settingsPage, "Other apps by this developer"))
        assertNull(findVisibleText(settingsPage, "Bot and AI connections"))
        assertNull(findVisibleText(settingsPage, "Choose received folder"))
        assertNotNull(findVisibleText(settingsPage, "Saving into StellarTimeLock backups/FlipSend."))
        val phone = activity.findViewById<SwitchMaterial>(R.id.phoneControl)
        assertEquals("Control this phone", phone.text.toString())
        assertFalse(phone.isChecked)
        assertFalse(org.stellartimelock.send.RemoteControlPrefs.phoneControl(activity))

        activity.findViewById<View>(R.id.settingsBack).performClick()
        assertEquals(View.VISIBLE, home.visibility)
        assertEquals(View.GONE, settingsPage.visibility)
    }

    @Test
    fun mainActionsSitOnTheFirstScreen() {
        assertMainActionsFit(
            Robolectric.buildActivity(MainActivity::class.java).create().get(),
            requireCopiedText = false
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun mainActionsFitA360By780Phone() {
        assertMainActionsFit(Robolectric.buildActivity(MainActivity::class.java).create().get())
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp-xxhdpi")
    fun mainActionsFitA411By915Phone() {
        assertMainActionsFit(Robolectric.buildActivity(MainActivity::class.java).create().get())
    }

    private fun assertMainActionsFit(activity: MainActivity, requireCopiedText: Boolean = true) {
        val home = activity.findViewById<View>(R.id.transferPage)
        val content = activity.findViewById<View>(android.R.id.content)
        val metrics = activity.resources.displayMetrics
        content.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
        )
        content.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        val scan = activity.findViewById<View>(R.id.scanCode)
        val sendText = activity.findViewById<View>(R.id.sendText)
        val send = activity.findViewById<View>(R.id.addFile)
        val received = activity.findViewById<View>(R.id.tileReceived)
        val apps = activity.findViewById<View>(R.id.tileApps)
        val desktop = activity.findViewById<View>(R.id.remoteDesktopHome)
        val phone = activity.findViewById<View>(R.id.shareScreen)
        val copied = activity.findViewById<View>(R.id.sendClipboard)
        val note = activity.findViewById<View>(R.id.message)
        val onScreen = mutableListOf(scan, send, received, apps, phone, sendText)
        if (BuildConfig.REMOTE_DESKTOP) onScreen += desktop
        if (requireCopiedText) onScreen += copied
        onScreen.forEach { view ->
            assertTrue(isChildOf(home, view))
            val bottom = offsetFrom(content, view) + view.height
            assertTrue("${label(view)} bottom $bottom should stay on the first ${metrics.heightPixels}px", bottom in 1..metrics.heightPixels)
            assertTrue("${label(view)} height ${view.height} is under 48dp (${dp(activity, 48f)}px)", view.height + 1 >= dp(activity, 48f))
        }
        fun topOf(view: View) = offsetFrom(content, view)
        assertTrue(topOf(activity.findViewById(R.id.pairingCard)) < topOf(send))
        assertTrue(topOf(send) < topOf(received))
        assertTrue(topOf(received) < topOf(apps))
        if (BuildConfig.REMOTE_DESKTOP) {
            assertTrue(topOf(apps) < topOf(desktop))
            assertTrue(topOf(desktop) < topOf(phone))
        } else {
            assertTrue(topOf(apps) < topOf(phone))
        }
        assertTrue(topOf(phone) < topOf(sendText))
        assertEquals(View.VISIBLE, note.visibility)
        assertTrue(topOf(sendText) < topOf(note))
        assertTrue(topOf(note) < topOf(copied))
    }

    @Test
    fun homeAppsCardPagesInsteadOfInflatingEveryReceivedApk() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        java.io.File(context.filesDir, "received.json").delete()
        val log = org.stellartimelock.send.ReceivedLog(context)
        repeat(12) { index ->
            val apk = java.io.File(context.filesDir, "inbox/Tool-$index.apk").also {
                it.parentFile?.mkdirs()
                it.writeBytes(byteArrayOf(1, 2, 3))
            }
            val entry = log.add(apk, "apk", 2_000L + index)
            log.noteApp(entry.id, "Tool $index", "app.tool$index", "1.0", 1, "", -1, false, "")
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val rows = activity.findViewById<android.widget.LinearLayout>(R.id.homeAppRows)
        assertEquals(org.stellartimelock.send.core.HomeApps.PREVIEW, rows.childCount)
        val more = activity.findViewById<Button>(R.id.homeAppsMore)
        assertEquals(View.VISIBLE, more.visibility)
        try {
            more.performClick()
            assertEquals(org.stellartimelock.send.core.HomeApps.PAGE, rows.childCount)
            assertTrue(rows.childCount < 12)
        } finally {
            java.io.File(context.filesDir, "received.json").delete()
        }
    }

    @Test
    fun pairedHomeShowsSendTextOnTheMainCard() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val sendCard = activity.findViewById<View>(R.id.homeSendCard)
        val home = activity.findViewById<View>(R.id.transferPage)
        assertEquals(View.VISIBLE, home.visibility)
        assertEquals(View.VISIBLE, sendCard.visibility)
        assertNotNull(findVisibleText(home, "Send"))
        assertNotNull(findVisibleText(home, "Send text"))
        assertNotNull(findVisibleText(home, "Send copied text"))
        assertNotNull(findVisibleText(home, "File to PC"))
        assertNotNull(findVisibleText(home, "Check phone screen"))
        val note = activity.findViewById<android.widget.EditText>(R.id.message)
        assertEquals(View.VISIBLE, note.visibility)
        assertEquals(
            "Add a note with your next send (optional)…",
            note.hint.toString()
        )
        assertTrue(isChildOf(sendCard, note))
    }

    private fun isChildOf(parent: View, child: View): Boolean {
        var walk: android.view.ViewParent? = child.parent
        while (walk != null) {
            if (walk === parent) return true
            walk = walk.parent
        }
        return false
    }

    private fun findChild(root: View, id: Int): View? {
        if (root.id == id) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findChild(root.getChildAt(i), id)?.let { return it }
            }
        }
        return null
    }

    private fun findVisibleText(root: View, value: String): TextView? {
        if (root.visibility != View.VISIBLE) return null
        if (root is TextView && root.text?.toString() == value) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findVisibleText(root.getChildAt(i), value)?.let { return it }
            }
        }
        return null
    }

    private fun offsetFrom(root: View, view: View): Int {
        var y = 0
        var walk: View? = view
        while (walk != null && walk !== root) {
            y += walk.top
            walk = walk.parent as? View
        }
        return y
    }

    private fun dp(activity: MainActivity, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics).toInt()

    private fun label(view: View): String = when {
        view.id == View.NO_ID -> view.javaClass.simpleName
        else -> view.resources.getResourceEntryName(view.id)
    }
}
