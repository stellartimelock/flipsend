package org.stellartimelock.send.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * Android network transport: delegates to java.net sockets and javax.net.ssl.
 * This mirrors the approach in android/stellarsend/Engine.kt.
 */
actual class NetworkTransportFactory actual constructor() {

    actual suspend fun connect(
        host: String,
        port: Int,
        local: DeviceIdentity,
        expectedFingerprint: String
    ): PeerConnection = withContext(Dispatchers.IO) {
        val sslContext = AndroidTlsPins.client(local, expectedFingerprint)
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 10_000)
        raw.soTimeout = 60_000
        val ssl = sslContext.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        AndroidTlsPins.configure(ssl, clientMode = true)
        ssl.startHandshake()
        JavaSocketConnection(ssl)
    }

    actual suspend fun acceptOne(
        port: Int,
        local: DeviceIdentity,
        allowNewFingerprint: Boolean
    ): PeerConnection = withContext(Dispatchers.IO) {
        val serverSsl = AndroidTlsPins.serverContext(local)
        val server = java.net.ServerSocket(port)
        server.use {
            val raw = it.accept()
            raw.soTimeout = 60_000
            val ssl = serverSsl.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
            AndroidTlsPins.configure(ssl, clientMode = false)
            ssl.startHandshake()
            JavaSocketConnection(ssl)
        }
    }

    actual fun discover(timeoutMs: Long): Flow<DiscoveredPeer> = flow {
        withContext(Dispatchers.IO) {
            val socket = DatagramSocket(ProtocolLimits.UDP_PORT)
            socket.soTimeout = 2000
            val buf = ByteArray(512)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    val map = BeaconCodec.tryParse(data) ?: continue
                    val p = map["port"]?.toIntOrNull() ?: continue
                    emit(DiscoveredPeer(packet.address.hostAddress ?: continue, p, map["name"] ?: "", map["id"] ?: ""))
                } catch (_: Exception) { /* timeout or parse error, keep trying */ }
            }
            socket.close()
        }
    }

    actual suspend fun announce(local: DeviceIdentity, port: Int, pairingCode: String?) {
        withContext(Dispatchers.IO) {
            val data = BeaconCodec.encode(local.deviceId, port, local.name, pairingCode)
            val socket = DatagramSocket()
            socket.broadcast = true
            val packet = DatagramPacket(data, data.size, java.net.InetSocketAddress("255.255.255.255", ProtocolLimits.UDP_PORT))
            socket.send(packet)
            socket.close()
        }
    }
}

private class JavaSocketConnection(private val socket: java.net.Socket) : PeerConnection {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        output.write(data); output.flush()
    }

    override suspend fun readExact(count: Int): ByteArray = withContext(Dispatchers.IO) {
        val buf = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buf, read, count - read)
            if (n < 0) throw java.io.EOFException("Connection closed.")
            read += n
        }
        buf
    }

    override fun close() = runCatching { socket.close() }.let {}
}

/** TLS helpers for the shared module's Android transport. */
private object AndroidTlsPins {
    fun client(identity: DeviceIdentity, expectedFingerprint: String): javax.net.ssl.SSLContext =
        context(identity, expectedFingerprint, allowNew = false)

    fun serverContext(identity: DeviceIdentity): javax.net.ssl.SSLContext =
        context(identity, expectedFingerprint = null, allowNew = true)

    fun configure(socket: SSLSocket, clientMode: Boolean) {
        socket.useClientMode = clientMode
        socket.enabledProtocols = arrayOf("TLSv1.2")
        val params = socket.sslParameters
        params.endpointIdentificationAlgorithm = null
        socket.sslParameters = params
        if (!clientMode) socket.needClientAuth = true
    }

    private fun context(identity: DeviceIdentity, expectedFingerprint: String?, allowNew: Boolean): javax.net.ssl.SSLContext {
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        val certificate = certFactory.generateCertificate(identity.certificateBytes.inputStream())
                as java.security.cert.X509Certificate
        val privateKey = java.security.KeyFactory.getInstance("EC")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(identity.privateKeyBytes))

        val keyManager = object : javax.net.ssl.X509ExtendedKeyManager() {
            override fun getClientAliases(kt: String?, i: Array<out java.security.Principal>?) = arrayOf("d")
            override fun chooseClientAlias(kt: Array<out String>?, i: Array<out java.security.Principal>?, s: java.net.Socket?) = "d"
            override fun chooseEngineClientAlias(kt: Array<out String>?, i: Array<out java.security.Principal>?, e: javax.net.ssl.SSLEngine?) = "d"
            override fun getServerAliases(kt: String?, i: Array<out java.security.Principal>?) = arrayOf("d")
            override fun chooseServerAlias(kt: String?, i: Array<out java.security.Principal>?, s: java.net.Socket?) = "d"
            override fun chooseEngineServerAlias(kt: String?, i: Array<out java.security.Principal>?, e: javax.net.ssl.SSLEngine?) = "d"
            override fun getCertificateChain(alias: String?) = arrayOf(certificate)
            override fun getPrivateKey(alias: String?) = privateKey
        }
        val trust = object : javax.net.ssl.X509ExtendedTrustManager() {
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = check(chain)
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = check(chain)
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, socket: java.net.Socket?) = check(chain)
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, socket: java.net.Socket?) = check(chain)
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine?) = check(chain)
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine?) = check(chain)
            private fun check(chain: Array<java.security.cert.X509Certificate>) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("Missing peer certificate.")
                val fp = sha256Hex(chain[0].publicKey.encoded)
                if (expectedFingerprint != null && !pinsEqual(fp, expectedFingerprint)) {
                    throw java.security.cert.CertificateException("The paired device key changed.")
                }
                if (expectedFingerprint == null && !allowNew) {
                    throw java.security.cert.CertificateException("Unknown peer.")
                }
            }
        }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.2")
        ctx.init(arrayOf(keyManager), arrayOf(trust), java.security.SecureRandom())
        return ctx
    }
}
