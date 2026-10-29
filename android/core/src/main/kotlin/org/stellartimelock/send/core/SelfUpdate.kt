package org.stellartimelock.send.core

/**
 * FlipSend updating FlipSend. Other apps already install over the paired
 * link. Self-update is the special case: the process dies when the package
 * is replaced, and a background silent session is often blocked on
 * Android 14+ (the same class of bug 1.5.20 fixed for other apps).
 *
 * The APK still travels the normal FlipSend path (direct Wi-Fi, then the
 * hosted relay). This object only decides how that APK is installed and
 * how a pending self-update is remembered across process death.
 */
object SelfUpdate {
    const val FILE_NAME = "pending-self-update.json"
    const val MAX_ATTEMPTS = 3

    data class Pending(
        val historyId: String,
        val path: String,
        val versionCode: Long,
        val versionName: String = "",
        val appName: String = "FlipSend",
        val attempts: Int = 1
    )

    /**
     * USER_ACTION_NOT_REQUIRED is only requested when FlipSend is in the
     * foreground. A background / relay receive uses USER_ACTION_UNSPECIFIED
     * so Android 14+ can show its confirm sheet instead of returning
     * STATUS_FAILURE_BLOCKED.
     */
    fun requestSilent(eligible: Boolean, installerInForeground: Boolean): Boolean =
        eligible && installerInForeground

    fun settled(installedCode: Long, pending: Pending): Boolean =
        installedCode >= 0 && installedCode >= pending.versionCode

    fun shouldRetry(pending: Pending, installedCode: Long, apkExists: Boolean): Boolean {
        if (!apkExists) return false
        if (pending.attempts >= MAX_ATTEMPTS) return false
        return pending.versionCode > installedCode
    }

    fun mayUninstallSelf(): Boolean = false

    fun encode(pending: Pending): ByteArray {
        val body = org.json.JSONObject()
            .put("historyId", pending.historyId)
            .put("path", pending.path)
            .put("versionCode", pending.versionCode)
            .put("versionName", pending.versionName)
            .put("appName", pending.appName)
            .put("attempts", pending.attempts)
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): Pending? {
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) return null
        val parsed = try {
            org.json.JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val historyId = parsed.optString("historyId").trim()
        val path = parsed.optString("path").trim()
        val versionCode = parsed.optLong("versionCode", -1)
        if (historyId.isEmpty() || path.isEmpty() || versionCode < 0) return null
        return Pending(
            historyId = historyId,
            path = path,
            versionCode = versionCode,
            versionName = parsed.optString("versionName"),
            appName = parsed.optString("appName").ifBlank { "FlipSend" },
            attempts = parsed.optInt("attempts", 1).coerceAtLeast(1)
        )
    }
}
