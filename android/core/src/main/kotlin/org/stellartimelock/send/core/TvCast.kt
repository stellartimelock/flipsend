package org.stellartimelock.send.core

/**
 * Cast to TV is the same screen-share family as Share this screen.
 * Chromecast does not give third-party apps a live Presentation display
 * without a Google Cast Console app id, so FlipSend lists TVs then uses
 * Android's Cast / wireless-display sheet (or a MediaRouter live-video
 * route when the phone actually has one). Drops are always shown.
 */
object TvCast {
    const val BUTTON = "Cast to TV"
    const val STOP = "Stop TV"
    const val LOOKING = "Looking for TVs on this Wi-Fi…"
    const val GOOGLE_CAST_SERVICE = "_googlecast._tcp"
    const val SYSTEM_ID = "system-cast-sheet"
    const val SYSTEM_NAME = "Cast sheet (Chromecast / smart TV)"
    const val SYSTEM_DETAIL = "Opens Android's Cast sheet. Pick the TV there. The whole phone is mirrored."
    const val PICK_SHEET = "Pick that TV on the next sheet. Android will mirror this phone."
    const val STARTED = "Showing this phone on the TV."
    const val STOPPED = "TV cast stopped."
    const val DROPPED = "Cast dropped: the TV disconnected."
    const val UNSELECTED = "Cast dropped: the TV was unselected."
    const val NO_SHEET = "Could not open the system Cast sheet on this phone."
    const val WINDOWS_PROJECT = "ms-settings:project"
    const val WINDOWS_STARTED =
        "Pick a TV in the Windows Project sheet. If it drops, Windows will say the wireless display disconnected."

    fun noneFound(foundCount: Int): String =
        if (foundCount > 0) ""
        else "No TV found on this Wi-Fi. Turn the TV on, then try again, or use the Cast sheet."

    fun showing(name: String): String = "Showing this phone on $name."

    fun pickNamed(name: String): String = "Pick $name on the next sheet. Android will mirror this phone."

    fun openFailed(reason: String): String = "Could not start Cast to TV. $reason"

    fun dropReason(userStop: Boolean, displayGone: Boolean, unselected: Boolean): String = when {
        userStop -> STOPPED
        displayGone -> DROPPED
        unselected -> UNSELECTED
        else -> DROPPED
    }

    fun friendlyName(txt: Map<String, String>, fallback: String): String {
        val fn = txt["fn"]?.trim().orEmpty()
        return fn.ifBlank { fallback.trim().ifBlank { "Chromecast" } }
    }

    fun isDefaultRouteName(name: String): Boolean {
        val value = name.trim()
        return value.isEmpty() ||
            value.equals("Phone", ignoreCase = true) ||
            value.equals("This device", ignoreCase = true) ||
            value.equals("Default", ignoreCase = true)
    }
}

enum class TvTargetKind {
    GOOGLE_CAST,
    WIRELESS_DISPLAY,
    SYSTEM
}

data class TvTarget(
    val id: String,
    val name: String,
    val kind: TvTargetKind,
    val detail: String
)
