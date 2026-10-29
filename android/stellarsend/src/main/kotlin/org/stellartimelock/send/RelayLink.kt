package org.stellartimelock.send

import org.stellartimelock.send.core.RelayAddress
import org.stellartimelock.send.core.RelayAuth
import org.stellartimelock.send.core.RelayJoin
import org.stellartimelock.send.core.RelayPlans
import org.stellartimelock.send.core.RelayQuotaException
import org.stellartimelock.send.core.RelayReply
import org.stellartimelock.send.core.RelayWake
import org.stellartimelock.send.core.RelayWakeException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

internal object RelaySeen {
    private val map = java.util.concurrent.ConcurrentHashMap<Int, String>()

    fun put(socket: java.net.Socket, seen: String?) {
        if (!seen.isNullOrBlank()) map[System.identityHashCode(socket)] = seen
    }

    fun take(socket: java.net.Socket): String? = map.remove(System.identityHashCode(socket))
}

internal object RelayQuotaBus {
    val message = AtomicReference<String?>(null)
}

internal object RelayLink {
    val lastClose = AtomicReference<String?>(null)

    fun open(
        url: String,
        room: String,
        role: String,
        auth: RelayAuth? = null,
        onWaiting: () -> Unit = {},
        onReady: (RelayReply) -> Unit = {},
        deviceId: String? = null,
        onJoined: (Socket) -> Unit = {}
    ): Socket {
        RelayQuotaBus.message.set(null)
        val endpoint = RelayAddress.parse(url) ?: throw java.io.IOException("Relay address must be a wss URL.")
        if (role == "listen" && healthAwake(endpoint, 5_000)) {
            try {
                return openOnce(endpoint, room, role, auth, RelayWake.LISTEN_WAIT_MS.toInt(), onReady, deviceId, onJoined)
            } catch (ex: InterruptedException) {
                throw ex
            } catch (ex: RelayQuotaException) {
                throw ex
            } catch (ex: Exception) {
                throw java.io.IOException(RelayWake.NO_PEER, ex)
            }
        }
        var last: Exception? = null
        var relayAwake = false
        for (attempt in RelayWake.attempts()) {
            if (attempt.delayMs > 0) {
                onWaiting()
                Thread.sleep(attempt.delayMs)
            }
            val announced = AtomicBoolean(false)
            val timer = Thread({
                try {
                    Thread.sleep(1500)
                    if (announced.compareAndSet(false, true)) onWaiting()
                } catch (_: InterruptedException) {
                }
            }, "stellarsend-relay-wake").apply { isDaemon = true; start() }
            try {
                if (!healthAwake(endpoint, attempt.timeoutMs.toInt())) {
                    last = java.io.IOException("The relay has not woken up yet.")
                    onWaiting()
                    continue
                }
                relayAwake = true
                return openOnce(endpoint, room, role, auth, attempt.timeoutMs.toInt(), onReady, deviceId, onJoined)
            } catch (ex: RelayQuotaException) {
                throw ex
            } catch (ex: InterruptedException) {
                throw ex
            } catch (ex: Exception) {
                last = ex
                onWaiting()
            } finally {
                timer.interrupt()
            }
        }
        throw RelayWakeException(RelayWake.afterAttempts(relayAwake), last)
    }

    fun usage(url: String, auth: RelayAuth?, onWaiting: () -> Unit = {}): RelayReply {
        val endpoint = RelayAddress.parse(url) ?: throw java.io.IOException("Relay address must be a wss URL.")
        var last: Exception? = null
        for (attempt in RelayWake.attempts()) {
            if (attempt.delayMs > 0) {
                onWaiting()
                Thread.sleep(attempt.delayMs)
            }
            try {
                if (!healthAwake(endpoint, attempt.timeoutMs.toInt())) {
                    onWaiting()
                    last = java.io.IOException("The relay has not woken up yet.")
                    continue
                }
                return usageOnce(endpoint, auth, attempt.timeoutMs.toInt())
            } catch (ex: RelayQuotaException) {
                throw ex
            } catch (ex: InterruptedException) {
                throw ex
            } catch (ex: Exception) {
                last = ex
                onWaiting()
            }
        }
        throw RelayWakeException(RelayWake.GAVE_UP, last)
    }

