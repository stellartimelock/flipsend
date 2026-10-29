package org.stellartimelock.send

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Keeps every screen inside the area that is not covered by the status bar,
 * display cutout, navigation bar, or the on-screen keyboard.
 *
 * The mask matches Compose's safeDrawing insets: system bars (status,
 * navigation, caption), the display cutout, and the IME.
 */
object SafeDrawing {
    val covered: Int =
        WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or
            WindowInsetsCompat.Type.ime()

    fun prepare(activity: Activity) {
        prepare(activity.window)
        activity.window.setBackgroundDrawableResource(R.color.space_bg)
    }

    fun prepare(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val params = window.attributes
        if (Build.VERSION.SDK_INT >= 30) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = params
    }

    /** Pads the activity content so it clears the safe drawing insets. */
    fun apply(activity: Activity) {
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        apply(content)
    }

    fun apply(view: View) {
        if (view.getTag(R.id.safe_drawing) == true) return
        view.setTag(R.id.safe_drawing, true)
        if (view.id == android.R.id.content && view.context is Activity && view.background == null) {
            view.setBackgroundColor(ContextCompat.getColor(view.context, R.color.space_bg))
        }
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom
        if (view is ScrollView) {
            view.clipToPadding = false
            view.isScrollContainer = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(covered)
            target.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            revealFocused(target)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Pads overlay chrome for the status bar, cutout, navigation bar, and keyboard.
     * The view behind the chrome stays edge to edge.
     */
    fun pinChrome(root: View, top: View, bottom: View) {
        pinRemote(root, top, bottom)
    }

    /**
     * Remote desktop: toolbar below the status bar and cutout, view in the
     * middle, keyboard and controls above the nav bar. Nothing overlaps.
     */
    fun pinRemote(root: View, top: View, bottom: View) {
        val topLeft = top.paddingLeft
        val topTop = top.paddingTop
        val topRight = top.paddingRight
        val topBottom = top.paddingBottom
        val bottomLeft = bottom.paddingLeft
        val bottomTop = bottom.paddingTop
        val bottomRight = bottom.paddingRight
        val bottomBottom = bottom.paddingBottom
        val rootLeft = root.paddingLeft
        val rootRight = root.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(covered)
            top.setPadding(topLeft + bars.left, topTop + bars.top, topRight + bars.right, topBottom)
            bottom.setPadding(bottomLeft + bars.left, bottomTop, bottomRight + bars.right, bottomBottom + bars.bottom)
            root.setPadding(rootLeft, root.paddingTop, rootRight, root.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun revealFocused(root: View) {
        val focused = root.findFocus() ?: return
        focused.post {
            val rect = android.graphics.Rect(0, 0, focused.width, focused.height)
            focused.requestRectangleOnScreen(rect, true)
        }
    }
}

/** Shows a dialog and keeps its contents clear of the bars, cutout, and keyboard. */
fun MaterialAlertDialogBuilder.showInSafeArea(): AlertDialog {
    val dialog = create()
    dialog.window?.let { window -> SafeDrawing.prepare(window) }
    fun pad() {
        dialog.window?.findViewById<View>(android.R.id.content)?.let { SafeDrawing.apply(it) }
    }
    // Dialog.show() posts OnShowListener, so pad now as well. Returning from an
    // install or open prompt then comes back to this window, not an empty one.
    dialog.setOnShowListener { pad() }
    dialog.show()
    pad()
    return dialog
}

/** A short message screen that can scroll if the safe area is small. */
fun Activity.showScrollingMessage(message: String) {
    SafeDrawing.prepare(this)
    val pad = (20 * resources.displayMetrics.density).toInt()
    val text = android.widget.TextView(this).apply {
        text = message
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(getColor(R.color.space_text))
        setPadding(pad, pad, pad, pad)
    }
    val scroll = ScrollView(this).apply {
        setBackgroundColor(getColor(R.color.space_bg))
        isFillViewport = true
        isScrollContainer = true
        clipToPadding = false
        addView(
            text,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }
    setContentView(scroll)
    SafeDrawing.apply(this)
}
