package org.stellartimelock.send

import android.content.Context
import org.stellartimelock.send.core.RelayMeter
import org.stellartimelock.send.core.RelayAnnouncement
import org.stellartimelock.send.core.RelayAuth
import org.stellartimelock.send.core.RelayPlans
import org.stellartimelock.send.core.RelayReply
import org.stellartimelock.send.core.TierTable

data class PlanSnapshot(
    val tierId: String,
    val title: String,
    val productId: String?,
    val basePlanId: String?,
    val purchaseToken: String?,
    val used: Long,
    val limit: Long,
    val enforced: Boolean,
    val unlimited: Boolean = false
) {
    val paid: Boolean get() = !productId.isNullOrBlank()
}

/**
 * What the phone last learned from Play Billing and from the relay.
 * The purchase token stays on the phone. It is sent only on the relay join.
 */
object PlanStatus {
    private const val PREFS = "stellarsend"
    private const val TIER = "plan_tier"
    private const val TITLE = "plan_title"
    private const val PRODUCT = "plan_product"
    private const val BASE = "plan_base"
    private const val TOKEN = "plan_token"
    private const val USED = "plan_used"
    private const val LIMIT = "plan_limit"
    private const val ENFORCED = "plan_enforced"
    private const val UNLIMITED = "plan_unlimited"
    private const val ACCOUNT = "relay_account"
    private const val ACCOUNT_SECRET = "relay_account_secret"

    @Volatile private var tableCache: TierTable? = null

    fun table(context: Context): TierTable? {
        tableCache?.let { return it }
        val parsed = runCatching {
            context.assets.open("tiers.json").bufferedReader().use { TierTable.parse(it.readText()) }
        }.getOrNull()
        if (parsed != null) tableCache = parsed
        return parsed
    }

    fun snapshot(context: Context): PlanSnapshot {
        val prefs = prefs(context)
        return PlanSnapshot(
            tierId = prefs.getString(TIER, null)?.ifBlank { null } ?: "free",
            title = prefs.getString(TITLE, null)?.ifBlank { null } ?: "Free",
            productId = prefs.getString(PRODUCT, null)?.ifBlank { null },
            basePlanId = prefs.getString(BASE, null)?.ifBlank { null },
            purchaseToken = prefs.getString(TOKEN, null)?.ifBlank { null },
            used = prefs.getLong(USED, 0).coerceAtLeast(0),
            limit = prefs.getLong(LIMIT, 0).coerceAtLeast(0),
            enforced = prefs.getBoolean(ENFORCED, false),
            unlimited = prefs.getBoolean(UNLIMITED, false)
        )
    }

    fun entitlementKey(context: Context): String {
        val material = IdentityVault(context).loadOrCreate(UserSaveVault(context).deviceName())
        return RelayPlans.entitlementKey(material.deviceId)
    }

    fun auth(context: Context): RelayAuth? {
        val snap = snapshot(context)
        val email = RelayMeter.normalizeEmail(accountEmail(context))
        val secret = accountSecret(context)
        val proof = if (email != null && !secret.isNullOrBlank()) RelayMeter.proof(secret, email) else null
        return RelayAuth(
            purchaseToken = snap.purchaseToken,
            productId = snap.productId,
            basePlanId = snap.basePlanId,
            packageName = context.packageName,
            entitlementKey = runCatching { entitlementKey(context) }.getOrNull(),
            account = email,
            accountProof = proof
        ).sanitized()
    }

    fun accountEmail(context: Context): String = prefs(context).getString(ACCOUNT, null).orEmpty()

    fun accountSecret(context: Context): String = prefs(context).getString(ACCOUNT_SECRET, null).orEmpty()

    fun rememberAccount(context: Context, email: String, secret: String) {
        prefs(context).edit()
            .putString(ACCOUNT, email.trim())
            .putString(ACCOUNT_SECRET, secret.trim())
            .apply()
    }

    fun announcement(context: Context): RelayAnnouncement {
        val snap = snapshot(context)
        val key = runCatching { entitlementKey(context) }.getOrNull()
        return RelayAnnouncement(
            plan = snap.title,
            quotaKey = key,
            relayUsed = snap.used,
            relayLimit = snap.limit.takeIf { it > 0 }
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun rememberPurchase(context: Context, tierId: String, title: String, productId: String?, basePlanId: String?, token: String?, relayBytes: Long) {
        prefs(context).edit()
            .putString(TIER, tierId)
            .putString(TITLE, title)
            .putString(PRODUCT, productId.orEmpty())
            .putString(BASE, basePlanId.orEmpty())
            .putString(TOKEN, token.orEmpty())
            .apply()
    }

    @Suppress("UNUSED_PARAMETER")
    fun rememberFree(context: Context, title: String, relayBytes: Long) {
        prefs(context).edit()
            .putString(TIER, "free")
            .putString(TITLE, title)
            .remove(PRODUCT)
            .remove(BASE)
            .remove(TOKEN)
            .apply()
    }

    fun rememberUsage(context: Context, used: Long, limit: Long, enforced: Boolean? = null, unlimited: Boolean? = null) {
        val edit = prefs(context).edit()
            .putLong(USED, used.coerceAtLeast(0))
            .putLong(LIMIT, limit.coerceAtLeast(0))
        if (enforced != null) edit.putBoolean(ENFORCED, enforced)
        if (unlimited != null) edit.putBoolean(UNLIMITED, unlimited)
        edit.apply()
    }

    fun noteUsage(context: Context, reply: RelayReply) {
        if (reply.unlimited) {
            rememberUsage(context, reply.used ?: 0, 0, false, unlimited = true)
            return
        }
        if (reply.used == null && reply.limit == null) return
        val snap = snapshot(context)
        rememberUsage(
            context,
            reply.used ?: snap.used,
            reply.limit ?: snap.limit,
            reply.enforced,
            unlimited = false
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
