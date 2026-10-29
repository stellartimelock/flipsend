package org.stellartimelock.send

import android.app.Activity
import org.stellartimelock.send.core.RelayPlans
import java.util.concurrent.atomic.AtomicBoolean

object RelayUpgrade {
    private val showing = AtomicBoolean(false)

    fun show(activity: Activity, message: String) {
        if (activity.isFinishing || !showing.compareAndSet(false, true)) return
        val text = message.ifBlank { RelayPlans.QUOTA_MESSAGE }
        val fairUse = text.contains("resets on the 1st", ignoreCase = true)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(if (fairUse) "Relay paused until the 1st" else "Relay data is used up")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .setOnDismissListener { showing.set(false) }
            .showInSafeArea()
    }
}
