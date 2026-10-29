package org.stellartimelock.send.core

/**
 * Fortress password-bot file handoff. FlipSend only moves the encrypted
 * file. It never decrypts, logs, or keeps the bytes after the transfer.
 *
 * Phone receive: a paired-PC file named `fortress-bot-request.json` or
 * `fortress-bot-failed.json` (any `fortress-bot-*.json` except the
 * response) is handed to Fortress with [ACTION_BOT_HANDOFF]. Extras are
 * [EXTRA_NAME], [EXTRA_CONTENT] (UTF-8 text), plus the FileProvider URI.
 *
 * Phone send: Fortress may fire [ACTION_SEND_FILE_TO_PC] or
 * [CopyToPc.ACTION] with [KIND_FILE], [EXTRA_NAME], and [EXTRA_CONTENT]
 * inline. FlipSend writes a temp file, sends it, and deletes the copy.
 */
object FortressBotHandoff {
    const val ACTION_BOT_HANDOFF = "com.stellartimelock.stellarvault.action.BOT_HANDOFF"
    const val ACTION_SEND_FILE_TO_PC = "org.stellartimelock.stellarsend.action.SEND_FILE_TO_PC"
    const val KIND_FILE = "file"
    const val REQUEST_NAME = "fortress-bot-request.json"
    const val FAILED_NAME = "fortress-bot-failed.json"
    const val RESPONSE_NAME = "fortress-bot-response.json"
    const val EXTRA_NAME = "name"
    const val EXTRA_CONTENT = "content"
    const val EXTRA_FILENAME = "filename"
    const val EXTRA_URI = "uri"
    const val EXTRA_STREAM = "android.intent.extra.STREAM"
    const val MIME_JSON = "application/json"
    const val SENT = "Sent the Fortress file to the PC."
    const val NOTHING = "Nothing to send."
    const val HANDED = "Handed a Fortress file to Fortress."
    const val REJECTED_UNPAIRED = "Rejected a Fortress file from an unpaired device."
    const val FORTRESS_MISSING = "Fortress is not installed."
    const val FORTRESS_CERT_PREFIX = "fac61745"
    const val DEBUG_CERT_PREFIX = "7994727d"

    private const val PREFIX = "fortress-bot-"
    private const val SUFFIX = ".json"

    fun isIncomingName(raw: String?): Boolean {
        val name = FileNames.sanitize(raw).lowercase()
        return name.startsWith(PREFIX) && name.endsWith(SUFFIX) && name.length > PREFIX.length + SUFFIX.length
    }

    fun isResponseName(raw: String?): Boolean =
        FileNames.sanitize(raw).equals(RESPONSE_NAME, ignoreCase = true)

    fun isFileKind(kind: String?): Boolean =
        kind.equals(KIND_FILE, ignoreCase = true)

    /**
     * File sends require the Fortress package and a verified signing cert.
     * The login [CopyToPc.allow] source fallback is not used here.
     */
    fun allowFileCaller(caller: String?, signed: Boolean): Boolean =
        caller == CopyToPc.FORTRESS_PACKAGE && signed

    fun knownFortressCert(sha256Hex: String?): Boolean {
        val digest = sha256Hex?.lowercase()?.replace(":", "") ?: return false
        return digest.startsWith(FORTRESS_CERT_PREFIX) || digest.startsWith(DEBUG_CERT_PREFIX)
    }

    /** Auto-accept only from the paired PC. Unpaired peers are rejected. */
    fun acceptOffer(name: String, paired: Boolean): Boolean? =
        if (isIncomingName(name)) paired else null

    fun outgoingName(raw: String? = null): String {
        val name = FileNames.sanitize(raw)
        return if (isIncomingName(name)) name else RESPONSE_NAME
    }

    /** Incoming PC files except the phone→PC response. */
    fun handOffToFortress(raw: String?): Boolean = isIncomingName(raw) && !isResponseName(raw)

    fun hideFromReceivedList(name: String?): Boolean = isIncomingName(name)

    fun stageOutgoing(directory: java.io.File, rawName: String?, content: String): java.io.File {
        val dest = FileNames.exact(directory, outgoingName(rawName))
        dest.writeText(content, Charsets.UTF_8)
        return dest
    }

    fun logLine(event: String, rawName: String?): String =
        "$event ${FileNames.sanitize(rawName)}"

    fun containsPayload(logLine: String, payload: String): Boolean {
        if (payload.isEmpty()) return false
        return payload in logLine
    }
}
