package org.stellartimelock.send

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.stellartimelock.send.core.RemoteCoords
import org.stellartimelock.send.core.RemoteInput
import java.util.concurrent.atomic.AtomicReference

class RemoteControlService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        instance.set(this)
    }

    override fun onDestroy() {
        if (instance.get() === this) instance.set(null)
        super.onDestroy()
    }

    fun apply(event: RemoteInput): Boolean {
        val size = screenSize()
        val (x, y) = RemoteCoords.toDevice(event.x, event.y, size.first, size.second)
        val (x2, y2) = RemoteCoords.toDevice(event.x2, event.y2, size.first, size.second)
        return when (event.kind) {
            "tap", "up" -> tap(x.toFloat(), y.toFloat())
            "swipe" -> swipe(x.toFloat(), y.toFloat(), x2.toFloat(), y2.toFloat())
            "scroll", "wheel" -> {
                val dy = (event.dy * 420).toFloat()
                swipe(x.toFloat(), y.toFloat(), x.toFloat(), (y - dy))
            }
            "text" -> type(event.text.orEmpty())
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "down", "move", "key" -> {
                if (event.kind == "key" && event.text.equals("enter", ignoreCase = true)) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                } else {
                    true
                }
            }
            else -> false
        }
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 70)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 220)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun type(text: String): Boolean {
        if (text.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        if (root !== focused) root.recycle()
        return ok
    }

    private fun screenSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            val bounds = getSystemService(Display::class.java)?.let {
                // displayMetrics is enough; keep the same pixels MediaProjection uses
                metrics.widthPixels to metrics.heightPixels
            } ?: (metrics.widthPixels to metrics.heightPixels)
            bounds
        } else {
            metrics.widthPixels to metrics.heightPixels
        }
    }

    companion object {
        private val instance = AtomicReference<RemoteControlService?>(null)

        fun available(): Boolean = instance.get() != null

        fun apply(event: RemoteInput): Boolean = instance.get()?.apply(event) ?: false
    }
}
