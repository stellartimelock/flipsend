package org.stellartimelock.send.core

/**
 * Fortress 1.3.8 Send to PC, from STELLAR_VAULT `StellarSend.kt`.
 *
 * Preferred: exported service, action [ACTION], [startService].
 * Fallback: exported activity with CATEGORY_DEFAULT, fire-and-forget [startActivity].
 *
 * Login extras: [EXTRA_USERNAME], [EXTRA_PASSWORD], [EXTRA_TITLE] (label only),
 * [EXTRA_CLEAR_AFTER_SECONDS] (Fortress sends 30), [EXTRA_SOURCE]=[SOURCE],
 * [EXTRA_KIND]=[KIND_LOGIN]. There is no `text` extra.
 *
 * Card extras: [EXTRA_KIND]=[KIND_CARD], username=card number, password=CVV,
 * plus optional holder / number / expMonth / expYear / cvv. v1 copies the
 * username/password pair.
 *
 * Caller: only [FORTRESS_PACKAGE]. A service often cannot see the caller uid,
 * so [EXTRA_SOURCE] must be [SOURCE] when the package is unknown.
 */
object CopyToPc {
    const val ACTION = "org.stellartimelock.stellarsend.action.COPY_TO_PC"
    const val FLIP_SEND_PACKAGE = "org.stellartimelock.stellarsend"
    const val FORTRESS_PACKAGE = "com.stellartimelock.stellarvault"
    const val SOURCE = "stellar-vault"
    const val KIND_LOGIN = "login"
    const val KIND_CARD = "card"
    const val KIND_FILE = FortressBotHandoff.KIND_FILE
    const val ACTION_SEND_FILE = FortressBotHandoff.ACTION_SEND_FILE_TO_PC

    const val EXTRA_USERNAME = "username"
    const val EXTRA_PASSWORD = "password"
    const val EXTRA_TITLE = "title"
    const val EXTRA_CLEAR_AFTER_SECONDS = "clearAfterSeconds"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_KIND = "kind"
    const val EXTRA_HOLDER = "holder"
    const val EXTRA_NUMBER = "number"
    const val EXTRA_EXP_MONTH = "expMonth"
    const val EXTRA_EXP_YEAR = "expYear"
    const val EXTRA_CVV = "cvv"
    const val EXTRA_URI = FortressBotHandoff.EXTRA_URI
    const val EXTRA_FILENAME = FortressBotHandoff.EXTRA_FILENAME
    const val EXTRA_STREAM = FortressBotHandoff.EXTRA_STREAM
    const val EXTRA_NAME = FortressBotHandoff.EXTRA_NAME
    const val EXTRA_CONTENT = FortressBotHandoff.EXTRA_CONTENT

    const val DEFAULT_CLEAR_AFTER_SECONDS = 45
    const val MIN_CLEAR_AFTER_SECONDS = 5
    const val MAX_CLEAR_AFTER_SECONDS = 300

    const val PC_NOT_CONNECTED =
        "The PC is not connected. Open FlipSend on the computer and keep this phone paired."
    const val NOTHING = "Nothing to copy."
    const val NOT_FORTRESS = "That request was not from Fortress."

    data class Request(
        val username: String?,
        val password: String?,
        val title: String?,
        val kind: String?,
        val source: String?,
        val clearAfterSeconds: Int,
        val holder: String? = null,
        val number: String? = null,
        val expMonth: String? = null,
        val expYear: String? = null,
        val cvv: String? = null,
        val uri: String? = null,
        val filename: String? = null,
        val content: String? = null
    ) {
        val fileKind: Boolean = FortressBotHandoff.isFileKind(kind)
        val clipboardText: String? = if (fileKind) null else pair(username, password)
    }

    data class Outcome(val ok: Boolean, val message: String)

    fun request(
        username: String?,
        password: String?,
        title: String? = null,
        kind: String? = null,
        source: String? = null,
        clearAfterSeconds: Int? = null,
        clearAfterSecondsText: String? = null,
        holder: String? = null,
        number: String? = null,
        expMonth: String? = null,
        expYear: String? = null,
        cvv: String? = null,
        uri: String? = null,
        filename: String? = null,
        content: String? = null
    ) = Request(
        username = username,
        password = password,
        title = title,
        kind = kind,
        source = source,
        clearAfterSeconds = clampClearAfter(clearAfterSeconds, clearAfterSecondsText),
        holder = holder,
        number = number,
        expMonth = expMonth,
        expYear = expYear,
        cvv = cvv,
        uri = uri,
        filename = filename,
        content = content
    )

    fun pair(username: String?, password: String?): String? {
        val user = filled(username)
        val pass = filled(password)
        return when {
            user != null && pass != null -> "$user\n$pass"
            pass != null -> pass
            user != null -> user
            else -> null
        }
    }

    fun clampClearAfter(raw: Int?, rawText: String? = null): Int {
        val value = raw ?: rawText?.toIntOrNull() ?: return DEFAULT_CLEAR_AFTER_SECONDS
        return value.coerceIn(MIN_CLEAR_AFTER_SECONDS, MAX_CLEAR_AFTER_SECONDS)
    }

    fun copiedMessage(seconds: Int): String =
        "Copied to the PC clipboard. It will clear in $seconds seconds."

    /**
     * Fortress package always wins. When startService hides the caller, [source]
     * must be [SOURCE]. Any other known package is refused.
     */
    @Suppress("UNUSED_PARAMETER")
    fun allow(caller: String?, source: String?, sameSigner: Boolean = false): Boolean {
        if (caller == FORTRESS_PACKAGE) return true
        if (!caller.isNullOrBlank()) return false
        return source == SOURCE
    }

    fun outcomeFrom(ok: Boolean, code: String, message: String, clearAfterSeconds: Int = DEFAULT_CLEAR_AFTER_SECONDS): Outcome {
        if (ok) return Outcome(true, copiedMessage(clearAfterSeconds))
        val safe = LogRedaction.redact(message)
        return when (code) {
            ErrorCodes.UNKNOWN_PEER, ErrorCodes.IO -> Outcome(false, PC_NOT_CONNECTED)
            ErrorCodes.BAD_REQUEST -> Outcome(false, if (safe.isBlank() || safe == "[redacted]") NOTHING else safe)
            else -> Outcome(false, if (safe.isBlank() || safe == "[redacted]") PC_NOT_CONNECTED else safe)
        }
    }

    fun fileOutcomeFrom(ok: Boolean, code: String, message: String): Outcome {
        if (ok) return Outcome(true, FortressBotHandoff.SENT)
        val safe = LogRedaction.redact(message)
        return when (code) {
            ErrorCodes.UNKNOWN_PEER, ErrorCodes.IO -> Outcome(false, PC_NOT_CONNECTED)
            ErrorCodes.BAD_REQUEST -> Outcome(false, if (safe.isBlank() || safe == "[redacted]") FortressBotHandoff.NOTHING else safe)
            else -> Outcome(false, if (safe.isBlank() || safe == "[redacted]") PC_NOT_CONNECTED else safe)
        }
    }

    private fun filled(value: String?): String? = value?.takeIf { it.isNotEmpty() }
}
