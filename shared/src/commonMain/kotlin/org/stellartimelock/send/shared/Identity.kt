package org.stellartimelock.send.shared

/**
 * Device identity: a self-signed EC certificate used as a mutual-TLS
 * identity for peer-to-peer connections.
 *
 * Keys are derived from the StellarTimeLock seed or a user password
 * (Argon2id / scrypt / HKDF) — never from device-bound hardware.
 * The device key lives in memory only; the serialised form is
 * application-encrypted before being written to disk.
 */
data class DeviceIdentity(
    val deviceId: String,
    val name: String,
    /** DER-encoded PKCS#8 private key. */
    val privateKeyBytes: ByteArray,
    /** DER-encoded X.509 certificate. */
    val certificateBytes: ByteArray,
    /** SHA-256 hex of the SubjectPublicKeyInfo (SPKI) bytes. */
    val fingerprint: String,
    /** Base64url-encoded SPKI (for QR codes). */
    val spkiBase64Url: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceIdentity) return false
        return deviceId == other.deviceId
    }
    override fun hashCode(): Int = deviceId.hashCode()

    companion object {
        /** Deserialise from [toBytes] output. */
        fun fromBytes(bytes: ByteArray): DeviceIdentity {
            var pos = 0
            fun readField(): ByteArray {
                val len = ((bytes[pos].toInt() and 0xFF) shl 24) or
                        ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                        (bytes[pos + 3].toInt() and 0xFF)
                pos += 4
                val field = bytes.copyOfRange(pos, pos + len); pos += len
                return field
            }
            val id = readField().decodeToString()
            val name = readField().decodeToString()
            val priv = readField()
            val cert = readField()
            val fp = readField().decodeToString()
            val spki = readField().decodeToString()
            return DeviceIdentity(id, name, priv, cert, fp, spki)
        }
    }
}

/** Generate a fresh EC-P256 self-signed identity. */
expect fun generateIdentity(name: String): DeviceIdentity

/**
 * Serialise a [DeviceIdentity] to bytes for application-layer encryption.
 * Format: 4-byte big-endian lengths followed by each field's raw bytes.
 */
fun DeviceIdentity.toBytes(): ByteArray {
    val idBytes = deviceId.encodeToByteArray()
    val nameBytes = name.encodeToByteArray()
    val fpBytes = fingerprint.encodeToByteArray()
    val spkiBytes = spkiBase64Url.encodeToByteArray()
    val total = 4 + idBytes.size + 4 + nameBytes.size +
            4 + privateKeyBytes.size + 4 + certificateBytes.size +
            4 + fpBytes.size + 4 + spkiBytes.size
    val out = ByteArray(total)
    var pos = 0
    fun writeField(b: ByteArray) {
        out[pos] = (b.size ushr 24).toByte(); out[pos + 1] = (b.size ushr 16).toByte()
        out[pos + 2] = (b.size ushr 8).toByte(); out[pos + 3] = b.size.toByte()
        b.copyInto(out, pos + 4); pos += 4 + b.size
    }
    writeField(idBytes); writeField(nameBytes); writeField(privateKeyBytes)
    writeField(certificateBytes); writeField(fpBytes); writeField(spkiBytes)
    return out
}
