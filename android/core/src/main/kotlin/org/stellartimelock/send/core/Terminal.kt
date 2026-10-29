package org.stellartimelock.send.core

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Terminal commands are signed with the pairing identity key.
 * That key is the pairing seed. It is not a device-bound keystore key.
 */
object TerminalAuth {
    const val LABEL = "flipsend-terminal-v1"

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun payload(id: String, command: String, confirmed: Boolean): ByteArray {
        val flag = if (confirmed) "1" else "0"
        return "$LABEL\n$id\n$command\n$flag\n".toByteArray(Charsets.UTF_8)
    }

    fun sign(privateKey: PrivateKey, id: String, command: String, confirmed: Boolean): String {
        val signature = ecdsa()
        signature.initSign(privateKey)
        signature.update(payload(id, command, confirmed))
        return base64UrlEncode(signature.sign())
    }

    fun verify(spki: ByteArray, id: String, command: String, confirmed: Boolean, signature: String?): Boolean {
        if (signature.isNullOrBlank()) return false
        return try {
            val key = publicKey(spki)
            val verifier = ecdsa()
            verifier.initVerify(key)
            verifier.update(payload(id, command, confirmed))
            verifier.verify(base64UrlDecode(signature))
        } catch (_: Exception) {
            false
        }
    }

    private fun publicKey(spki: ByteArray): PublicKey =
        KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
            .generatePublic(X509EncodedKeySpec(spki))

    private fun ecdsa(): Signature =
        Signature.getInstance("SHA256withECDSA", BouncyCastleProvider.PROVIDER_NAME)
}

object TerminalPolicy {
    const val MAX_COMMAND_CHARS = 4000
    const val MAX_OUTPUT_CHARS = 1_048_576
    const val DISABLED = "terminal_off"
    const val NOT_ALLOWED = "not_allowed"
    const val NEEDS_CONFIRM = "needs_confirm"
    const val UNAUTHENTICATED = "unauthenticated"

    private val destructive = Regex("(?i)(?:^|[^a-z0-9_])(del|rm|rmdir|format|shutdown)(?:[^a-z0-9_]|$)")

    fun isDestructive(command: String): Boolean = destructive.containsMatchIn(command)

    fun rejectReason(command: String?): String? {
        if (command.isNullOrBlank()) return "Enter a command."
        if (command.length > MAX_COMMAND_CHARS) return "That command is too long."
        if (command.any { it == '\u0000' || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') }) {
            return "That command has unsupported characters."
        }
        return null
    }

    fun isAllowlisted(command: String, allowlist: Iterable<String>): Boolean {
        val trimmed = command.trim()
        return allowlist.any { it.trim().equals(trimmed, ignoreCase = true) }
    }
}

data class TerminalChunk(val id: String, val seq: Int, val stream: String, val text: String)

object TerminalCodec {
    fun parseChunk(payload: ByteArray): TerminalChunk {
        val json = try {
            JSONObject(payload.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw ProtocolException("Unreadable terminal output.")
        }
        val stream = json.optString("stream")
        if (stream != "out" && stream != "err") throw ProtocolException("Unreadable terminal output.")
        return TerminalChunk(json.optString("id"), json.optInt("seq"), stream, json.optString("text"))
    }
}