    private fun openOnce(
        endpoint: org.stellartimelock.send.core.RelayEndpoint,
        room: String,
        role: String,
        auth: RelayAuth?,
        timeoutMs: Int,
        onReady: (RelayReply) -> Unit,
        deviceId: String? = null,
        onJoined: (Socket) -> Unit = {}
    ): Socket {
        val tcp = Socket()
        tcp.tcpNoDelay = true
        tcp.keepAlive = true
        tcp.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
        tcp.soTimeout = timeoutMs
        val remote: Socket = if (endpoint.secure) {
            val ssl = SSLContext.getDefault().socketFactory.createSocket(tcp, endpoint.host, endpoint.port, true) as SSLSocket
            ssl.startHandshake()
            ssl
        } else {
            tcp
        }
        remote.soTimeout = timeoutMs
        val input = remote.getInputStream()
        val output = remote.getOutputStream()
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val hostHeader = if (endpoint.port == 443 || endpoint.port == 80) endpoint.host else "${endpoint.host}:${endpoint.port}"
        val request = buildString {
            append("GET ${endpoint.path} HTTP/1.1\r\n")
            append("Host: $hostHeader\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()
        val headers = readHeaders(input)
        if (!headers.contains(" 101 ")) throw java.io.IOException("The relay did not connect.")
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
        )
        if (!headers.contains(accept)) throw java.io.IOException("The relay did not connect.")
        sendFrame(output, 0x1, RelayJoin.write(room, role, auth, deviceId).toByteArray(Charsets.UTF_8))
        onJoined(tcp)
        val joinPingStop = AtomicBoolean(false)
        Thread({
            try {
                while (!joinPingStop.get()) {
                    Thread.sleep(org.stellartimelock.send.core.RelayWake.CLIENT_PING_MS)
                    if (joinPingStop.get()) break
                    sendFrame(output, 0x9, ByteArray(0))
                }
            } catch (_: Exception) {
            }
        }, "stellarsend-relay-join-ping").apply { isDaemon = true }.start()
        val replyText = try {
            readJoinText(input, output)
        } finally {
            joinPingStop.set(true)
        }
        val reply = runCatching { RelayJoin.parseReply(replyText) }.getOrElse {
            remote.close()
            throw java.io.IOException("The relay did not connect.")
        }
        if (reply.quota) {
            remote.close()
            throw RelayQuotaException(reply.message ?: RelayPlans.QUOTA_MESSAGE)
        }
        if (!reply.ok) {
            remote.close()
            throw java.io.IOException("The relay did not connect.")
        }
        onReady(reply)
        remote.soTimeout = 0
        tcp.soTimeout = 0
        val local = bridge(remote, input, output)
        RelaySeen.put(local, reply.seen)
        return local
    }

    private fun usageOnce(endpoint: org.stellartimelock.send.core.RelayEndpoint, auth: RelayAuth?, timeoutMs: Int): RelayReply {
        val tcp = Socket()
        tcp.tcpNoDelay = true
        tcp.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
        tcp.soTimeout = timeoutMs
        val remote: Socket = if (endpoint.secure) {
            val ssl = SSLContext.getDefault().socketFactory.createSocket(tcp, endpoint.host, endpoint.port, true) as SSLSocket
            ssl.startHandshake()
            ssl
        } else {
            tcp
        }
        remote.soTimeout = timeoutMs
        return try {
            val input = remote.getInputStream()
            val output = remote.getOutputStream()
            handshakeHeaders(endpoint, output, input)
            sendFrame(output, 0x1, RelayJoin.usage(auth).toByteArray(Charsets.UTF_8))
            val reply = RelayJoin.parseReply(text(readData(input)))
            if (reply.quota) throw RelayQuotaException(reply.message ?: RelayPlans.QUOTA_MESSAGE)
            if (!reply.ok) throw java.io.IOException("The relay did not connect.")
            reply
        } finally {
            runCatching { remote.close() }
        }
    }

    private fun healthAwake(endpoint: org.stellartimelock.send.core.RelayEndpoint, timeoutMs: Int): Boolean {
        val scheme = if (endpoint.secure) "https" else "http"
        val authority = if ((endpoint.secure && endpoint.port == 443) || (!endpoint.secure && endpoint.port == 80)) {
            endpoint.host
        } else {
            "${endpoint.host}:${endpoint.port}"
        }
        return try {
            val connection = URL("$scheme://$authority/healthz").openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            connection.disconnect()
            code == 200 || code == 404
        } catch (_: Exception) {
            false
        }
    }

    private fun handshakeHeaders(endpoint: org.stellartimelock.send.core.RelayEndpoint, output: OutputStream, input: InputStream) {
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val hostHeader = if (endpoint.port == 443 || endpoint.port == 80) endpoint.host else "${endpoint.host}:${endpoint.port}"
        val request = buildString {
            append("GET ${endpoint.path} HTTP/1.1\r\n")
            append("Host: $hostHeader\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()
        val headers = readHeaders(input)
        if (!headers.contains(" 101 ")) throw java.io.IOException("The relay did not connect.")
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
        )
        if (!headers.contains(accept)) throw java.io.IOException("The relay did not connect.")
    }

    private fun bridge(remote: Socket, input: InputStream, output: OutputStream): Socket {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val ready = CountDownLatch(1)
        var accepted: Socket? = null
        Thread({
            try {
                accepted = server.accept().also { it.tcpNoDelay = true }
                ready.countDown()
                pump(accepted!!, remote, input, output)
            } catch (_: Exception) {
                ready.countDown()
                runCatching { remote.close() }
            } finally {
                runCatching { server.close() }
            }
        }, "stellarsend-relay").apply { isDaemon = true }.start()
        val local = Socket()
        local.tcpNoDelay = true
        local.connect(InetSocketAddress("127.0.0.1", server.localPort), 3000)
        if (!ready.await(3, TimeUnit.SECONDS)) {
            local.close()
            remote.close()
            throw java.io.IOException("The relay did not connect.")
        }
        return local
    }

    private fun pump(local: Socket, remote: Socket, input: InputStream, output: OutputStream) {
        val pingStop = AtomicBoolean(false)
        Thread({
            try {
                while (!pingStop.get()) {
                    Thread.sleep(org.stellartimelock.send.core.RelayWake.CLIENT_PING_MS)
                    if (pingStop.get()) break
                    sendFrame(output, 0x9, ByteArray(0))
                }
            } catch (_: Exception) {
            }
        }, "stellarsend-relay-ping").apply { isDaemon = true }.start()
        Thread({
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val n = local.getInputStream().read(buffer)
                    if (n < 0) break
                    sendFrame(output, 0x2, buffer.copyOf(n))
                }
            } catch (_: Exception) {
            } finally {
                pingStop.set(true)
                runCatching { sendFrame(output, 0x8, ByteArray(0)) }
                runCatching { remote.close() }
                runCatching { local.close() }
            }
        }, "stellarsend-relay-up").apply { isDaemon = true }.start()
        try {
            val localOut = local.getOutputStream()
            while (true) {
                val (opcode, payload) = readData(input)
                when (opcode) {
                    0x2 -> {
                        localOut.write(payload)
                        localOut.flush()
                    }
                    0x8 -> {
                        val close = org.stellartimelock.send.core.RelayClose.describe(payload)
                        lastClose.set(close)
                        android.util.Log.i("StellarSend", "Relay idle disconnect. $close")
                        break
                    }
                    0x9 -> sendFrame(output, 0xA, payload) // answer a relay keepalive ping
                    0x1 -> {
                        val reply = runCatching { RelayJoin.parseReply(payload.toString(Charsets.UTF_8)) }.getOrNull()
                        if (reply?.quota == true) {
                            RelayQuotaBus.message.set(reply.message ?: RelayPlans.QUOTA_MESSAGE)
                            break
                        }
                    }
                    else -> Unit
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { local.close() }
            runCatching { remote.close() }
        }
    }

    private fun sendFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        synchronized(output) {
            val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
            val header = ByteArrayOutputStream()
            header.write(0x80 or opcode)
            val length = payload.size
            when {
                length < 126 -> header.write(0x80 or length)
                length < 65536 -> {
                    header.write(0x80 or 126)
                    header.write(length shr 8)
                    header.write(length and 0xFF)
                }
                else -> throw java.io.IOException("Relay frame is too large.")
            }
            header.write(mask)
            val masked = ByteArray(payload.size)
            for (index in payload.indices) {
                masked[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
            }
            output.write(header.toByteArray())
            output.write(masked)
            output.flush()
        }
    }

    private fun readData(input: InputStream): Pair<Int, ByteArray> {
        while (true) {
            val first = input.read()
            val second = input.read()
            if (first < 0 || second < 0) return 0x8 to ByteArray(0)
            val opcode = first and 0x0F
            var length = second and 0x7F
            val masked = second and 0x80 != 0
            if (length == 126) {
                val hi = input.read()
                val lo = input.read()
                if (hi < 0 || lo < 0) return 0x8 to ByteArray(0)
                length = (hi shl 8) or lo
            } else if (length == 127) {
                throw java.io.IOException("Relay frame is too large.")
            }
            val mask = if (masked) readExactly(input, 4) else null
            val data = readExactly(input, length)
            if (mask != null) {
                for (index in data.indices) {
                    data[index] = (data[index].toInt() xor mask[index % 4].toInt()).toByte()
                }
            }
            if (opcode == 0x0) continue
            return opcode to data
        }
    }

    private fun text(frame: Pair<Int, ByteArray>): String = frame.second.toString(Charsets.UTF_8)

    /**
     * Wait for the join/ready text frame. Answer server pings so an idle listen
     * is not closed by treating a ping as the join reply (the ~85 s drop).
     */
    private fun readJoinText(input: InputStream, output: OutputStream): String {
        while (true) {
            val (opcode, payload) = readData(input)
            when (opcode) {
                0x1 -> return payload.toString(Charsets.UTF_8)
                0x9 -> sendFrame(output, 0xA, payload)
                0x8 -> {
                    val close = org.stellartimelock.send.core.RelayClose.describe(payload)
                    lastClose.set(close)
                    throw java.io.IOException("The relay closed the connection. $close")
                }
                0xA -> Unit
                else -> Unit
            }
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(data, offset, length - offset)
            if (n < 0) throw java.io.IOException("The relay closed the connection.")
            offset += n
        }
        return data
    }

    private fun readHeaders(input: InputStream): String {
        val out = ByteArrayOutputStream()
        var matched = 0
        val marker = byteArrayOf(13, 10, 13, 10)
        while (matched < 4) {
            val value = input.read()
            if (value < 0) break
            out.write(value)
            matched = if (value == marker[matched].toInt()) matched + 1 else 0
            if (out.size() > 8192) break
        }
        return out.toString(Charsets.US_ASCII)
    }
}
