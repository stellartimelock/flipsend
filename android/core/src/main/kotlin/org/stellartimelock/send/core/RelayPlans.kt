package org.stellartimelock.send.core

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Free relay fallback. Local transfers are never part of this table.
 * The cap lives in relay/tiers.json so a limit change is not a code change.
 */
data class RelayTier(
    val id: String,
    val title: String,
    val productId: String?,
    val basePlanId: String?,
    val priceUsd: BigDecimal,
    val relayGb: BigDecimal,
    val relayBytes: Long,
    val local: String,
    val pcIncluded: String,
    val speed: String,
    val recommended: Boolean
) {
    val paid: Boolean get() = priceUsd.signum() > 0

    fun relayLabel(): String = relayGb.stripTrailingZeros().toPlainString() + " GB"

    fun fallbackPrice(): String =
        if (!paid) "Included" else "$" + priceUsd.setScale(2, RoundingMode.HALF_UP).toPlainString() + " / month"

    fun egressCost(perGb: BigDecimal): BigDecimal = relayGb.multiply(perGb)

    fun netRevenue(playFeeRate: BigDecimal): BigDecimal =
        priceUsd.multiply(BigDecimal.ONE.subtract(playFeeRate))

    /** A paid tier never loses money when worst-case egress is within the price after the store fee. */
    fun staysProfitable(perGb: BigDecimal, playFeeRate: BigDecimal): Boolean =
        !paid || egressCost(perGb).compareTo(netRevenue(playFeeRate)) <= 0
}

data class TierTable(
    val egressUsdPerGb: BigDecimal,
    val playFeeRate: BigDecimal,
    val bytesPerGb: Long,
    val appPriceUsd: BigDecimal,
    val packageName: String,
    val tiers: List<RelayTier>,
    val fairUseGb: BigDecimal = BigDecimal("0.1"),
    val fairUseBytes: Long = 100_000_000L
) {
    val free: RelayTier get() = tiers.first { it.id == "free" }
    val recommended: RelayTier? get() = tiers.firstOrNull { it.recommended }
    val paid: List<RelayTier> get() = tiers.filter { it.paid }

    fun byId(id: String?): RelayTier? = tiers.firstOrNull { it.id == id }
    fun byProduct(productId: String?): RelayTier? =
        productId?.let { id -> tiers.firstOrNull { it.productId == id } }

    fun highest(productIds: Collection<String>): RelayTier {
        val matches = productIds.mapNotNull { byProduct(it) }
        return matches.maxByOrNull { it.relayBytes } ?: free
    }

    companion object {
        fun parse(json: String): TierTable {
            val root = JSONObject(json)
            val bytesPerGb = root.getLong("bytesPerGb")
            require(bytesPerGb > 0) { "bytesPerGb must be positive." }
            val array = root.getJSONArray("tiers")
            val tiers = buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val gb = BigDecimal(item.get("relayGb").toString())
                    val bytes = gb.multiply(BigDecimal.valueOf(bytesPerGb)).setScale(0, RoundingMode.HALF_UP).longValueExact()
                    add(
                        RelayTier(
                            id = item.getString("id"),
                            title = item.getString("title"),
                            productId = item.optString("productId").ifBlank { null },
                            basePlanId = item.optString("basePlanId").ifBlank { null },
                            priceUsd = BigDecimal(item.get("priceUsd").toString()),
                            relayGb = gb,
                            relayBytes = bytes,
                            local = item.optString("local", "Unlimited"),
                            pcIncluded = item.optString("pcIncluded", "Included"),
                            speed = item.optString("speed", "Full speed"),
                            recommended = item.optBoolean("recommended", false)
                        )
                    )
                }
            }
            require(tiers.any { it.id == "free" }) { "The plan table needs a free tier." }
            require(tiers.map { it.id }.distinct().size == tiers.size) { "Tier ids must be unique." }
            val fairGb = if (root.has("fairUseGb")) BigDecimal(root.get("fairUseGb").toString()) else BigDecimal("0.1")
            val fairBytes = fairGb.multiply(BigDecimal.valueOf(bytesPerGb)).setScale(0, RoundingMode.HALF_UP).longValueExact()
            return TierTable(
                egressUsdPerGb = BigDecimal(root.get("egressUsdPerGb").toString()),
                playFeeRate = BigDecimal(root.get("playFeeRate").toString()),
                bytesPerGb = bytesPerGb,
                appPriceUsd = BigDecimal(root.get("appPriceUsd").toString()),
                packageName = root.optString("packageName", RelayPlans.PACKAGE),
                tiers = tiers,
                fairUseGb = if (fairGb.signum() > 0) fairGb else BigDecimal("0.1"),
                fairUseBytes = if (fairBytes > 0) fairBytes else 100_000_000L
            )
        }
    }
}

