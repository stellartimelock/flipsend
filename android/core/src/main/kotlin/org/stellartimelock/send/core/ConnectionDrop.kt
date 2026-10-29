package org.stellartimelock.send.core

/**
 * Plain-English disconnect text. Wi-Fi is tried first, then the HTTPS/WSS relay.
 * A drop is never silent: the home screen shows this, and it is logged.
 */
object ConnectionDrop {
    const val WIFI_LISTEN = "Wi-Fi listen stopped. FlipSend is starting it again."
    const val INCOMING = "Disconnected. The other device hung up."
    const val RELAY = "Disconnected from the internet relay. FlipSend will try again."
    const val RELAY_WAKE = "The internet relay is still waking up. Same-Wi-Fi stays free."
    const val SECURE_RETRY = "The secure connection closed. FlipSend will try again."

    fun line(kind: String, why: String?): String {
        val reason = LogRedaction.redact(why?.trim().orEmpty())
        if (RepairPrompt.looksLikeCertificateUnknown(reason)) {
            return "$kind $SECURE_RETRY"
        }
        if (reason.isEmpty() || reason == kind) return kind
        return "$kind $reason"
    }
}

/** The same disconnect line should not fill the log on every retry. */
class NoticeGate {
    private var last = ""
    private var at = 0L

    fun allow(text: String, nowMs: Long, quietMs: Long = 8_000): Boolean {
        if (text == last && nowMs - at < quietMs) return false
        last = text
        at = nowMs
        return true
    }
}
