package org.stellartimelock.send.shared

/**
 * Platform-specific cryptographic primitives.
 *
 * Android actual: MessageDigest + BouncyCastle.
 * iOS actual: CommonCrypto + Apple Security framework.
 */

/** Compute SHA-256 and return the raw 32 bytes. */
expect fun sha256Bytes(data: ByteArray): ByteArray

/** Return a random UUID v4 as lowercase hyphenated string. */
expect fun randomUuid(): String

/** Base64url-encode (no padding). */
expect fun base64UrlEncode(data: ByteArray): String

/** Base64url-decode (with or without padding). */
expect fun base64UrlDecode(value: String): ByteArray

// --- Derived helpers (pure, no platform dependency) ---------------------

fun sha256Hex(data: ByteArray): String =
    sha256Bytes(data).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