object RelayPlans {
    const val PACKAGE = "org.stellartimelock.stellarsend"
    const val DEFAULT_RELAY = "wss://stellar-send-relay.onrender.com/v1"
    const val QUOTA = "quota"
    const val WAKE = "relay_waking"
    const val QUOTA_MESSAGE =
        "This month's relay data is used up. Wi-Fi transfers still work."
    const val FAIR_USE_MESSAGE = QUOTA_MESSAGE
    const val BUSY_MESSAGE = "Relay busy, Wi-Fi transfers still work"
    const val RESETS = "Resets on the 1st."

    /** Only relay egress counts. Direct Wi-Fi, LAN, and USB do not. */
    fun countsRelay(path: String?): Boolean = path == Reachability.RELAY

    fun entitlementKey(deviceId: String): String =
        sha256Hex("stellarsend-quota-v1|$deviceId".toByteArray(Charsets.UTF_8))

    /** Same-Wi-Fi and direct transfers are never gated and never capped. */
    fun localTransfersAllowed(): Boolean = true

    fun overCap(used: Long, limit: Long, enforced: Boolean): Boolean =
        enforced && limit > 0 && used >= limit

    /** Usage counted in a previous billing period does not carry forward. */
    fun bytesThisPeriod(storedPeriod: String?, storedBytes: Long, period: String): Long =
        if (storedPeriod == period) storedBytes.coerceAtLeast(0) else 0
}

data class WakeAttempt(val delayMs: Long, val timeoutMs: Long)

object RelayWake {
    const val STATUS = "Waking relay…"
    const val GAVE_UP = "The relay is still waking up. Same-Wi-Fi transfers stay free. Try again in a minute."
    const val PHONE_MISSING = "The relay is up, but the phone did not join the room. FlipSend on the phone rejoins on its own. Try the send again."
    const val NO_PEER = "No peer in the room yet."
    const val LISTEN_WAIT_MS = 120_000L
    val REJOIN_BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 15_000, 30_000, 60_000)
    const val STABLE_JOIN_MS = 30_000L
    const val CLIENT_PING_MS = 45_000L
    const val WIFI_REJOIN_MS = 1_000L
    const val DROP_REJOIN_MS = 1_000L
    const val BUDGET_MS = 90_000L
    const val ATTEMPT_MS = 20_000L

    /** Health succeeded, so this is not a sleeping relay. */
    fun afterAttempts(relayAwake: Boolean): String = if (relayAwake) PHONE_MISSING else GAVE_UP

    /** Idle rejoin: 1, 2, 5, 15, 30, 60 s cap. */
    fun rejoinMs(attempt: Int): Long {
        val index = attempt.coerceIn(0, REJOIN_BACKOFF_MS.lastIndex)
        return REJOIN_BACKOFF_MS[index]
    }

    fun rejoinMs(onWifi: Boolean): Long = rejoinMs(0)

    fun dropRejoinMs(): Long = rejoinMs(0)

    fun dropRejoinMs(attempt: Int): Long = rejoinMs(attempt)
    val delaysMs: LongArray = longArrayOf(0, 2_000, 5_000, 10_000, 15_000, 20_000)

    fun attempts(budgetMs: Long = BUDGET_MS, attemptMs: Long = ATTEMPT_MS): List<WakeAttempt> {
        val result = mutableListOf<WakeAttempt>()
        var spent = 0L
        for (delay in delaysMs) {
            if (spent + delay >= budgetMs) break
            val timeout = minOf(attemptMs, budgetMs - spent - delay)
            if (timeout < 1_000) break
            result += WakeAttempt(delay, timeout)
            spent += delay + timeout
        }
        return result
    }

    fun budgetOf(plan: List<WakeAttempt>): Long = plan.sumOf { it.delayMs + it.timeoutMs }
}

data class RelayAuth(
    val purchaseToken: String? = null,
    val productId: String? = null,
    val basePlanId: String? = null,
    val packageName: String? = null,
    val entitlementKey: String? = null,
    val account: String? = null,
    val accountProof: String? = null
) {
    fun sanitized(): RelayAuth? {
        val token = purchaseToken?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4096 }
        val key = entitlementKey?.trim()?.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
        val email = RelayMeter.normalizeEmail(account)
        val proof = accountProof?.trim()?.lowercase()?.takeIf { email != null && it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
        if (token == null && key == null && email == null && productId.isNullOrBlank()) return null
        return copy(
            purchaseToken = token,
            entitlementKey = key,
            account = email,
            accountProof = proof,
            packageName = packageName ?: RelayPlans.PACKAGE
        )
    }
}

data class RelayReply(
    val ok: Boolean,
    val error: String? = null,
    val message: String? = null,
    val tier: String? = null,
    val used: Long? = null,
    val limit: Long? = null,
    val enforced: Boolean? = null,
    val seen: String? = null,
    val unlimited: Boolean = false
) {
    val quota: Boolean get() = error == RelayPlans.QUOTA
}

data class RelayAnnouncement(
    val plan: String? = null,
    val quotaKey: String? = null,
    val relayUsed: Long? = null,
    val relayLimit: Long? = null
)

