package org.stellartimelock.send.core

import org.json.JSONObject

object MemoCommandLimits {
    const val VERSION = 1
    const val CHANNEL_MEMO = "sscmd.1"
    const val MAX_CIPHERTEXT_BYTES = 512
    const val MAX_PLAINTEXT_BYTES = 512
    const val MAX_COPY_CHARS = 256
    const val MAX_URL_CHARS = 256
    const val CLOCK_SKEW_SECONDS = 120L
    const val MAX_LIFETIME_SECONDS = 86_400L
}

object MemoCodes {
    const val UNVERIFIED_SENDER = "unverified_sender"
    const val EXPIRED = "expired"
    const val REPLAY = "replay"
    const val NOT_CONFIGURED = "not_configured"
}

data class MemoDecision(val ok: Boolean, val code: String, val message: String, val op: String?) {
    companion object {
        fun accept(op: String) = MemoDecision(true, "ok", "", op)
        fun reject(code: String, message: String) = MemoDecision(false, code, message, null)
    }
}

object MemoCommandPolicy {
    fun decide(plaintext: ByteArray, nowSeconds: Long, senderVerified: Boolean, alreadySeen: (String) -> Boolean): MemoDecision {
        if (plaintext.isEmpty() || plaintext.size > MemoCommandLimits.MAX_PLAINTEXT_BYTES) {
            return MemoDecision.reject(ErrorCodes.LIMIT, "That memo command is empty or too large.")
        }
        if (!senderVerified) {
            return MemoDecision.reject(MemoCodes.UNVERIFIED_SENDER, "The memo sender was not verified. Nothing was run.")
        }
        val root = try {
            JSONObject(plaintext.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return MemoDecision.reject(ErrorCodes.BAD_REQUEST, "Unreadable memo command.")
        }
        if (root.optInt("v", -1) != MemoCommandLimits.VERSION) {
            return MemoDecision.reject(ErrorCodes.BAD_REQUEST, "Unsupported memo command.")
        }
        val id = root.optString("id", "")
        if (runCatching { java.util.UUID.fromString(id) }.isFailure) {
            return MemoDecision.reject(ErrorCodes.BAD_REQUEST, "Unreadable memo command.")
        }
        if (!root.has("exp")) {
            return MemoDecision.reject(ErrorCodes.BAD_REQUEST, "Unreadable memo command.")
        }
        val exp = root.optLong("exp")
        if (nowSeconds > exp + MemoCommandLimits.CLOCK_SKEW_SECONDS) {
            return MemoDecision.reject(MemoCodes.EXPIRED, "That memo command has expired.")
        }
        if (exp > nowSeconds + MemoCommandLimits.MAX_LIFETIME_SECONDS) {
            return MemoDecision.reject(ErrorCodes.BAD_REQUEST, "That memo command expires too far ahead.")
        }
        if (alreadySeen(id)) {
            return MemoDecision.reject(MemoCodes.REPLAY, "That memo command was already used.")
        }
        return when (val op = root.optString("op", "")) {
            "ping", "lock_pc" -> MemoDecision.accept(op)
            "open_url" -> openUrl(root)
            "copy_text" -> copyText(root)
            else -> MemoDecision.reject(ErrorCodes.NOT_IMPLEMENTED, "That memo command is not available. The desktop is not controlled.")
        }
    }

    private fun openUrl(root: JSONObject): MemoDecision {
        val url = if (root.has("url")) root.optString("url", "") else null
        if (url == null || url.length > MemoCommandLimits.MAX_URL_CHARS) {
            val message = if (url == null) "Enter an http or https link." else "That link is too long for a memo command."
            return MemoDecision.reject(ErrorCodes.BAD_REQUEST, message)
        }
        val check = BrowserUrls.tryNormalize(url)
        if (!check.ok) return MemoDecision.reject(ErrorCodes.BAD_REQUEST, check.error)
        return MemoDecision.accept("open_url")
    }

    private fun copyText(root: JSONObject): MemoDecision {
        val text = if (root.has("text")) root.optString("text", "") else ""
        if (text.isEmpty()) return MemoDecision.reject(ErrorCodes.BAD_REQUEST, "Text is empty.")
        if (text.length > MemoCommandLimits.MAX_COPY_CHARS) {
            return MemoDecision.reject(ErrorCodes.BAD_REQUEST, "Text is too long for a memo command.")
        }
        return MemoDecision.accept("copy_text")
    }
}

object UnconfiguredMemoCrypto {
    const val MESSAGE = "Memo commands are not unlocked. No seed was read and no account was watched."

    fun open(ciphertextSize: Int): MemoDecision {
        if (ciphertextSize == 0 || ciphertextSize > MemoCommandLimits.MAX_CIPHERTEXT_BYTES) {
            return MemoDecision.reject(ErrorCodes.LIMIT, "That memo command is empty or too large.")
        }
        return MemoDecision.reject(MemoCodes.NOT_CONFIGURED, MESSAGE)
    }
}

object DisconnectedMemoLedger {
    const val CONTACTS_NETWORK = false
}
