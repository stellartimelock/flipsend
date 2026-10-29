package org.stellartimelock.send

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import org.stellartimelock.send.core.DeviceMaterial
import org.stellartimelock.send.core.Identities
import org.stellartimelock.send.core.OverwriteDecision
import org.stellartimelock.send.core.SaveStore
import org.stellartimelock.send.core.trimDeviceName
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SecretBox(context: Context) {
    private val alias = "stellarsend.wrap.v1"
    private val app = context.applicationContext
    @Volatile private var cachedKey: SecretKey? = null

    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return byteArrayOf(1, iv.size.toByte()) + iv + body
    }

    fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < 3 || sealed[0] != 1.toByte()) {
            throw IllegalStateException("Pairing key file is not recognized.")
        }
        val ivLength = sealed[1].toInt() and 0xFF
        if (ivLength <= 0 || sealed.size < 2 + ivLength) {
            throw IllegalStateException("Pairing key file is not recognized.")
        }
        val iv = sealed.copyOfRange(2, 2 + ivLength)
        val body = sealed.copyOfRange(2 + ivLength, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val store = KeyStore.getInstance("AndroidKeyStore")
            store.load(null)
            val existing = store.getKey(alias, null) as? SecretKey
            if (existing != null) {
                cachedKey = existing
                return existing
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            generator.init(spec)
            val created = generator.generateKey()
            cachedKey = created
            return created
        }
    }

    fun forgetKey() {
        synchronized(this) {
            cachedKey = null
            val store = KeyStore.getInstance("AndroidKeyStore")
            store.load(null)
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        }
        app
    }
}

internal data class StoredPeer(
    val deviceId: String,
    val name: String,
    val fingerprint: String,
    val publicKey: String,
    val host: String?,
    val port: Int,
    val pairedAtMillis: Long = 0,
    val hosts: List<String> = emptyList(),
    val acceptNext: Boolean = false
) {
    fun snapshot() = org.stellartimelock.send.core.PeerTrust.Snapshot(
        deviceId, name, fingerprint, publicKey, host, port, pairedAtMillis, hosts
    )

    companion object {
        fun from(snapshot: org.stellartimelock.send.core.PeerTrust.Snapshot) = StoredPeer(
            snapshot.deviceId,
            snapshot.name,
            snapshot.fingerprint,
            snapshot.publicKey,
            snapshot.host,
            snapshot.port,
            snapshot.pairedAtMillis,
            snapshot.hosts
        )
    }
}

internal class IdentityVault(context: Context) {
    private val box = SecretBox(context)
    private val file = File(context.filesDir, "identity.bin")

    fun loadOrCreate(name: String): DeviceMaterial {
        if (file.exists()) {
            val loaded = decode(box.open(file.readBytes()))
            val upgraded = Identities.ensureDeviceUsages(loaded)
            if (!upgraded.certificateDer.contentEquals(loaded.certificateDer)) {
                SaveStore.writeAtomic(file, box.seal(encode(upgraded)), backups = false)
            }
            return upgraded
        }
        val created = Identities.generate(name = trimDeviceName(name))
        SaveStore.writeAtomic(file, box.seal(encode(created)), backups = false)
        return created
    }

    fun delete() {
        file.delete()
    }

    internal val location: File get() = file

    internal fun seal(material: DeviceMaterial): ByteArray = box.seal(encode(material))

    private fun encode(material: DeviceMaterial): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeUTF(material.deviceId)
            data.writeUTF(material.name)
            data.writeInt(material.privateKeyPkcs8.size)
            data.write(material.privateKeyPkcs8)
            data.writeInt(material.certificateDer.size)
            data.write(material.certificateDer)
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): DeviceMaterial {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val id = data.readUTF()
            val name = data.readUTF()
            val key = ByteArray(data.readInt())
            data.readFully(key)
            val cert = ByteArray(data.readInt())
            data.readFully(cert)
            return DeviceMaterial(id, name, key, cert)
        }
    }
}

internal class PeerVault(context: Context) {
    private val box = SecretBox(context)
    private val file = File(context.filesDir, "peer.bin")
    @Volatile private var memory: StoredPeer? = null
    @Volatile private var ready = false

