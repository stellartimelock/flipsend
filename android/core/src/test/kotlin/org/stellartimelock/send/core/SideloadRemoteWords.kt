package org.stellartimelock.send.core

/** Test-only copy of the sideload wording. This source set is not in the APK. */
object SideloadRemoteWords {
    fun install() {
        RemotePhrases.install(
            RemoteWords(
                label = "Remote desktop",
                betaLabel = "Remote desktop (beta)",
                settingsLead = "Remote desktop, TV, folders, and received files are here.",
                needPair = "Pair a computer before remote desktop.",
                allowed = "Remote desktop is on. Tap Disconnect to end it.",
                denied = "Remote desktop was not allowed.",
                stopped = "Remote desktop stopped.",
                activeLine = "Remote desktop active - Disconnect",
                updatePhone = "Update the FlipSend app on this phone, then tap Remote desktop again.",
                accessibilityHint = "Phone control is off. Open phone control settings when you want the computer to tap this phone. Remote desktop does not need it.",
                accessibilityDescription = "FlipSend uses this so your paired computer can tap, swipe, and type on this phone after you tap Allow. Remote desktop does not use this.",
                iosLimit = "Remote desktop is not available on iPhone. You can still send files and watch a shared screen.",
                ready = "Remote desktop is ready.",
                failed = "Remote desktop didn't start. Tap it again.",
                checklistDetail = "This lets the computer tap and type on this phone. Remote desktop does not use it.",
                failedFallback = "Remote desktop failed.",
                allowTitle = "Allow remote desktop?",
                linkNeedle = "remote desktop"
            )
        )
    }
}
