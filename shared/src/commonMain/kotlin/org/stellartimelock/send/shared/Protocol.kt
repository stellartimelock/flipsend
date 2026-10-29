package org.stellartimelock.send.shared

/**
 * Wire-level constants shared by every platform.
 * Keep these in sync with ProtocolLimits in android/core.
 */
object ProtocolLimits {
    const val VERSION = 1
    const val HEADER_SIZE = 12
    const val MAX_FRAME_PAYLOAD = 256 * 1024
    const val MAX_CHUNK_DATA = 64 * 1024
    const val MAX_TEXT_BYTES = 1024 * 1024
    const val MAX_FILE_BYTES = 8L * 1024 * 1024 * 1024
    const val MAX_NAME_CHARS = 240
    const val MAX_DEVICE_NAME = 64
    const val TCP_PORT = 53321
    const val UDP_PORT = 53320
    const val QR_PREFIX = "stellarsend://pair?"
}

enum class MessageType(val code: Int) {
    HELLO(1), HELLO_ACK(2), OFFER(3), ACCEPT(4), REJECT(5),
    CHUNK(6), CANCEL(7), COMPLETE(8), ERROR(9),
    SERVICE(10), SERVICE_RESULT(11), TERMINAL_DATA(12), SCREEN_FRAME(13), REMOTE_INPUT(14),
    ACK(15);

    companion object {
        fun from(code: Int): MessageType =
            entries.firstOrNull { it.code == code }
                ?: throw ProtocolException("Unknown message type.")
    }
}

class ProtocolException(message: String, val code: String = ErrorCodes.BAD_REQUEST) : Exception(message)

object ErrorCodes {
    const val KEY_CHANGED = "key_changed"
    const val UNKNOWN_PEER = "unknown_peer"
    const val LIMIT = "limit"
    const val HASH_MISMATCH = "hash_mismatch"
    const val CANCELLED = "cancelled"
    const val DECLINED = "declined"
    const val BAD_REQUEST = "bad_request"
    const val IO = "io"
    const val NOT_IMPLEMENTED = "not_implemented"
}

data class Frame(val version: Int, val type: MessageType, val flags: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return version == other.version && type == other.type && flags == other.flags && payload.contentEquals(other.payload)
    }
    override fun hashCode(): Int {
        var result = version
        result = 31 * result + type.hashCode()
        result = 31 * result + flags
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Pure ByteArray-based frame codec.  The streaming variants
 * (FrameCodec.read / write over a socket) live in platform-specific
 * transport code so each platform can use its own I/O primitives.
 */
object FrameCodec {
    private val magic = byteArrayOf(0x53, 0x53, 0x4E, 0x44)

    fun encode(type: MessageType, payload: ByteArray, flags: Int = 0): ByteArray {
        if (flags != 0) throw ProtocolException("Unsupported frame flags.")
        if (payload.size > ProtocolLimits.MAX_FRAME_PAYLOAD) throw ProtocolException("Frame payload exceeds the limit.", ErrorCodes.LIMIT)
        val out = ByteArray(ProtocolLimits.HEADER_SIZE + payload.size)
        magic.copyInto(out)
        out[4] = ProtocolLimits.VERSION.toByte()
        out[5] = type.code.toByte()
        out[6] = (flags shr 8).toByte()
        out[7] = flags.toByte()
        putInt(out, 8, payload.size)
        payload.copyInto(out, ProtocolLimits.HEADER_SIZE)
        return out
    }

    fun encodeJson(type: MessageType, json: String): ByteArray =
        encode(type, json.encodeToByteArray())

    /** Parse a header block (exactly HEADER_SIZE bytes) and return (type, flags, payloadLength). */
    fun parseHeader(header: ByteArray): Triple<MessageType, Int, Int> {
        if (header.size != ProtocolLimits.HEADER_SIZE) throw ProtocolException("Invalid header size.")
        if (!header.copyOfRange(0, 4).contentEquals(magic)) throw ProtocolException("Unrecognized protocol header.")
        val version = header[4].toInt() and 0xFF
        if (version != ProtocolLimits.VERSION) throw ProtocolException("Unsupported protocol version $version.")
        val type = MessageType.from(header[5].toInt() and 0xFF)
        val flags = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
        if (flags != 0) throw ProtocolException("Unsupported frame flags.")
        val length = getInt(header, 8)
        if (length < 0 || length > ProtocolLimits.MAX_FRAME_PAYLOAD) {
            throw ProtocolException("Frame payload exceeds the limit.", ErrorCodes.LIMIT)
        }
        return Triple(type, flags, length)
    }

    private fun putInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    private fun getInt(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
                ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                (buf[offset + 3].toInt() and 0xFF)
}

data class Chunk(val transferId: String, val offset: Long, val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Chunk) return false
        return transferId == other.transferId && offset == other.offset && data.contentEquals(other.data)
    }
    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + offset.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

object ChunkCodec {
    fun encode(transferId: String, offset: Long, data: ByteArray): ByteArray {
        if (data.size > ProtocolLimits.MAX_CHUNK_DATA) throw ProtocolException("Chunk exceeds the limit.", ErrorCodes.LIMIT)
        val idBytes = uuidToBytes(transferId)
        val out = ByteArray(24 + data.size)
        idBytes.copyInto(out)
        putLong(out, 16, offset)
        data.copyInto(out, 24)
        return out
    }

    fun decode(payload: ByteArray): Chunk {
        if (payload.size < 24) throw ProtocolException("Chunk is too short.")
        if (payload.size - 24 > ProtocolLimits.MAX_CHUNK_DATA) throw ProtocolException("Chunk exceeds the limit.", ErrorCodes.LIMIT)
        val id = uuidFromBytes(payload)
        val offset = getLong(payload, 16)
        return Chunk(id, offset, payload.copyOfRange(24, payload.size))
    }

    private fun putLong(buf: ByteArray, offset: Int, value: Long) {
        for (i in 0..7) buf[offset + i] = ((value ushr (56 - i * 8)) and 0xFF).toByte()
    }

    private fun getLong(buf: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0..7) result = (result shl 8) or (buf[offset + i].toLong() and 0xFF)
        return result
    }
}

