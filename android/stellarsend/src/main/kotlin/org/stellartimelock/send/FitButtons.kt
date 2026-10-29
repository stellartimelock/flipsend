package org.stellartimelock.send

import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.stellartimelock.send.core.ReceiveStatus

/** Stacks buttons and labels so they stay on a normal phone width. */
object FitButtons {
    fun prepare(view: TextView) {
        view.isSingleLine = false
        view.maxLines = 8
        view.ellipsize = null
        view.minWidth = 0
        val minPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            ReceiveStatus.MIN_SP,
            view.resources.displayMetrics
        )
        if (view.textSize + 0.5f < minPx) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, ReceiveStatus.MIN_SP)
        }
    }

    fun stack(parent: LinearLayout, gapDp: Int = 8, vararg children: View) {
        parent.orientation = LinearLayout.VERTICAL
        val gap = (gapDp * parent.resources.displayMetrics.density).toInt()
        children.forEachIndexed { index, child ->
            if (child is TextView) prepare(child)
            child.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) topMargin = gap }
            parent.addView(child)
        }
    }
}
