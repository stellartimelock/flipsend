package org.stellartimelock.send.core

import java.net.URLDecoder
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

data class PairingAdvert(
    val version: Int,
    val deviceId: UUID,
    val publicKeySpkiBase64Url: String,
    val fingerprint: String,
    val host: String,
    val port: Int,
    val name: String,
    val hosts: List<String> = emptyList()
) {
    fun candidates(): List<String> {
        if (hosts.isNotEmpty()) return hosts
        return if (host.isBlank()) emptyList() else listOf(host)
    }
}

object PairingCodec {
    private val forbidden = setOf("secret", "private", "priv", "sk", "password", "token", "seed", "mnemonic", "pkey")

    fun toQr(advert: PairingAdvert): String {
        require(advert.version == 1)
        val candidates = advert.candidates().filter { AdapterRank.isLanCandidate(it) }.distinct().take(AdapterRank.MAX_CANDIDATES)
        val host = candidates.firstOrNull().orEmpty()
        val parts = mutableListOf(
            "v=${advert.version}",
            "id=${enc(advert.deviceId.toString())}",
            "pk=${enc(advert.publicKeySpkiBase64Url)}",
            "fp=${enc(advert.fingerprint)}",
            "host=${enc(host)}",
            "port=${advert.port}",
            "name=${enc(advert.name)}"
        )
        if (candidates.isNotEmpty()) parts += "ips=${enc(candidates.joinToString(","))}"
        return ProtocolLimits.QR_PREFIX + parts.joinToString("&")
    }

    fun parse(payload: String): PairingAdvert {
        val trimmed = payload.trim()
        if (!trimmed.startsWith(ProtocolLimits.QR_PREFIX)) throw ProtocolException("This is not a FlipSend pairing code.")
        val fields = LinkedHashMap<String, String>()
        trimmed.substring(ProtocolLimits.QR_PREFIX.length).split('&').filter { it.isNotEmpty() }.forEach { part ->
            val eq = part.indexOf('=')
            val key = URLDecoder.decode(if (eq < 0) part else part.substring(0, eq), Charsets.UTF_8)
            val rawValue = if (eq < 0) "" else part.substring(eq + 1)
            val value = URLDecoder.decode(rawValue.replace("+", "%2B"), Charsets.UTF_8)
            if (fields.containsKey(key)) throw ProtocolException("Pairing code has a duplicate field.")
            fields[key] = value
        }
        if (fields.keys.any { forbidden.contains(it.lowercase()) }) throw ProtocolException("Pairing code contains a forbidden field.")
        if (fields["v"] != "1") throw ProtocolException("Unsupported pairing code.")
        val id = try {
            UUID.fromString(fields["id"])
        } catch (_: Exception) {
            throw ProtocolException("Pairing code is missing a device id.")
        }
        val pk = fields["pk"] ?: throw ProtocolException("Pairing code is missing a public key.")
        val fp = fields["fp"] ?: throw ProtocolException("Pairing code is missing a public key.")
        if (pk.contains("PRIVATE", true) || pk.contains("BEGIN", true)) {
            throw ProtocolException("Pairing code must not contain a private key.")
        }
        val spki = try {
            base64UrlDecode(pk)
        } catch (_: Exception) {
            throw ProtocolException("Pairing public key is not valid.")
        }
        try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            if (key !is ECPublicKey) throw ProtocolException("Pairing public key is not valid.")
        } catch (ex: ProtocolException) {
            throw ex
        } catch (_: Exception) {
            throw ProtocolException("Pairing public key is not valid.")
        }
        val actual = sha256Hex(spki)
        if (!pinsEqual(actual, fp.lowercase())) throw ProtocolException("Pairing key does not match its fingerprint.")
        val host = fields["host"].orEmpty()
        if (host.isNotEmpty() && !LocalAddressPolicy.isAllowedHost(host, allowLoopback = false)) {
            throw ProtocolException("Pairing address must be a local IP.")
        }
        val hosts = mutableListOf<String>()
        fields["ips"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            if (!LocalAddressPolicy.isAllowedHost(part, allowLoopback = false)) {
                throw ProtocolException("Pairing address must be a local IP.")
            }
            if (part !in hosts) hosts += part
        }
        if (host.isNotEmpty() && host !in hosts) hosts.add(0, host)
        val port = fields["port"]?.toIntOrNull() ?: throw ProtocolException("Pairing port is invalid.")
        if (port !in 1..65535) throw ProtocolException("Pairing port is invalid.")
        val primary = hosts.firstOrNull() ?: host
        return PairingAdvert(1, id, pk, actual, primary, port, trimDeviceName(fields["name"] ?: "Device"), hosts)
    }

    private fun enc(value: String) = escapeDataString(value)
}

fun escapeDataString(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val out = StringBuilder(bytes.size)
    for (raw in bytes) {
        val c = raw.toInt() and 0xFF
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
            out.append(ch)
        } else {
            out.append('%')
            out.append("0123456789ABCDEF"[c ushr 4])
            out.append("0123456789ABCDEF"[c and 0x0F])
        }
    }
    return out.toString()
}

fun base64UrlEncode(data: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(data)

fun base64UrlDecode(value: String): ByteArray =
    Base64.getUrlDecoder().decode(value)

object LocalAddressPolicy {
    fun isAllowedHost(host: String, allowLoopback: Boolean): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val bytes = parts.map { it.toIntOrNull() ?: return false }
        if (bytes.any { it !in 0..255 }) return false
        if (bytes[0] >= 224 || (bytes[0] == 0 && bytes[1] == 0)) return false
        if (bytes[0] == 255) return false
        if (bytes[0] == 127) return allowLoopback
        if (bytes[0] == 10) return true
        if (bytes[0] == 192 && bytes[1] == 168) return true
        if (bytes[0] == 172 && bytes[1] in 16..31) return true
        if (bytes[0] == 169 && bytes[1] == 254) return true
        return false
    }
}
