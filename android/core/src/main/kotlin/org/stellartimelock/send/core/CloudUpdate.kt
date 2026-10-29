package org.stellartimelock.send.core

import org.json.JSONObject

/**
 * A signed FlipSend build the phone can pull itself. Check only when the
 * app is opened or when a push says a build is ready. Never poll.
 */
object CloudUpdate {
    const val MANIFEST_URL = "https://flipsend.app/update.json"
    const val ACTION_CHECK = "org.stellartimelock.stellarsend.CHECK_UPDATE"
    const val SIDELOAD_SIGNER = "7994727d"

    data class Manifest(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val sha256: String,
        val signerPrefix: String
    )

    fun parse(json: String): Manifest? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val versionCode = root.optInt("versionCode", 0)
        val apkUrl = root.optString("apkUrl").trim()
        if (versionCode < 1 || apkUrl.isBlank()) return null
        if (!apkUrl.startsWith("https://")) return null
        return Manifest(
            versionName = root.optString("versionName").ifBlank { versionCode.toString() },
            versionCode = versionCode,
            apkUrl = apkUrl,
            sha256 = root.optString("sha256").trim().lowercase(),
            signerPrefix = root.optString("signerPrefix").trim().lowercase().ifBlank { SIDELOAD_SIGNER }
        )
    }

    fun newerThan(manifest: Manifest, installedCode: Int): Boolean =
        manifest.versionCode > installedCode

    fun signerMatches(installedPrefix: String?, manifest: Manifest): Boolean {
        val have = installedPrefix?.take(8)?.lowercase().orEmpty()
        val want = manifest.signerPrefix.take(8)
        if (have.isBlank() || want.isBlank()) return false
        return have == want
    }

    /** One check per process unless a push asks again. */
    fun shouldCheck(alreadyChecked: Boolean, pushAsked: Boolean): Boolean =
        pushAsked || !alreadyChecked
}
