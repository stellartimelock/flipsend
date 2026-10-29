package org.stellartimelock.send.core

/**
 * The paired PC presented a new pairing pin. That is not a reason to
 * rotate this phone's key. Show Trust new key for the already-paired
 * computer, then re-pin.
 */
data class TrustOffer(
    val name: String,
    val deviceId: String,
    val fingerprint: String,
    val publicKey: String
)

object TrustNewKey {
    const val TITLE = "Trust new key"
    const val ACTION = "Trust new key"

    fun message(pcName: String?): String {
        val name = pcName?.trim().orEmpty().ifBlank { "your PC" }
        return "$name has a new key. This is the computer you already paired. Trust the new key to keep using it."
    }

    fun applies(hasPairedPeer: Boolean, code: String?, message: String?): Boolean {
        if (!hasPairedPeer) return false
        val text = message?.lowercase().orEmpty()
        return code == ErrorCodes.KEY_CHANGED ||
            text.contains("key changed") ||
            handshakeFailed(message)
    }

    /**
     * Trust is only for a presented cert that is different from the saved pin.
     * A blank presented pin ("none") is a closed socket, not a new key.
     */
    fun shouldPrompt(
        hasPairedPeer: Boolean,
        code: String?,
        message: String?,
        presentedPin: String?,
        storedPin: String?
    ): Boolean {
        if (!applies(hasPairedPeer, code, message)) return false
        if (presentedPin.isNullOrBlank() || !isFingerprint(presentedPin)) return false
        if (isFingerprint(storedPin) && pinsEqual(presentedPin, storedPin)) return false
        return true
    }

    /**
     * Trust new key is only for a real mismatch: both pins well formed and
     * different. An already-paired peer that presents its saved pin never
     * gets the prompt, and neither does this phone's own key (a connection
     * that looped back to this phone through the relay or LAN).
     */
    fun shouldOffer(presentedPin: String?, storedPin: String?, ownPin: String? = null): Boolean {
        if (!isFingerprint(presentedPin) || !isFingerprint(storedPin)) return false
        if (pinsEqual(presentedPin, storedPin)) return false
        if (isFingerprint(ownPin) && pinsEqual(presentedPin, ownPin)) return false
        return true
    }

    /** A pending offer is dropped once the saved pin matches it, or it is this phone's own key. */
    fun offerStillValid(offer: TrustOffer?, storedPin: String?, ownPin: String? = null): Boolean =
        offer != null && shouldOffer(offer.fingerprint.trim().lowercase(), storedPin, ownPin)

    /** KEY_CHANGED is only for a well-formed TLS pin that does not match the saved pin. */
    fun shouldRaiseKeyChanged(presentedPin: String?, storedPin: String?): Boolean {
        if (!isFingerprint(storedPin) || !isFingerprint(presentedPin)) return false
        return !pinsEqual(presentedPin, storedPin)
    }

    /** A closed TLS handshake while already paired is a pin mismatch, not a hang-up. */
    fun handshakeFailed(message: String?): Boolean {
        val text = message?.lowercase().orEmpty()
        if (text.isEmpty()) return false
        return text.contains("key changed") ||
            text.contains("has a new key") ||
            text.contains("secure connection closed") ||
            text.contains("handshake did not finish") ||
            text.contains("did not match the saved pin") ||
            text.contains("sslhandshake") ||
            text.contains("connection closed by peer") ||
            (text.contains("hung up") && (text.contains("ssl") || text.contains("handshake") || text.contains("certificate") || text.contains("secure")))
    }

    fun looksLikeMismatch(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current is KeyChangedException) return true
            if (current is javax.net.ssl.SSLException) return true
            if (handshakeFailed(current.message) || handshakeFailed(current.javaClass.name)) return true
            current = current.cause
        }
        return false
    }

    fun blocksConnected(lastError: String?): Boolean = handshakeFailed(lastError)

    /** First eight hex chars so a log can show which pin was written. */
    fun shortPin(fingerprint: String?): String {
        val pin = fingerprint?.trim().orEmpty()
        if (pin.isBlank()) return "(empty)"
        return if (pin.length <= 8) pin.lowercase() else pin.take(8).lowercase()
    }
}

class KeyChangedException(
    val presentedFingerprint: String,
    presentedSpki: ByteArray = ByteArray(0)
) : java.security.cert.CertificateException("The paired device key changed.") {
    val presentedPublicKey: String =
        if (presentedSpki.isEmpty()) "" else base64UrlEncode(presentedSpki)

    companion object {
        fun of(error: Throwable?): KeyChangedException? {
            var current = error
            while (current != null) {
                if (current is KeyChangedException) return current
                current = current.cause
            }
            return null
        }
    }
}
