package org.stellartimelock.send.shared

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSData
import platform.Foundation.NSUUID
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create

@OptIn(ExperimentalForeignApi::class)
actual fun sha256Bytes(data: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    data.usePinned { pinned ->
        digest.usePinned { out ->
            CC_SHA256(pinned.addressOf(0), data.size.convert(), out.addressOf(0))
        }
    }
    return digest
}

actual fun randomUuid(): String = NSUUID().UUIDString.lowercase()

actual fun base64UrlEncode(data: ByteArray): String {
    val nsData = data.toNSData()
    return nsData.base64EncodedStringWithOptions(0u)
        .replace('+', '-')
        .replace('/', '_')
        .trimEnd('=')
}

actual fun base64UrlDecode(value: String): ByteArray {
    val padded = value.replace('-', '+').replace('_', '/').let {
        val pad = (4 - it.length % 4) % 4
        if (pad == 0) it else it + "=".repeat(pad)
    }
    val nsData = NSData.create(base64EncodedString = padded, options = 0u)
        ?: throw IllegalArgumentException("Invalid base64url input.")
    return nsData.toByteArray()
}

// ---------- NSData <-> ByteArray helpers ----------

@OptIn(ExperimentalForeignApi::class)
fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.convert())
}

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val result = ByteArray(length.toInt())
    if (result.isNotEmpty()) {
        result.usePinned { pinned ->
            memScoped {
                bytes?.let { src ->
                    platform.posix.memcpy(pinned.addressOf(0), src, length)
                }
            }
        }
    }
    return result
}