/** Convert a UUID string to 16 bytes (big-endian MSB/LSB). */
fun uuidToBytes(uuid: String): ByteArray {
    val clean = uuid.replace("-", "")
    if (clean.length != 32) throw ProtocolException("Invalid UUID.")
    return ByteArray(16) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/** Convert 16 raw bytes back to a UUID string. */
fun uuidFromBytes(bytes: ByteArray): String {
    if (bytes.size < 16) throw ProtocolException("UUID bytes too short.")
    val hex = bytes.take(16).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

fun isFingerprint(value: String?): Boolean {
    if (value == null || value.length != 64) return false
    return value.all { it in '0'..'9' || it in 'a'..'f' }
}

fun pinsEqual(left: String?, right: String?): Boolean {
    if (!isFingerprint(left) || !isFingerprint(right)) return false
    // Constant-time comparison
    var diff = 0
    for (i in left!!.indices) diff = diff or (left[i].code xor right!![i].code)
    return diff == 0
}

fun trimDeviceName(name: String): String {
    val clean = name.trim().filter { !it.isISOControl() }
    if (clean.isEmpty()) return "Device"
    return clean.take(ProtocolLimits.MAX_DEVICE_NAME)
}

object BeaconCodec {
    fun encode(id: String, port: Int, name: String, code: String? = null): ByteArray {
        val parts = mutableListOf(
            "\"v\":1",
            "\"app\":\"stellarsend\"",
            "\"id\":${jsonString(id)}",
            "\"port\":$port",
            "\"name\":${jsonString(trimDeviceName(name))}"
        )
        if (!code.isNullOrBlank()) parts += "\"code\":${jsonString(code)}"
        val json = "{${parts.joinToString(",")}}".encodeToByteArray()
        if (json.size > 512) throw ProtocolException("Discovery beacon is too large.", ErrorCodes.LIMIT)
        return json
    }

    fun tryParse(data: ByteArray): Map<String, String>? {
        if (data.isEmpty() || data.size > 512) return null
        val text = data.decodeToString()
        val lower = text.lowercase()
        if ("\"pk\"" in lower || "private" in lower || "secret" in lower) return null
        return try {
            val map = parseSimpleJson(text) ?: return null
            if (map["v"] != "1" || map["app"] != "stellarsend") return null
            val port = map["port"]?.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            map
        } catch (_: Exception) {
            null
        }
    }

    /** Minimal flat JSON string->string/number map parser for beacon payloads. */
    private fun parseSimpleJson(text: String): Map<String, String>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) return null
        val result = mutableMapOf<String, String>()
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return result
        var i = 0
        while (i < inner.length) {
            while (i < inner.length && inner[i].isWhitespace()) i++
            if (i >= inner.length) break
            if (inner[i] != '"') return null
            val keyEnd = inner.indexOf('"', i + 1)
            if (keyEnd < 0) return null
            val key = inner.substring(i + 1, keyEnd)
            i = keyEnd + 1
            while (i < inner.length && inner[i].isWhitespace()) i++
            if (i >= inner.length || inner[i] != ':') return null
            i++
            while (i < inner.length && inner[i].isWhitespace()) i++
            val value: String
            if (inner[i] == '"') {
                val valEnd = inner.indexOf('"', i + 1)
                if (valEnd < 0) return null
                value = inner.substring(i + 1, valEnd)
                i = valEnd + 1
            } else {
                val valEnd = inner.indexOfFirst { it == ',' || it == '}' }.let {
                    if (it < 0) inner.length else i + (inner.substring(i).indexOfFirst { c -> c == ',' || c == '}' }.let { idx -> if (idx < 0) inner.length - i else idx })
                }
                value = inner.substring(i, valEnd).trim()
                i = valEnd
            }
            result[key] = value
            while (i < inner.length && inner[i].isWhitespace()) i++
            if (i < inner.length && inner[i] == ',') i++
        }
        return result
    }
}

internal fun jsonString(value: String): String {
    val sb = StringBuilder("\"")
    for (ch in value) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}

/** Build a flat JSON object from alternating key/value pairs. Nulls are skipped. */
fun buildJson(vararg keyValues: Any?): String {
    require(keyValues.size % 2 == 0) { "buildJson requires pairs" }
    val parts = mutableListOf<String>()
    var i = 0
    while (i < keyValues.size) {
        val key = keyValues[i] as? String ?: error("Key must be String")
        val value = keyValues[i + 1]
        if (value != null) {
            val encoded = when (value) {
                is String -> jsonString(value)
                is Number -> value.toString()
                is Boolean -> value.toString()
                else -> jsonString(value.toString())
            }
            parts += "\"$key\":$encoded"
        }
        i += 2
    }
    return "{${parts.joinToString(",")}}"
}
