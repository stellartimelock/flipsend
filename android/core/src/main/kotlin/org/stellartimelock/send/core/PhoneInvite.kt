package org.stellartimelock.send.core

import java.security.SecureRandom

/**
 * One-time email invite so one phone can send FlipSend to another.
 * The token is burned after the first successful transfer or after a day.
 */
object PhoneInvite {
    const val PREFIX = "stellarsend-invite-v1|"
    const val TTL_MS = 24L * 60L * 60L * 1000L
    const val TOKEN_CHARS = 12

    data class Token(
        val code: String,
        val createdAtMillis: Long,
        val used: Boolean = false
    )

    fun mint(nowMs: Long = System.currentTimeMillis(), random: SecureRandom = SecureRandom()): Token {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val raw = CharArray(TOKEN_CHARS) { alphabet[random.nextInt(alphabet.length)] }
        return Token(String(raw), nowMs)
    }

    fun normalize(code: String?): String =
        code.orEmpty().trim().uppercase().replace("-", "").replace(" ", "")

    fun looksLike(code: String?): Boolean {
        val token = normalize(code)
        return token.length == TOKEN_CHARS && token.all { it.isLetterOrDigit() }
    }

    fun room(code: String): String = RelayRoom.invite(normalize(code))

    fun live(token: Token?, nowMs: Long): Boolean {
        if (token == null || token.used) return false
        if (nowMs < token.createdAtMillis) return false
        return nowMs - token.createdAtMillis <= TTL_MS
    }

    fun burn(token: Token): Token = token.copy(used = true)

    fun emailSubject(): String = "Your FlipSend invite"

    fun emailBody(code: String, link: String): String {
        val pretty = normalize(code).chunked(4).joinToString("-")
        return buildString {
            appendLine("Install FlipSend, then open Settings and tap Enter invite.")
            appendLine()
            appendLine("One-time code: $pretty")
            appendLine()
            appendLine("Or open $link")
            appendLine()
            appendLine("The code works once, and it expires in 24 hours.")
        }
    }

    fun redeemLink(code: String): String =
        "https://flipsend.app/invite?code=${normalize(code)}"
}
