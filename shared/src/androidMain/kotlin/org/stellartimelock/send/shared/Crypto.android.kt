package org.stellartimelock.send.shared

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

actual fun sha256Bytes(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

actual fun randomUuid(): String = UUID.randomUUID().toString()

actual fun base64UrlEncode(data: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(data)

actual fun base64UrlDecode(value: String): ByteArray =
    Base64.getUrlDecoder().decode(value.replace("-", "+").replace("_", "/").let {
        // Accept both padded and unpadded
        val pad = (4 - it.length % 4) % 4
        if (pad == 0) it else it + "=".repeat(pad)
    })
