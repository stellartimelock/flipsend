package org.stellartimelock.send.core

/**
 * User-facing remote-view copy. The default set is what the production Play build ships.
 * Sideload and Play beta install their own set before any screen or log line.
 * The production Play dex must not contain the sideload wording.
 */
data class RemoteWords(
    val label: String,
    val betaLabel: String,
    val settingsLead: String,
    val needPair: String,
    val allowed: String,
    val denied: String,
    val stopped: String,
    val activeLine: String,
    val updatePhone: String,
    val accessibilityHint: String,
    val accessibilityDescription: String,
    val iosLimit: String,
    val ready: String,
    val failed: String,
    val checklistDetail: String,
    val failedFallback: String,
    val allowTitle: String,
    val linkNeedle: String
)

object RemotePhrases {
    val neutral = RemoteWords(
        label = "Computer view",
        betaLabel = "",
        settingsLead = "TV, folders, and received files are here.",
        needPair = "Pair a computer before viewing its screen.",
        allowed = "The computer view is on. Tap Disconnect to end it.",
        denied = "The computer view was not allowed.",
        stopped = "The computer view stopped.",
        activeLine = "Computer view active - Disconnect",
        updatePhone = "Update the FlipSend app on this phone, then try the computer view again.",
        accessibilityHint = "Phone control is off. Open phone control settings when you want the computer to tap this phone. Viewing the computer does not need it.",
        accessibilityDescription = "FlipSend uses this so your paired computer can tap, swipe, and type on this phone after you tap Allow. Viewing the computer does not use this.",
        iosLimit = "Viewing the computer is not available on iPhone. You can still send files and watch a shared screen.",
        ready = "The computer view is ready.",
        failed = "The computer view didn't start. Tap it again.",
        checklistDetail = "This lets the computer tap and type on this phone. Viewing the computer does not use it.",
        failedFallback = "The computer view failed.",
        allowTitle = "Allow computer view?",
        linkNeedle = ""
    )

    @Volatile
    var current: RemoteWords = neutral

    fun install(words: RemoteWords) {
        current = words
    }
}
