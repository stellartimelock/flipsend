package org.stellartimelock.send.shared

/**
 * Relay room names, endpoint parsing, and join/reply protocol.
 *
 * Ported from android/core RelayPlans.kt + AdapterRank.kt.
 * Pure Kotlin — no platform dependencies.
 */

object RelayRoom {
    /** Room used once both devices are paired. Deterministic from both IDs. */
    fun paired(a: String, b: String): String {
        val left = if (a <= b) a else b
        val right = if (a <= b) b else a
        return sha256Hex("stellarsend-room-v1|$left|$right".encodeToByteArray())
    }

    /** Temporary room used during the initial QR pairing handshake. */
    fun pairing(showingDeviceId: String): String =
        sha256Hex("stellarsend-pair-v1|$showingDeviceId".encodeToByteArray())

    /** Store-and-forward bot mailbox. Keyed by pairing device ids, never the seed. */
    fun botHub(a: String, b: String): String {
        val left = if (a <= b) a else b
        val right = if (a <= b) b else a
        return sha256Hex("stellarsend-bothub-v1|$left|$right".encodeToByteArray())
    }

    fun isToken(room: String?): Boolean =
        room != null && room.length == 64 && room.all { it in '0'..'9' || it in 'a'..'f' }
}

data class RelayEndpoint(val secure: Boolean, val host: String, val port: Int, val path: String)

object RelayAddress {
    fun parse(raw: String?): RelayEndpoint? {
        val text = raw?.trim().orEmpty()
        val schemeSplit = text.split("://", limit = 2)
        if (schemeSplit.size != 2) return null
        val secure = when (schemeSplit[0].lowercase()) {
            "wss", "https" -> true
            "ws", "http" -> false
            else -> return null
        }
        val rest = schemeSplit[1]
        if (rest.isEmpty()) return null
        val slash = rest.indexOf('/')
        val authority = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0 || rest.substring(slash).isBlank() || rest.substring(slash) == "/") {
            "/v1"
        } else {
            rest.substring(slash)
        }
        val hostPort = splitHost(authority) ?: return null
        if (!secure && hostPort.first != "localhost" && hostPort.first != "127.0.0.1") return null
        val port = hostPort.second ?: if (secure) 443 else 80
        if (port !in 1..65535) return null
        return RelayEndpoint(secure, hostPort.first, port, path)
    }

    private fun splitHost(authority: String): Pair<String, Int?>? {
        if (authority.isBlank() || '@' in authority) return null
        val colon = authority.lastIndexOf(':')
        if (colon > 0 && authority.indexOf(':') == colon) {
            val host = authority.substring(0, colon)
            val port = authority.substring(colon + 1).toIntOrNull() ?: return null
            return host to port
        }
        return authority to null
    }
}

/** Relay join/reply message codec. Matches RelayJoin in android/core. */
object RelayJoin {
    fun write(room: String, role: String, entitlementKey: String? = null): String {
        require(RelayRoom.isToken(room)) { "Relay room is invalid." }
        require(role == "listen" || role == "dial") { "Relay role is invalid." }
        val parts = mutableListOf("\"v\":1", "\"room\":${jsonString(room)}", "\"role\":${jsonString(role)}")
        if (!entitlementKey.isNullOrBlank() && entitlementKey.length == 64 && entitlementKey.all { it in '0'..'9' || it in 'a'..'f' }) {
            parts += "\"entitlementKey\":${jsonString(entitlementKey)}"
        }
        return "{${parts.joinToString(",")}}"
    }

    fun parseReply(json: String): RelayReply = RelayReply(
        ok = SimpleJson.getBool(json, "ok") == true,
        error = SimpleJson.getString(json, "error")?.ifBlank { null },
        message = SimpleJson.getString(json, "message")?.ifBlank { null },
        tier = SimpleJson.getString(json, "tier")?.ifBlank { null },
        used = SimpleJson.getLong(json, "used"),
        quota = SimpleJson.getBool(json, "quota") == true
    )
}

data class RelayReply(
    val ok: Boolean,
    val error: String? = null,
    val message: String? = null,
    val tier: String? = null,
    val used: Long? = null,
    val quota: Boolean = false
)

object RelayWake {
    const val GAVE_UP = "The relay is still waking up. Same-Wi-Fi transfers stay free. Try again in a minute."
    private val delaysMs = longArrayOf(0L, 2_000L, 5_000L, 10_000L, 15_000L, 20_000L)
    const val ATTEMPT_MS = 20_000L

    data class Attempt(val delayMs: Long, val timeoutMs: Long)

    fun attempts(): List<Attempt> = delaysMs.map { Attempt(it, ATTEMPT_MS) }
}

/** Per-session relay quota/plan announcement included in the HELLO frame. */
data class RelayAnnouncement(
    val plan: String? = null,
    val quotaKey: String? = null,
    val relayUsed: Long? = null,
    val relayLimit: Long? = null
)
