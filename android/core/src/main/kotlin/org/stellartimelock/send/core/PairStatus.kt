package org.stellartimelock.send.core

/**
 * Home-screen path and error text. A paired phone that is already listening
 * on Wi-Fi is ready to receive. Background relay wake is not shown as a
 * problem in that case.
 */
object PairStatus {
    fun isWakeText(text: String?): Boolean {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return false
        return value == RelayWake.STATUS ||
            value == RelayWake.GAVE_UP ||
            value == RelayWake.NO_PEER ||
            value.contains("waking", ignoreCase = true)
    }

    fun pathLabel(paired: Boolean, listening: Boolean, raw: String): String {
        if (paired && listening && (raw.isBlank() || isWakeText(raw))) {
            return Reachability.DIRECT
        }
        return raw
    }

    fun detailError(paired: Boolean, listening: Boolean, lastError: String?): String? {
        if (NetworkHop.isSwitch(lastError)) return NetworkHop.LINE
        if (paired && listening && isWakeText(lastError)) return null
        return lastError
    }
}
