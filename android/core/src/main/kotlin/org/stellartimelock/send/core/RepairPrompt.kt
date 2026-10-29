package org.stellartimelock.send.core

/** Shown when the paired computer still answers, but it no longer accepts this phone. */
object RepairPrompt {
    const val TITLE = "Re-scan / re-pair"
    const val ACTION = "Re-scan / re-pair"
    const val MESSAGE = "This computer no longer recognizes this phone. Tap Re-scan / re-pair, then scan the code on the PC."

    fun looksLikeCertificateUnknown(message: String?): Boolean {
        val text = message?.lowercase().orEmpty()
        return text.contains("certificate_unknown") ||
            text.contains("sslv3_alert_certificate_unknown") ||
            text.contains("certificate unknown")
    }

    /**
     * A certificate_unknown alert is not proof the pairing is wrong. It also
     * happens when the link closes, when this phone meets its own relay
     * listener, or when a probe hits the port. Only a finished handshake that
     * names a different key, or an explicit unknown-peer result, may ask for
     * a re-scan. Remote desktop refusals never do.
     */
    fun unrecognized(code: String?, message: String?): Boolean {
        if (looksLikeCertificateUnknown(message)) return false
        if (RemoteDesktop.isLinkFailure(code, message)) return false
        val text = message?.lowercase().orEmpty()
        if (text.contains("pair a device before")) return false
        if (text.contains("no longer recognizes")) return false
        if (code == ErrorCodes.KEY_CHANGED) return false
        if (text.contains("key changed")) return false
        if (text.contains("unknown peer")) return true
        if (code == ErrorCodes.UNKNOWN_PEER && text.contains("pair again")) return true
        if (text.contains("pair again") && (code == ErrorCodes.UNKNOWN_PEER || code == ErrorCodes.KEY_CHANGED || code == null)) {
            return true
        }
        return text.contains("pair again") && text.contains("not paired")
    }

    fun codeOf(error: Throwable): String? {
        var current: Throwable? = error
        while (current != null) {
            val code = (current as? ProtocolException)?.code
            if (TrustNewKey.applies(true, code, current.message)) {
                return ErrorCodes.KEY_CHANGED
            }
            if (unrecognized(code, current.message)) {
                return ErrorCodes.UNKNOWN_PEER
            }
            current = current.cause
        }
        return null
    }
}
