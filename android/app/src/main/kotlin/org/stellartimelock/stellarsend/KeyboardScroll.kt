package org.stellartimelock.stellarsend

import android.graphics.Rect
import android.widget.ScrollView

object KeyboardScroll {
    fun attach(scroll: ScrollView) {
        scroll.viewTreeObserver.addOnGlobalLayoutListener { reveal(scroll) }
        scroll.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> reveal(scroll) }
    }

    private fun reveal(scroll: ScrollView) {
        val focused = scroll.findFocus() ?: return
        focused.post {
            val rect = Rect(0, 0, focused.width, focused.height)
            focused.requestRectangleOnScreen(rect, true)
        }
    }
}
