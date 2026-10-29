package org.stellartimelock.send.core

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

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

/**
 * Durable resume checkpoints. 1 MiB is a multiple of the 8 KB relay
 * WebSocket slice and stays under the 2 MB forward budget, so a drop
 * never restarts a large send from zero.
 */
object ResumeAck {
    const val INTERVAL_BYTES = 1024 * 1024
    const val WINDOW_BYTES = 2 * 1024 * 1024
    const val RELAY_SLICE_BYTES = 8 * 1024
}

enum class MessageType(val code: Int) {
    HELLO(1), HELLO_ACK(2), OFFER(3), ACCEPT(4), REJECT(5), CHUNK(6), CANCEL(7), COMPLETE(8), ERROR(9), SERVICE(10), SERVICE_RESULT(11), TERMINAL_DATA(12), SCREEN_FRAME(13), REMOTE_INPUT(14), ACK(15);

    companion object {
        fun from(code: Int): MessageType = entries.firstOrNull { it.code == code }
            ?: throw ProtocolException("Unknown message type.")
    }
}

class ProtocolException(message: String, val code: String = "bad_request") : Exception(message)

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

data class Frame(val version: Int, val type: MessageType, val flags: Int, val payload: ByteArray)

object FrameCodec {
    private val magic = byteArrayOf(0x53, 0x53, 0x4E, 0x44)

    fun encode(type: MessageType, payload: ByteArray, flags: Int = 0, version: Int = ProtocolLimits.VERSION): ByteArray {
        if (version != ProtocolLimits.VERSION) throw ProtocolException("Unsupported protocol version.")
        if (flags != 0) throw ProtocolException("Unsupported frame flags.")
        if (payload.size > ProtocolLimits.MAX_FRAME_PAYLOAD) throw ProtocolException("Frame payload exceeds the limit.", ErrorCodes.LIMIT)
        val out = ByteArray(ProtocolLimits.HEADER_SIZE + payload.size)
        magic.copyInto(out)
        out[4] = version.toByte()
        out[5] = type.code.toByte()
        out[6] = (flags shr 8).toByte()
        out[7] = flags.toByte()
        ByteBuffer.wrap(out, 8, 4).putInt(payload.size)
        payload.copyInto(out, ProtocolLimits.HEADER_SIZE)
        return out
    }

    fun write(stream: OutputStream, type: MessageType, payload: ByteArray) {
        stream.write(encode(type, payload))
        stream.flush()
    }

    fun writeJson(stream: OutputStream, type: MessageType, json: JSONObject) {
        write(stream, type, json.toString().toByteArray(Charsets.UTF_8))
    }

    fun read(stream: InputStream): Frame {
        val header = readExactly(stream, ProtocolLimits.HEADER_SIZE)
        if (!header.copyOfRange(0, 4).contentEquals(magic)) throw ProtocolException("Unrecognized protocol header.")
        val version = header[4].toInt() and 0xFF
        if (version != ProtocolLimits.VERSION) throw ProtocolException("Unsupported protocol version $version.")
        val type = MessageType.from(header[5].toInt() and 0xFF)
        val flags = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
        if (flags != 0) throw ProtocolException("Unsupported frame flags.")
        val length = ByteBuffer.wrap(header, 8, 4).int
        if (length < 0 || length > ProtocolLimits.MAX_FRAME_PAYLOAD) {
            throw ProtocolException("Frame payload exceeds the limit.", ErrorCodes.LIMIT)
        }
        val payload = if (length == 0) ByteArray(0) else readExactly(stream, length)
        return Frame(version, type, flags, payload)
    }

    fun readExactly(stream: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = stream.read(buffer, read, size - read)
            if (n < 0) throw EOFException("Connection closed.")
            read += n
        }
        return buffer
    }
}

data class Chunk(val transferId: UUID, val offset: Long, val data: ByteArray)

object ChunkCodec {
    fun encode(id: UUID, offset: Long, data: ByteArray): ByteArray {
        if (data.size > ProtocolLimits.MAX_CHUNK_DATA) throw ProtocolException("Chunk exceeds the limit.", ErrorCodes.LIMIT)
        val out = ByteArray(24 + data.size)
        uuidBytes(id).copyInto(out)
        ByteBuffer.wrap(out, 16, 8).putLong(offset)
        data.copyInto(out, 24)
        return out
    }

    fun decode(payload: ByteArray): Chunk {
        if (payload.size < 24) throw ProtocolException("Chunk is too short.")
        if (payload.size - 24 > ProtocolLimits.MAX_CHUNK_DATA) throw ProtocolException("Chunk exceeds the limit.", ErrorCodes.LIMIT)
        val id = readUuid(payload)
        val offset = ByteBuffer.wrap(payload, 16, 8).long
        return Chunk(id, offset, payload.copyOfRange(24, payload.size))
    }
}

fun uuidBytes(id: UUID): ByteArray {
    val buffer = ByteBuffer.allocate(16)
    buffer.putLong(id.mostSignificantBits)
    buffer.putLong(id.leastSignificantBits)
    return buffer.array()
}

fun readUuid(bytes: ByteArray): UUID {
    val buffer = ByteBuffer.wrap(bytes, 0, 16)
    return UUID(buffer.long, buffer.long)
}

fun sha256Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(data)
    return digest.joinToString("") { "%02x".format(it) }
}

fun sha256Hex(stream: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(81920)
    while (true) {
        val n = stream.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun pinsEqual(left: String?, right: String?): Boolean {
    if (!isFingerprint(left) || !isFingerprint(right)) return false
    return MessageDigest.isEqual(left!!.toByteArray(Charsets.US_ASCII), right!!.toByteArray(Charsets.US_ASCII))
}

fun isFingerprint(value: String?): Boolean {
    if (value == null || value.length != 64) return false
    return value.all { it in '0'..'9' || it in 'a'..'f' }
}

fun trimDeviceName(name: String): String {
    val clean = name.trim().filter { !it.isISOControl() }
    if (clean.isEmpty()) return "Device"
    return clean.take(ProtocolLimits.MAX_DEVICE_NAME)
}

object BeaconCodec {
    fun encode(id: UUID, port: Int, name: String, code: String? = null): ByteArray {
        val json = JSONObject()
            .put("v", 1)
            .put("app", "stellarsend")
            .put("id", id.toString())
            .put("port", port)
            .put("name", trimDeviceName(name))
        if (!code.isNullOrBlank()) json.put("code", code)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 512) throw ProtocolException("Discovery beacon is too large.", ErrorCodes.LIMIT)
        return bytes
    }

    fun tryParse(data: ByteArray): JSONObject? {
        if (data.isEmpty() || data.size > 512) return null
        val text = data.toString(Charsets.UTF_8).lowercase()
        if ("\"pk\"" in text || "private" in text || "secret" in text) return null
        return try {
            val json = JSONObject(data.toString(Charsets.UTF_8))
            if (json.optInt("v") != 1 || json.optString("app") != "stellarsend") return null
            UUID.fromString(json.getString("id"))
            val port = json.getInt("port")
            if (port !in 1..65535) return null
            json
        } catch (_: Exception) {
            null
        }
    }
}

fun readStreamLimited(stream: InputStream, max: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val n = stream.read(buffer)
        if (n < 0) break
        total += n
        if (total > max) throw ProtocolException("Content exceeds the size limit.", ErrorCodes.LIMIT)
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
