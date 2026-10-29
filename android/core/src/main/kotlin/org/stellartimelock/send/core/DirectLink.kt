package org.stellartimelock.send.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

/**
 * Cross-network direct link. The relay only introduces the two public addresses.
 * File bytes use a punched socket with the existing pairing certificates, or stay on the relay.
 */
object DirectLink {
    const val BUDGET_MS = 4000
    const val WIFI_DIRECT_NOTE = "Wi-Fi Direct is not started. A miss stays on the punch or the relay."

    fun choose(punched: Boolean, wifiDirectUp: Boolean): String =
        if (punched || wifiDirectUp) Reachability.PUNCHED else Reachability.RELAY

    fun iAmClient(localId: String, remoteId: String): Boolean =
        localId.compareTo(remoteId, ignoreCase = true) < 0

    fun isEndpoint(text: String?): Boolean {
        val value = text?.trim().orEmpty()
        val cut = value.lastIndexOf(':')
        if (cut <= 0 || cut == value.length - 1) return false
        val host = value.substring(0, cut)
        val port = value.substring(cut + 1).toIntOrNull() ?: return false
        return port in 1..65535 && host.split('.').let { parts ->
            parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
        }
    }

    fun packet(token: String): ByteArray {
        val id = hexToBytes(token.replace("-", ""))
        require(id.size == 16)
        return byteArrayOf(0x53, 0x53, 0x4E, 0x44) + id
    }

    fun readToken(bytes: ByteArray, length: Int): String? {
        if (length < 20) return null
        if (bytes[0] != 0x53.toByte() || bytes[1] != 0x53.toByte() || bytes[2] != 0x4E.toByte() || bytes[3] != 0x44.toByte()) return null
        val hex = bytes.copyOfRange(4, 20).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20)
    }

    fun targets(remote: JSONObject): List<PunchTarget> {
        val udp = remote.optInt("udp")
        val tcp = remote.optInt("tcp")
        if (udp !in 1..65535 || tcp !in 1..65535) return emptyList()
        val list = ArrayList<PunchTarget>()
        fun add(host: String?, tcpPort: Int) {
            if (host.isNullOrBlank() || tcpPort !in 1..65535 || !isIpv4(host)) return
            val target = PunchTarget(host, udp, tcpPort)
            if (list.none { it == target }) list += target
        }
        val seen = remote.optString("seen")
        if (isEndpoint(seen)) {
            val cut = seen.lastIndexOf(':')
            add(seen.substring(0, cut), tcp)
            add(seen.substring(0, cut), seen.substring(cut + 1).toInt())
        }
        val lan = remote.optJSONArray("lan")
        if (lan != null) {
            for (i in 0 until lan.length()) add(lan.optString(i), tcp)
        }
        return list
    }

    fun offerJson(token: String, udp: Int, tcp: Int, seen: String?, lan: List<String>): JSONObject {
        val json = JSONObject().put("token", token).put("udp", udp).put("tcp", tcp)
        if (isEndpoint(seen)) json.put("seen", seen)
        val hosts = lan.filter { isIpv4(it) }.distinct().take(8)
        if (hosts.isNotEmpty()) {
            val array = JSONArray()
            hosts.forEach { array.put(it) }
            json.put("lan", array)
        }
        return json
    }

    private fun isIpv4(host: String): Boolean =
        host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < out.size) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            i++
        }
        return out
    }
}

data class PunchTarget(val host: String, val udpPort: Int, val tcpPort: Int)

class ByteOut(var inner: OutputStream) : OutputStream() {
    override fun write(b: Int) = inner.write(b)
    override fun write(buf: ByteArray, off: Int, len: Int) = inner.write(buf, off, len)
    override fun flush() = inner.flush()
}

class ByteIn(var inner: InputStream) : InputStream() {
    override fun read(): Int = inner.read()
    override fun read(buf: ByteArray, off: Int, len: Int): Int = inner.read(buf, off, len)
    override fun available(): Int = inner.available()
}

