package org.stellartimelock.stellarsend

import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.stellartimelock.send.ApkInstallActivity
import org.stellartimelock.send.ApkSessions
import org.stellartimelock.send.ReceivedActivity
import org.stellartimelock.send.ReceivedLog
import org.stellartimelock.send.SafeDrawing
import org.stellartimelock.send.SendToPcActivity
import org.stellartimelock.send.TextViewerActivity
import org.stellartimelock.send.VaultHandoffActivity
import org.stellartimelock.send.showInSafeArea
import java.io.File
import kotlin.math.max
import kotlin.math.pow

/**
 * OnePlus 13R sized window (1264×2780 at 450 dpi) with a punch-hole cutout
 * taller than the status bar, a navigation bar, and an open keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w449dp-h988dp-450dpi")
class EdgeToEdgeTest {

    @Test
    fun mainScreenClearsCutoutBarsAndKeyboard() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val content = activity.findViewById<View>(android.R.id.content)
        val cutoutTop = 168
        val navBottom = 96
        val imeBottom = 1100
        val cutoutLeft = 0
        dispatch(content, statusTop = 96, cutoutTop = cutoutTop, navBottom = navBottom, imeBottom = imeBottom)
        dispatch(content, statusTop = 96, cutoutTop = cutoutTop, navBottom = navBottom, imeBottom = imeBottom)
        layoutFull(content)

        assertEquals(cutoutTop, content.paddingTop)
        assertEquals(imeBottom, content.paddingBottom)
        assertEquals(cutoutLeft, content.paddingLeft)

        val title = activity.findViewById<TextView>(R.id.screenTitle)
        assertEquals("FlipSend", title.text.toString())
        assertEquals(View.VISIBLE, title.visibility)
        assertTrue(topIn(title, content) >= content.paddingTop)
        assertTrue(title.height > 0)

        val scroll = activity.findViewById<ScrollView>(R.id.scroller)
        val child = scroll.getChildAt(0)
        assertTrue("screen content should scroll", child.measuredHeight > scroll.measuredHeight)
        assertTrue(scroll.canScrollVertically(1))

        val relay = activity.findViewById<View>(R.id.relayUrl)
        val relayTop = topIn(relay, child)
        val maxScroll = child.measuredHeight - scroll.measuredHeight
        assertTrue(
            "keyboard should not trap the relay field",
            relayTop <= maxScroll + scroll.measuredHeight - relay.measuredHeight
        )
        assertTrue(scroll.measuredHeight > relay.measuredHeight)
    }

    @Test
    fun sideCutoutIsPadded() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val content = activity.findViewById<View>(android.R.id.content)
        dispatch(content, statusTop = 80, cutoutTop = 40, cutoutLeft = 132, cutoutRight = 20, navBottom = 48)
        assertEquals(132, content.paddingLeft)
        assertEquals(20, content.paddingRight)
        assertEquals(80, content.paddingTop)
        assertEquals(48, content.paddingBottom)
    }

    @Test
    fun firstLaunchTextIsLightOnDark() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val content = activity.findViewById<View>(android.R.id.content)
        dispatch(content, statusTop = 120, cutoutTop = 150, navBottom = 80)
        layoutFull(content)

        val windowBg = activity.window.decorView.background
        assertTrue(windowBg is ColorDrawable)
        val page = (windowBg as ColorDrawable).color
        assertEquals(activity.getColor(org.stellartimelock.send.R.color.space_bg), page)
        assertTrue(luminance(page) < 0.15)

        val lightStatus = TypedValue()
        assertTrue(activity.theme.resolveAttribute(android.R.attr.windowLightStatusBar, lightStatus, true))
        assertEquals(0, lightStatus.data)
        val forceDark = TypedValue()
        assertTrue(activity.theme.resolveAttribute(android.R.attr.forceDarkAllowed, forceDark, true))
        assertEquals(0, forceDark.data)

        val failures = mutableListOf<String>()
        walk(content) { view ->
            if (view !is TextView || view.visibility != View.VISIBLE) return@walk
            val sample = if (view.text.isNullOrBlank()) view.hint?.toString().orEmpty() else view.text.toString()
            if (sample.isBlank()) return@walk
            val fg = if (view.text.isNullOrBlank()) view.currentHintTextColor else view.currentTextColor
            val bg = backgroundOf(view)
            val ratio = contrast(fg, bg)
            if (luminance(bg) < 0.2 && luminance(fg) < 0.25) {
                failures += "dark text on dark background \"$sample\" fg=${hex(fg)} bg=${hex(bg)}"
            }
            if (ratio < 4.5) {
                failures += "low contrast ${"%.2f".format(ratio)} \"$sample\" fg=${hex(fg)} bg=${hex(bg)}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun everyScreenPadsTheSafeArea() {
        val cutoutTop = 160
        val navBottom = 88

        val send = Robolectric.buildActivity(
            SendToPcActivity::class.java,
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "hello")
            }
        ).create().get()
        assertScreen(send.findViewById(android.R.id.content), "Send to PC", cutoutTop, navBottom)

        val vault = Robolectric.buildActivity(VaultHandoffActivity::class.java).create().get()
        assertScreen(vault.findViewById(android.R.id.content), "Sending to your PC.", cutoutTop, navBottom)

        val received = Robolectric.buildActivity(ReceivedActivity::class.java).create().get()
        assertScreen(received.findViewById(android.R.id.content), "Received", cutoutTop, navBottom)

        val install = Robolectric.buildActivity(ApkInstallActivity::class.java).create().get()
        assertScreen(
            install.findViewById(android.R.id.content),
            "Receiving",
            cutoutTop,
            navBottom
        )

        val main = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val dialog = MaterialAlertDialogBuilder(main)
            .setTitle("Pair with computer")
            .setMessage("Type the pairing code")
            .showInSafeArea()
        val dialogContent = dialog.window!!.findViewById<View>(android.R.id.content)
        dispatch(dialogContent, statusTop = 100, cutoutTop = cutoutTop, navBottom = navBottom, imeBottom = 700)
        assertEquals(cutoutTop, dialogContent.paddingTop)
        assertEquals(700, dialogContent.paddingBottom)
        val message = findText(dialogContent, "Type the pairing code")
        assertTrue(message != null)
        assertTrue(luminance(message!!.currentTextColor) > 0.4)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun receivedListIsNewestFirstAndOpenStaysOnScreen() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val inbox = File(context.filesDir, "inbox").apply { mkdirs() }
        val older = File(inbox, "older.txt").apply { writeText("old note") }
        val newer = File(inbox, "notes.txt").apply { writeText("hello from the computer") }
        val log = ReceivedLog(context)
        log.add(older, "text", at = 1_700_000_000_000)
        val notes = log.add(newer, "text", at = 1_759_000_000_000)

        org.stellartimelock.send.InstallPrefs.setAuto(context, true)
        val activity = Robolectric.buildActivity(ReceivedActivity::class.java).create().get()
        val content = activity.findViewById<View>(android.R.id.content)
        dispatch(content, statusTop = 96, cutoutTop = 160, navBottom = 84)
        layoutFull(content)

        val title = activity.findViewById<TextView>(org.stellartimelock.send.R.id.received_title)
        assertEquals("Received", title.text.toString())
        assertTrue(topIn(title, content) >= content.paddingTop)
        assertTrue(luminance(title.currentTextColor) > 0.5)

        val toggle = activity.findViewById<android.widget.CompoundButton>(org.stellartimelock.send.R.id.auto_install_updates)
        assertEquals("Auto-install updates from my PC", toggle.text.toString())
        assertTrue(toggle.isChecked)
        assertTrue(findText(content, org.stellartimelock.send.core.ReceivedHistory.EXPLANATION) != null)

        val notesView = findText(content, "notes.txt")
        val olderView = findText(content, "older.txt")
        assertTrue(notesView != null && olderView != null)
        assertTrue(topIn(notesView!!, content) < topIn(olderView!!, content))
        assertTrue(findText(content, ReceivedHistoryLine(notes)) != null)
        assertFalse(activity.isFinishing)

        val viewer = Robolectric.buildActivity(
            TextViewerActivity::class.java,
            Intent().putExtra(TextViewerActivity.EXTRA_ID, notes.id)
        ).create().get()
        val body = viewer.findViewById<TextView>(org.stellartimelock.send.R.id.text_viewer_body)
        assertEquals("hello from the computer", body.text.toString())
        assertTrue(body.text.isNotBlank())
        assertTrue(luminance(body.currentTextColor) > 0.5)
        assertFalse(viewer.isFinishing)
        assertFalse(activity.isFinishing)

        val missing = Robolectric.buildActivity(
            TextViewerActivity::class.java,
            Intent().putExtra(TextViewerActivity.EXTRA_ID, "missing")
        ).create().get()
        val missingBody = missing.findViewById<TextView>(org.stellartimelock.send.R.id.text_viewer_body)
        assertTrue(missingBody.text.toString().contains("no longer"))
        assertFalse(missing.isFinishing)

        assertEquals(
            PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED,
            ApkSessions.requireUserActionMode(silent = true)
        )
        assertEquals(
            PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED,
            ApkSessions.requireUserActionMode(silent = false)
        )
        assertFalse(org.stellartimelock.send.core.ApkAuto.waitsForFlipSendTap())
        assertFalse(org.stellartimelock.send.core.ApkAuto.abandonsIfAndroidAsks())
        assertTrue(org.stellartimelock.send.core.ApkAuto.startIncomingSession())
        assertFalse(
            org.stellartimelock.send.core.SelfUpdate.requestSilent(
                eligible = true,
                installerInForeground = false
            )
        )
        assertTrue(
            org.stellartimelock.send.core.SelfUpdate.requestSilent(
                eligible = true,
                installerInForeground = true
            )
        )
        assertFalse(org.stellartimelock.send.core.SelfUpdate.mayUninstallSelf())
        assertEquals(
            PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED,
            ApkSessions.requireUserActionMode(
                silent = org.stellartimelock.send.core.SelfUpdate.requestSilent(true, false)
            )
        )
    }

    private fun ReceivedHistoryLine(entry: org.stellartimelock.send.core.ReceivedEntry): String =
        org.stellartimelock.send.core.ReceivedHistory.summary(entry)

    private fun assertScreen(content: View, title: String, cutoutTop: Int, navBottom: Int) {
        dispatch(content, statusTop = 90, cutoutTop = cutoutTop, navBottom = navBottom)
        layoutFull(content)
        assertEquals(cutoutTop, content.paddingTop)
        assertEquals(navBottom, content.paddingBottom)
        val text = findText(content, title)
        assertTrue("missing \"$title\"", text != null)
        assertTrue(topIn(text!!, content) >= content.paddingTop)
        assertTrue(luminance(text.currentTextColor) > 0.5)
        val scroll = findScroll(content)
        assertTrue("\"$title\" should sit in a scroll view", scroll != null)
        assertTrue(scroll!!.isFillViewport)
        assertTrue(!scroll.clipToPadding)
    }

    private fun dispatch(
        view: View,
        statusTop: Int = 0,
        navBottom: Int = 0,
        cutoutLeft: Int = 0,
        cutoutTop: Int = 0,
        cutoutRight: Int = 0,
        imeBottom: Int = 0
    ) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusTop, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBottom))
            .setInsets(
                WindowInsetsCompat.Type.displayCutout(),
                Insets.of(cutoutLeft, cutoutTop, cutoutRight, 0)
            )
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottom))
            .setVisible(WindowInsetsCompat.Type.statusBars(), true)
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .setVisible(WindowInsetsCompat.Type.displayCutout(), true)
            .setVisible(WindowInsetsCompat.Type.ime(), imeBottom > 0)
            .build()
        assertEquals(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime(),
            SafeDrawing.covered
        )
        ViewCompat.dispatchApplyWindowInsets(view, insets)
    }

    private fun layoutFull(view: View) {
        val metrics = view.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
    }

    private fun topIn(view: View, ancestor: View): Int {
        var y = 0
        var current: View? = view
        while (current != null && current !== ancestor) {
            y += current.top
            current = current.parent as? View
        }
        return y
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

    private fun findScroll(root: View): ScrollView? {
        if (root is ScrollView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findScroll(root.getChildAt(i))?.let { return it }
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

    private fun backgroundOf(view: View): Int {
        val tint = view.backgroundTintList?.defaultColor
        if (tint != null && Color.alpha(tint) >= 255) return tint
        var current: View? = view
        while (current != null) {
            solid(current)?.let { return it }
            val parentTint = current.backgroundTintList?.defaultColor
            if (parentTint != null && Color.alpha(parentTint) >= 255) return parentTint
            current = current.parent as? View
        }
        return view.context.getColor(org.stellartimelock.send.R.color.space_bg)
    }

    private fun solid(view: View): Int? {
        val drawable = view.background ?: return null
        return when (drawable) {
            is ColorDrawable -> drawable.color
            is GradientDrawable -> drawable.color?.defaultColor
            else -> null
        }
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    private fun contrast(fg: Int, bg: Int): Double {
        val lighter = max(luminance(fg), luminance(bg))
        val darker = kotlin.math.min(luminance(fg), luminance(bg))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun hex(color: Int) = "#%08X".format(color)
}