object RelayJoin {
    fun write(room: String, role: String, auth: RelayAuth? = null, deviceId: String? = null): String {
        require(RelayRoom.isToken(room)) { "Relay room is invalid." }
        require(role == "listen" || role == "dial") { "Relay role is invalid." }
        val json = JSONObject().put("v", 1).put("room", room).put("role", role)
        val device = deviceId?.trim()?.takeIf { it.length == 36 }
        if (device != null) json.put("device", device)
        putAuth(json, auth?.sanitized())
        return json.toString()
    }

    fun usage(auth: RelayAuth?): String {
        val json = JSONObject().put("v", 1).put("op", "usage")
        putAuth(json, auth?.sanitized())
        return json.toString()
    }

    fun bot(room: String, role: String, auth: RelayAuth? = null): String {
        require(RelayRoom.isToken(room)) { "Relay room is invalid." }
        require(role == "put" || role == "get") { "Relay role is invalid." }
        val json = JSONObject().put("v", 1).put("op", "bot").put("room", room).put("role", role)
        putAuth(json, auth?.sanitized())
        return json.toString()
    }

    fun parseReply(json: String): RelayReply {
        val root = JSONObject(json)
        return RelayReply(
            ok = root.optBoolean("ok", false),
            error = root.optString("error").ifBlank { null },
            message = root.optString("message").ifBlank { null },
            tier = root.optString("tier").ifBlank { null },
            used = if (root.has("used")) root.optLong("used") else null,
            limit = if (root.has("limit")) root.optLong("limit") else null,
            enforced = if (root.has("enforced")) root.optBoolean("enforced") else null,
            seen = root.optString("seen").takeIf { DirectLink.isEndpoint(it) },
            unlimited = root.optBoolean("unlimited", false)
        )
    }

    private fun putAuth(json: JSONObject, auth: RelayAuth?) {
        if (auth == null) return
        auth.purchaseToken?.let { json.put("purchaseToken", it) }
        auth.productId?.takeIf { it.isNotBlank() }?.let { json.put("productId", it) }
        auth.basePlanId?.takeIf { it.isNotBlank() }?.let { json.put("basePlanId", it) }
        auth.packageName?.let { json.put("packageName", it) }
        auth.entitlementKey?.let { json.put("entitlementKey", it) }
        auth.account?.let { json.put("account", it) }
        auth.accountProof?.let { json.put("accountProof", it) }
    }
}

object RelayMeter {
    const val FREE_BYTES = 100_000_000L

    fun row(used: Long, limit: Long, unlimited: Boolean): String {
        if (unlimited) return "Relay: unlimited"
        val cap = if (limit > 0) limit else FREE_BYTES
        return "Relay: ${mb(used)} of ${mb(cap)} MB used this month"
    }

    fun homeMeta(used: Long, limit: Long, unlimited: Boolean, sending: String, vpnLine: String? = null): String {
        val relay = if (unlimited) "Relay unlimited" else {
            val cap = if (limit > 0) limit else FREE_BYTES
            "Relay ${mb(used)} of ${mb(cap)} MB"
        }
        val send = sending.trim().ifBlank { TransferQueue.EMPTY }
        val short = if (send == TransferQueue.EMPTY) "Nothing sending" else send
        val base = "$relay · $short"
        return if (vpnLine.isNullOrBlank()) base else "$vpnLine · $base"
    }

    fun mb(bytes: Long): Long {
        val value = bytes.coerceAtLeast(0)
        return (value + 500_000L) / 1_000_000L
    }

    fun normalizeEmail(raw: String?): String? {
        val email = raw?.trim()?.lowercase() ?: return null
        val at = email.indexOf('@')
        if (at <= 0 || at != email.lastIndexOf('@') || at >= email.length - 1) return null
        if (email.length > 320 || email.any { it.isWhitespace() || it.code < 32 }) return null
        return email
    }

    fun proof(secret: String, email: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal("stellarsend-unlimited-v1|$email".toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }
}

object UsageText {
    fun label(used: Long, limit: Long, bytesPerGb: Long): String {
        if (limit <= 0) return "Usage appears after the relay answers."
        return formatGb(used, bytesPerGb) + " of " + formatGb(limit, bytesPerGb) + " relay this month"
    }

    fun fraction(used: Long, limit: Long): Float {
        if (limit <= 0) return 0f
        return (used.coerceAtLeast(0).toDouble() / limit.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    fun formatGb(bytes: Long, bytesPerGb: Long): String {
        if (bytesPerGb <= 0) return "0 GB"
        val gb = BigDecimal.valueOf(bytes.coerceAtLeast(0)).divide(BigDecimal.valueOf(bytesPerGb), 2, RoundingMode.HALF_UP)
        val text = gb.stripTrailingZeros().toPlainString()
        return "$text GB"
    }
}

class RelayQuotaException(message: String) : Exception(message)

/** Not an IOException, so a transfer retry does not spend the wake budget twice. */
class RelayWakeException(message: String, cause: Throwable? = null) : Exception(message, cause)
