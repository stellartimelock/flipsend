package org.stellartimelock.send

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import org.stellartimelock.send.core.ReceiveStatus

/** Shared FlipSend look: Design C list rows, one gold fill, outline otherwise. */
object PageLook {
    enum class Kind { GOLD, TONAL, OUTLINE, ROW, DANGER }

    fun title(context: Context, text: String, id: Int = View.NO_ID): TextView =
        text(context, text, ReceiveStatus.TITLE_SP, bold = true, muted = false).apply {
            if (id != View.NO_ID) this.id = id
        }

    fun heading(context: Context, text: String, id: Int = View.NO_ID): TextView =
        text(context, text, ReceiveStatus.STATUS_SP, bold = true, muted = false).apply {
            if (id != View.NO_ID) this.id = id
            setPadding(0, dp(context, 8), 0, 0)
        }

    fun status(context: Context, text: String, id: Int = View.NO_ID): TextView =
        text(context, text, ReceiveStatus.STATUS_SP, bold = true, muted = false).apply {
            if (id != View.NO_ID) this.id = id
            setPadding(0, dp(context, 16), 0, 0)
        }

    fun note(context: Context, text: String, id: Int = View.NO_ID): TextView =
        text(context, text, ReceiveStatus.META_SP, bold = false, muted = true).apply {
            if (id != View.NO_ID) this.id = id
            setPadding(0, dp(context, 8), 0, 0)
        }

    fun section(context: Context, text: String): TextView =
        text(context, text.uppercase(), ReceiveStatus.SECTION_SP, bold = true, muted = true).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(context, 14), 0, dp(context, 8))
        }

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = surface(context)
        val pad = dp(context, 16)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 14) }
    }

    fun pill(context: Context, label: String, accent: Boolean = false, id: Int = View.NO_ID): MaterialButton =
        pill(context, label, if (accent) Kind.GOLD else Kind.OUTLINE, id)

    fun pill(context: Context, label: String, kind: Kind, id: Int = View.NO_ID): MaterialButton =
        MaterialButton(context).apply {
            if (id != View.NO_ID) this.id = id
            text = label
            contentDescription = label
            paint(this, kind)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 8) }
        }

    fun bar(context: Context, id: Int = View.NO_ID): ProgressBar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            if (id != View.NO_ID) this.id = id
            max = 100
            progress = 0
            isIndeterminate = false
            visibility = View.GONE
            minimumHeight = dp(context, 12)
            progressTintList = ColorStateList.valueOf(context.getColor(R.color.dash_gold))
            progressBackgroundTintList = ColorStateList.valueOf(context.getColor(R.color.space_line))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 16) }
        }

    fun text(context: Context, value: String, sp: Float, bold: Boolean, muted: Boolean): TextView =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(context.getColor(if (muted) R.color.dash_muted else R.color.dash_cream))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            FitButtons.prepare(this)
        }

    fun styleExisting(button: android.widget.Button, accent: Boolean = false) {
        paint(button, if (accent) Kind.GOLD else Kind.OUTLINE)
    }

    fun styleExisting(button: android.widget.Button, kind: Kind) {
        paint(button, kind)
    }

    private fun paint(button: android.widget.Button, kind: Kind) {
        val context = button.context
        val body = if (kind == Kind.ROW) ReceiveStatus.MIN_SP else ReceiveStatus.MIN_SP
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, body)
        FitButtons.prepare(button)
        button.minimumHeight = dp(context, if (kind == Kind.GOLD) 56 else 52)
        button.isSingleLine = true
        button.maxLines = 1
        if (button is MaterialButton) {
            button.cornerRadius = dp(context, if (kind == Kind.ROW) 10 else 999)
            button.insetTop = 0
            button.insetBottom = 0
            button.elevation = 0f
            button.stateListAnimator = null
            when (kind) {
                Kind.GOLD -> {
                    button.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.dash_gold))
                    button.strokeWidth = 0
                    button.setTextColor(context.getColor(R.color.dash_gold_ink))
                }
                Kind.TONAL -> {
                    button.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.space_quiet))
                    button.strokeWidth = 0
                    button.setTextColor(context.getColor(R.color.dash_teal))
                }
                Kind.DANGER -> {
                    button.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                    button.strokeColor = ColorStateList.valueOf(context.getColor(R.color.dash_danger))
                    button.strokeWidth = dp(context, 2)
                    button.setTextColor(context.getColor(R.color.dash_danger))
                }
                Kind.ROW -> {
                    button.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                    button.strokeWidth = 0
                    button.setTextColor(context.getColor(R.color.dash_cream))
                    button.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                }
                Kind.OUTLINE -> {
                    button.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                    button.strokeColor = ColorStateList.valueOf(context.getColor(R.color.space_stroke))
                    button.strokeWidth = dp(context, 2)
                    button.setTextColor(context.getColor(R.color.dash_cream))
                }
            }
        } else {
            button.background = when (kind) {
                Kind.GOLD -> pillShape(context, context.getColor(R.color.dash_gold), 0)
                Kind.TONAL -> pillShape(context, context.getColor(R.color.space_quiet), 0)
                Kind.DANGER -> pillShape(context, android.graphics.Color.TRANSPARENT, context.getColor(R.color.dash_danger))
                Kind.ROW -> pillShape(context, android.graphics.Color.TRANSPARENT, 0)
                Kind.OUTLINE -> pillShape(context, android.graphics.Color.TRANSPARENT, context.getColor(R.color.space_stroke))
            }
            button.setTextColor(
                context.getColor(
                    when (kind) {
                        Kind.GOLD -> R.color.dash_gold_ink
                        Kind.TONAL -> R.color.dash_teal
                        Kind.DANGER -> R.color.dash_danger
                        else -> R.color.dash_cream
                    }
                )
            )
        }
    }

    private fun surface(context: Context): GradientDrawable = GradientDrawable().apply {
        setColor(context.getColor(R.color.dash_surface))
        cornerRadius = dp(context, 16).toFloat()
        setStroke(dp(context, 1), context.getColor(R.color.space_line))
    }

    private fun pillShape(context: Context, fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, 999).toFloat()
        if (stroke != 0) setStroke(dp(context, 2), stroke)
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