class DirectAttempt private constructor(
    private val udp: DatagramSocket,
    private val tcp: ServerSocket,
    val offer: JSONObject
) : AutoCloseable {
    fun open(local: DeviceMaterial, remote: JSONObject, budgetMs: Int = DirectLink.BUDGET_MS): SSLSocket? {
        val remoteOffer = remote.optJSONObject("direct") ?: return null
        val remoteId = remote.optString("deviceId")
        val remoteFp = remote.optString("fingerprint")
        if (remoteId.isBlank() || remoteFp.isBlank()) return null
        val targets = DirectLink.targets(remoteOffer)
        if (targets.isEmpty()) return null
        val winner = race(local.deviceId, remoteId, remoteOffer.optString("token"), targets, budgetMs) ?: return null
        return try {
            val client = DirectLink.iAmClient(local.deviceId, remoteId)
            val ssl = if (client) {
                TlsPins.client(local, remoteFp).socketFactory.createSocket(winner, winner.inetAddress.hostAddress, winner.port, true) as SSLSocket
            } else {
                TlsPins.server(local, remoteFp, false).socketFactory.createSocket(winner, winner.inetAddress?.hostAddress, winner.port, true) as SSLSocket
            }
            TlsPins.configure(ssl, client)
            ssl.startHandshake()
            ssl
        } catch (_: Exception) {
            runCatching { winner.close() }
            null
        }
    }

    override fun close() {
        runCatching { udp.close() }
        runCatching { tcp.close() }
    }

    private fun race(localId: String, remoteId: String, remoteToken: String, targets: List<PunchTarget>, budgetMs: Int): Socket? {
        val deadline = System.currentTimeMillis() + budgetMs
        val accepted = AtomicReference<Socket?>(null)
        val connected = AtomicReference<Socket?>(null)
        val token = offer.getString("token")
        Thread({
            val packet = DirectLink.packet(token)
            val buffer = ByteArray(64)
            while (System.currentTimeMillis() < deadline && !udp.isClosed) {
                for (target in targets) {
                    try {
                        udp.send(DatagramPacket(packet, packet.size, InetAddress.getByName(target.host), target.udpPort))
                    } catch (_: Exception) {
                        return@Thread
                    }
                }
                try {
                    udp.soTimeout = 100
                    val incoming = DatagramPacket(buffer, buffer.size)
                    udp.receive(incoming)
                    val got = DirectLink.readToken(incoming.data, incoming.length)
                    if (got != null && got.equals(remoteToken, ignoreCase = true)) return@Thread
                } catch (_: Exception) {
                }
            }
        }, "stellarsend-punch-udp").apply { isDaemon = true }.start()
        Thread.sleep(300)
        Thread({
            while (System.currentTimeMillis() < deadline && !tcp.isClosed) {
                try {
                    tcp.soTimeout = 200
                    val socket = tcp.accept()
                    socket.tcpNoDelay = true
                    if (!accepted.compareAndSet(null, socket)) socket.close()
                } catch (_: Exception) {
                }
            }
        }, "stellarsend-punch-accept").apply { isDaemon = true }.start()
        Thread({
            while (System.currentTimeMillis() < deadline && connected.get() == null) {
                for (target in targets) {
                    if (System.currentTimeMillis() >= deadline) return@Thread
                    val socket = Socket()
                    try {
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(target.host, target.tcpPort), 800)
                        if (!connected.compareAndSet(null, socket)) socket.close()
                    } catch (_: Exception) {
                        runCatching { socket.close() }
                    }
                }
            }
        }, "stellarsend-punch-connect").apply { isDaemon = true }.start()
        val client = DirectLink.iAmClient(localId, remoteId)
        val fallbackAt = System.currentTimeMillis() + 700
        while (System.currentTimeMillis() < deadline) {
            val preferred = if (client) connected.get() else accepted.get()
            if (preferred != null) return preferred.also { dropOther(if (client) accepted else connected, it) }
            if (System.currentTimeMillis() >= fallbackAt) {
                val other = if (client) accepted.get() else connected.get()
                if (other != null) return other.also { dropOther(if (client) connected else accepted, it) }
            }
            Thread.sleep(20)
        }
        dropOther(accepted, null)
        dropOther(connected, null)
        return null
    }

    private fun dropOther(slot: AtomicReference<Socket?>, keep: Socket?) {
        val extra = slot.getAndSet(null)
        if (extra != null && extra !== keep) runCatching { extra.close() }
    }

    companion object {
        fun start(seen: String?, lan: List<String>): DirectAttempt {
            val udp = DatagramSocket(0)
            val tcp = ServerSocket(0)
            val token = UUID.randomUUID().toString()
            return DirectAttempt(udp, tcp, DirectLink.offerJson(token, udp.localPort, tcp.localPort, seen, lan))
        }
    }
}