    fun load(): StoredPeer? {
        if (ready) return memory
        synchronized(this) {
            if (ready) return memory
            memory = readFromDisk()
            ready = true
            return memory
        }
    }

    fun save(peer: StoredPeer) {
        val existing = runCatching { load() }.getOrNull()
        val merged = StoredPeer.from(org.stellartimelock.send.core.PeerTrust.merge(existing?.snapshot(), peer.snapshot()))
            .copy(acceptNext = existing?.acceptNext == true)
        if (!org.stellartimelock.send.core.PeerTrust.shouldWrite(existing?.snapshot(), merged.snapshot()) &&
            existing?.acceptNext == merged.acceptNext
        ) {
            remember(merged)
            return
        }
        SaveStore.writeAtomic(file, seal(merged), backups = false)
        remember(merged)
    }

    fun savePinned(peer: StoredPeer) {
        SaveStore.writeAtomic(file, seal(peer), backups = false)
        remember(peer)
    }

    fun trustNewKey(fingerprint: String, publicKey: String): StoredPeer? {
        val existing = load() ?: return null
        val next = StoredPeer.from(
            org.stellartimelock.send.core.PeerTrust.repin(existing.snapshot(), fingerprint, publicKey)
        ).copy(acceptNext = false)
        savePinned(next)
        return next
    }

    fun markAcceptNext(): StoredPeer? {
        val existing = load() ?: return null
        val next = existing.copy(acceptNext = true)
        savePinned(next)
        return next
    }

    fun reload(): StoredPeer? {
        synchronized(this) {
            ready = false
            memory = null
        }
        return load()
    }

    internal val location: File get() = file

    internal fun seal(peer: StoredPeer): ByteArray {
        val json = JSONObject()
            .put("deviceId", peer.deviceId)
            .put("name", peer.name)
            .put("fingerprint", peer.fingerprint)
            .put("pk", peer.publicKey)
            .put("host", peer.host ?: "")
            .put("port", peer.port)
            .put("pairedAt", peer.pairedAtMillis)
            .put("hosts", org.json.JSONArray(peer.hosts))
            .put("acceptNext", peer.acceptNext)
        return box.seal(json.toString().toByteArray(Charsets.UTF_8))
    }

    fun delete() {
        synchronized(this) {
            memory = null
            if (file.exists() && !file.delete()) {
                runCatching { file.writeBytes(ByteArray(0)) }
                file.delete()
            }
            if (file.exists()) {
                throw java.io.IOException("Could not remove ${file.absolutePath}.")
            }
            ready = true
        }
    }

    private fun remember(peer: StoredPeer) {
        synchronized(this) {
            memory = peer
            ready = true
        }
    }

    private fun readFromDisk(): StoredPeer? {
        if (!file.exists()) return null
        val json = JSONObject(box.open(file.readBytes()).toString(Charsets.UTF_8))
        return StoredPeer(
            json.getString("deviceId"),
            json.getString("name"),
            json.getString("fingerprint"),
            json.optString("pk"),
            json.optString("host").ifBlank { null },
            json.optInt("port", 53321),
            json.optLong("pairedAt"),
            json.optJSONArray("hosts")?.let { array ->
                (0 until array.length()).mapNotNull { index -> array.optString(index).ifBlank { null } }
            } ?: emptyList(),
            json.optBoolean("acceptNext", false)
        )
    }
}

internal class InboxFolder(context: Context) {
    private val file = File(context.filesDir, "inbox.json")
    private val gate = Any()

    fun tree(): String? = synchronized(gate) { read() }

    fun set(uri: String?, confirmed: Boolean): OverwriteDecision = synchronized(gate) {
        val trimmed = uri?.trim()?.ifBlank { null }
        val json = JSONObject()
        if (trimmed != null) json.put("inboxTree", trimmed)
        SaveStore.commit(file, json.toString().toByteArray(Charsets.UTF_8), confirmed)
    }

    private fun read(): String? {
        if (!file.isFile || file.length() == 0L) return null
        return try {
            JSONObject(file.readText()).optString("inboxTree").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }
}
