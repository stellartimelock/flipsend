package org.stellartimelock.send

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.stellartimelock.send.core.PhoneInvite
import java.io.File

/** One-time email invite so another phone can install FlipSend. */
object InviteShare {
    private const val PREFS = "flipsend-invite"

    fun compose(context: Context): Intent {
        val token = PhoneInvite.mint()
        save(context, token)
        val body = PhoneInvite.emailBody(token.code, PhoneInvite.redeemLink(token.code))
        val apk = stagedApk(context)
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, PhoneInvite.emailSubject())
            putExtra(Intent.EXTRA_TEXT, body)
            if (apk != null) {
                type = "application/vnd.android.package-archive"
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.stellarsend.files", apk)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
        return Intent.createChooser(intent, "Share FlipSend")
    }

    fun redeem(context: Context, code: String): String {
        val token = load(context)
        val normalized = PhoneInvite.normalize(code)
        if (!PhoneInvite.looksLike(normalized)) {
            return "That invite code is not 12 letters and numbers."
        }
        if (token != null && PhoneInvite.normalize(token.code) == normalized) {
            if (!PhoneInvite.live(token, System.currentTimeMillis())) {
                return "That invite is used up or older than 24 hours."
            }
        }
        CloudPull.onOpened(context, pushAsked = true)
        return "Checking for a signed FlipSend build. Install when the phone asks."
    }

    fun hint(): String = "Email a one-time code. The other phone installs FlipSend, then taps Enter invite."

    private fun stagedApk(context: Context): File? {
        val source = File(context.applicationInfo.sourceDir)
        if (!source.isFile) return null
        val outgoing = File(context.cacheDir, "outgoing").apply { mkdirs() }
        val dest = File(outgoing, "FlipSend.apk")
        return runCatching {
            source.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            dest.takeIf { it.isFile && it.length() > 1_000 }
        }.getOrNull()
    }

    private fun save(context: Context, token: PhoneInvite.Token) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", JSONObject()
                .put("code", token.code)
                .put("createdAtMillis", token.createdAtMillis)
                .put("used", token.used)
                .toString())
            .apply()
    }

    private fun load(context: Context): PhoneInvite.Token? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return PhoneInvite.Token(
            json.optString("code"),
            json.optLong("createdAtMillis"),
            json.optBoolean("used")
        )
    }
}
