package org.stellartimelock.send.shared

/**
 * Transfer state machine data classes shared across platforms.
 * The actual I/O (socket, TLS) is platform-specific — see
 * NetworkTransport in each platform's source set.
 */

enum class TransferState { AWAITING_CONSENT, IN_PROGRESS, SUCCEEDED, FAILED, CANCELLED }

data class TransferUpdate(
    val id: String,
    val name: String,
    val state: TransferState,
    val bytes: Long,
    val total: Long,
    val bytesPerSecond: Double?,
    val secondsRemaining: Double?,
    val detail: String?,
    val batchIndex: Int = 0,
    val batchCount: Int = 0
)

data class TransferOutcome(
    val state: TransferState,
    val code: String?,
    val message: String?,
    val savedPath: String?,
    val text: String?,
    val chunks: Int
)

/**
 * JSON messages used during a transfer session.
 * These mirror the protocol defined in docs/PROTOCOL.md.
 */
object TransferMessages {
    fun hello(deviceId: String, name: String, fingerprint: String): String =
        buildJson("v", 1, "deviceId", deviceId, "name", trimDeviceName(name), "fingerprint", fingerprint)

    fun helloAck(deviceId: String, fingerprint: String): String =
        buildJson("deviceId", deviceId, "fingerprint", fingerprint)

    fun offer(
        transferId: String, kind: String, name: String,
        size: Long, sha256: String, mime: String,
        index: Int = 0, count: Int = 0
    ): String = buildJson(
        "transferId", transferId, "kind", kind, "name", name,
        "size", size, "sha256", sha256, "mime", mime,
        "index", if (index > 0) index else null,
        "count", if (count > 0) count else null
    )

    fun accept(transferId: String, offset: Long? = null): String =
        buildJson("transferId", transferId, "offset", offset)

    fun reject(transferId: String, reason: String): String =
        buildJson("transferId", transferId, "reason", reason)

    fun complete(transferId: String, sha256: String): String =
        buildJson("transferId", transferId, "sha256", sha256)

    fun error(code: String, message: String): String =
        buildJson("code", code, "message", message)

    fun cancel(transferId: String): String =
        buildJson("transferId", transferId)
}

/** Minimal JSON value extractor — only what the protocol needs. */
object SimpleJson {
    fun getString(json: String, key: String): String? {
        val pattern = "\"$key\""
        val idx = json.indexOf(pattern)
        if (idx < 0) return null
        var i = idx + pattern.length
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != ':') return null
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length) return null
        return if (json[i] == '"') {
            val end = findStringEnd(json, i + 1)
            json.substring(i + 1, end)
        } else {
            val end = json.indexOfFirst(startAt = i) { it == ',' || it == '}' || it.isWhitespace() }
            json.substring(i, if (end < 0) json.length else end).trim().let {
                if (it == "null") null else it
            }
        }
    }

    fun getLong(json: String, key: String): Long? = getString(json, key)?.toLongOrNull()
    fun getInt(json: String, key: String): Int? = getString(json, key)?.toIntOrNull()
    fun getDouble(json: String, key: String): Double? = getString(json, key)?.toDoubleOrNull()
    fun getBool(json: String, key: String): Boolean? = getString(json, key)?.toBooleanStrictOrNull()

    private fun findStringEnd(json: String, from: Int): Int {
        var i = from
        while (i < json.length) {
            when (json[i]) {
                '"' -> return i
                '\\' -> i += 2
                else -> i++
            }
        }
        return json.length
    }

    private fun String.indexOfFirst(startAt: Int, predicate: (Char) -> Boolean): Int {
        for (i in startAt until length) if (predicate(this[i])) return i
        return -1
    }
}

object RetryPolicy {
    const val MAX_ATTEMPTS = 5

    fun shouldRetry(remoteCompleted: Boolean, cancelled: Boolean, attempt: Int, transientFailure: Boolean) =
        !remoteCompleted && !cancelled && attempt < MAX_ATTEMPTS && transientFailure

    fun delayMs(failedAttempt: Int): Long {
        val shift = (failedAttempt - 1).coerceIn(0, 4)
        return 400L shl shift
    }
}
