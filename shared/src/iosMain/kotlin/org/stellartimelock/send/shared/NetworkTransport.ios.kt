@file:Suppress("UNCHECKED_CAST")
package org.stellartimelock.send.shared

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import platform.CFNetwork.CFReadStreamRef
import platform.CFNetwork.CFWriteStreamRef
import platform.CFNetwork.kCFStreamPropertySSLSettings
import platform.CFNetwork.kCFStreamSSLCertificates
import platform.CFNetwork.kCFStreamSSLValidatesCertificateChain
import platform.CFNetwork.CFStreamCreatePairWithSocket
import platform.CFNetwork.CFStreamCreatePairWithSocketToHost
import platform.CoreFoundation.CFArrayCreateMutable
import platform.CoreFoundation.CFArrayAppendValue
import platform.CoreFoundation.CFArrayRef
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFReadStreamClose
import platform.CoreFoundation.CFReadStreamOpen
import platform.CoreFoundation.CFReadStreamRead
import platform.CoreFoundation.CFReadStreamSetProperty
import platform.CoreFoundation.CFWriteStreamClose
import platform.CoreFoundation.CFWriteStreamOpen
import platform.CoreFoundation.CFWriteStreamSetProperty
import platform.CoreFoundation.CFWriteStreamWrite
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeArrayCallBacks
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSURLSessionWebSocketMessageTypeData
import platform.Foundation.create
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrLabel
import platform.Security.kSecClass
import platform.Security.kSecClassCertificate
import platform.Security.kSecClassIdentity
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef
import platform.Security.kSecValueRef
import platform.posix.AF_INET
import platform.posix.IPPROTO_TCP
import platform.posix.IPPROTO_UDP
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_BROADCAST
import platform.posix.SO_REUSEADDR
import platform.posix.TCP_NODELAY
import platform.posix.accept as posixAccept
import platform.posix.bind
import platform.posix.close as posixClose
import platform.posix.connect as posixConnect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.htonl
import platform.posix.htons
import platform.posix.listen as posixListen
import platform.posix.memset
import platform.posix.read as posixRead
import platform.posix.recv
import platform.posix.select
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval
import platform.posix.write as posixWrite

/**
 * iOS network transport: POSIX + CFStream for direct TLS,
 * NSURLSession WebSocket for relay, POSIX UDP for discovery.
 * Mirrors android/stellarsend Engine.kt + Lan.kt + RelayLink.kt.
 */
@OptIn(ExperimentalForeignApi::class)
actual class NetworkTransportFactory actual constructor() {

    // ----- Direct LAN: mutual TLS over TCP (CFStream) ------------------------------------

    actual suspend fun connect(
        host: String,
        port: Int,
        local: DeviceIdentity,
        expectedFingerprint: String
    ): PeerConnection = withContext(Dispatchers.Default) {
        CfStreamConnection.connect(host, port, local, expectedFingerprint, allowNew = false)
    }

    actual suspend fun acceptOne(
        port: Int,
        local: DeviceIdentity,
        allowNewFingerprint: Boolean
    ): PeerConnection = withContext(Dispatchers.Default) {
        CfStreamConnection.listen(port, local, allowNew = allowNewFingerprint)
    }

    // ----- UDP discovery / announcement --------------------------------------------------

    actual fun discover(timeoutMs: Long): Flow<DiscoveredPeer> = flow {
        for (peer in udpDiscover(timeoutMs)) emit(peer)
    }

    actual suspend fun announce(local: DeviceIdentity, port: Int, pairingCode: String?) {
        withContext(Dispatchers.Default) {
            udpAnnounce(local.deviceId, port, local.name, pairingCode)
        }
    }

    // ----- Relay: WebSocket over HTTPS 443, bridged to POSIX pipe pair -------------------

    /**
     * Connect to the relay, retrying if it is still waking.
     * Returns a [PeerConnection] backed by a POSIX pipe so the protocol code
     * runs unchanged over the WebSocket tunnel (mirrors RelayLink.open()).
     */
    suspend fun openRelay(
        relayUrl: String,
        room: String,
        role: String,
        entitlementKey: String? = null,
        onWaiting: () -> Unit = {}
    ): PeerConnection = withContext(Dispatchers.Default) {
        val endpoint = RelayAddress.parse(relayUrl)
            ?: throw java.io.IOException("Relay address must be a wss:// or https:// URL.")
        var last: Exception? = null
        for (attempt in RelayWake.attempts()) {
            if (attempt.delayMs > 0) {
                onWaiting()
                platform.posix.usleep((attempt.delayMs * 1_000L).toUInt())
            }
            if (!relayHealthy(endpoint)) {
                onWaiting()
                last = java.io.IOException("The relay has not woken up yet.")
                continue
            }
            try {
                return@withContext openRelayWebSocket(relayUrl, room, role, entitlementKey)
            } catch (e: Exception) {
                last = e
                onWaiting()
            }
        }
        throw java.io.IOException(RelayWake.GAVE_UP, last)
    }
}

// ===================================================================================
// CFStream mutual-TLS connection
// ===================================================================================

@OptIn(ExperimentalForeignApi::class)
private class CfStreamConnection(
    private val readStream: CFReadStreamRef,
    private val writeStream: CFWriteStreamRef
) : PeerConnection {

    override suspend fun write(data: ByteArray): Unit = withContext(Dispatchers.Default) {
        var written = 0
        data.usePinned { pinned ->
            while (written < data.size) {
                val n = CFWriteStreamWrite(writeStream, pinned.addressOf(written).reinterpret(), (data.size - written).convert())
                if (n <= 0) throw java.io.IOException("CFWriteStreamWrite failed: $n")
                written += n.toInt()
            }
        }
    }

    override suspend fun readExact(count: Int): ByteArray = withContext(Dispatchers.Default) {
        val buf = ByteArray(count)
        var read = 0
        buf.usePinned { pinned ->
            while (read < count) {
                val n = CFReadStreamRead(readStream, pinned.addressOf(read).reinterpret(), (count - read).convert())
                if (n <= 0) throw java.io.EOFException("CFReadStreamRead returned $n")
                read += n.toInt()
            }
        }
        buf
    }

    override fun close() {
        CFReadStreamClose(readStream)
        CFWriteStreamClose(writeStream)
        CFRelease(readStream)
        CFRelease(writeStream)
    }

    companion object {
        fun connect(host: String, port: Int, local: DeviceIdentity, expectedFp: String, allowNew: Boolean): CfStreamConnection {
            memScoped {
                val readRef = alloc<platform.CoreFoundation.CFReadStreamRefVar>()
                val writeRef = alloc<platform.CoreFoundation.CFWriteStreamRefVar>()
                val cfHost = NSString.create(string = host) as platform.CoreFoundation.CFStringRef
                CFStreamCreatePairWithSocketToHost(kCFAllocatorDefault, cfHost, port.toUInt(), readRef.ptr, writeRef.ptr)
                val rs = readRef.value ?: throw java.io.IOException("Could not create read stream for $host:$port")
                val ws = writeRef.value ?: throw java.io.IOException("Could not create write stream for $host:$port")
                applySslSettings(rs, ws, local, isServer = false)
                openStreams(rs, ws)
                verifyPeer(rs, expectedFp, allowNew)
                return CfStreamConnection(rs, ws)
            }
        }

        fun listen(port: Int, local: DeviceIdentity, allowNew: Boolean): CfStreamConnection {
            // POSIX accept, then wrap fd in CFStream + TLS
            val serverFd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
            if (serverFd < 0) throw java.io.IOException("socket() failed")
            try {
                memScoped {
                    val on = alloc<platform.posix.IntVar>(); on.value = 1
                    setsockopt(serverFd, SOL_SOCKET, SO_REUSEADDR, on.ptr, sizeOf<platform.posix.IntVar>().convert())
                    val addr = alloc<sockaddr_in>()
                    memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
                    addr.sin_family = AF_INET.convert()
                    addr.sin_port = htons(port.toUShort())
                    addr.sin_addr.s_addr = htonl(0u)
                    bind(serverFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
                    posixListen(serverFd, 1)
                    val clientFd = posixAccept(serverFd, null, null)
                    if (clientFd < 0) throw java.io.IOException("accept() failed: $errno")
                    memScoped {
                        val readRef = alloc<platform.CoreFoundation.CFReadStreamRefVar>()
                        val writeRef = alloc<platform.CoreFoundation.CFWriteStreamRefVar>()
                        CFStreamCreatePairWithSocket(kCFAllocatorDefault, clientFd, readRef.ptr, writeRef.ptr)
                        val rs = readRef.value ?: throw java.io.IOException("Could not create read stream")
                        val ws = writeRef.value ?: throw java.io.IOException("Could not create write stream")
                        applySslSettings(rs, ws, local, isServer = true)
                        openStreams(rs, ws)
                        verifyPeer(rs, null, allowNew)
                        return CfStreamConnection(rs, ws)
                    }
                }
            } finally {
                posixClose(serverFd)
            }
        }

        private fun applySslSettings(rs: CFReadStreamRef, ws: CFWriteStreamRef, identity: DeviceIdentity, isServer: Boolean) {
            val secIdentity = IosKeychain.getOrCreate(identity)
            val certArray = CFArrayCreateMutable(kCFAllocatorDefault, 1, kCFTypeArrayCallBacks)!!
            CFArrayAppendValue(certArray, secIdentity)

            // Build the SSL settings dictionary
            val keys = arrayOf(
                kCFStreamSSLCertificates,
                kCFStreamSSLValidatesCertificateChain
            )
            val values = arrayOf(
                certArray,
                kCFBooleanFalse
            )
            val dict = platform.CoreFoundation.CFDictionaryCreate(
                kCFAllocatorDefault,
                keys.toCFValues(), values.toCFValues(),
                keys.size.convert(),
                platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks.ptr,
                platform.CoreFoundation.kCFTypeDictionaryValueCallBacks.ptr
            )!!

            CFReadStreamSetProperty(rs, kCFStreamPropertySSLSettings, dict)
            CFWriteStreamSetProperty(ws, kCFStreamPropertySSLSettings, dict)
            CFRelease(dict)
            CFRelease(certArray)
        }

        private fun openStreams(rs: CFReadStreamRef, ws: CFWriteStreamRef) {
            if (!CFReadStreamOpen(rs)) throw java.io.IOException("CFReadStreamOpen failed")
            if (!CFWriteStreamOpen(ws)) throw java.io.IOException("CFWriteStreamOpen failed")
            // Wait until streams are ready (up to 10 s)
            val deadline = NSDate.date().timeIntervalSince1970 + 10.0
            while (NSDate.date().timeIntervalSince1970 < deadline) {
                val rStatus = platform.CFNetwork.CFReadStreamGetStatus(rs)
                val wStatus = platform.CFNetwork.CFWriteStreamGetStatus(ws)
                if (rStatus == platform.CFNetwork.kCFStreamStatusOpen &&
                    wStatus == platform.CFNetwork.kCFStreamStatusOpen) break
                if (rStatus == platform.CFNetwork.kCFStreamStatusError ||
                    wStatus == platform.CFNetwork.kCFStreamStatusError) {
                    throw java.io.IOException("TLS stream error during open.")
                }
                platform.posix.usleep(10_000u)
            }
        }

        private fun verifyPeer(rs: CFReadStreamRef, expectedFp: String?, allowNew: Boolean) {
            val sslCtx = platform.CFNetwork.CFReadStreamCopyProperty(rs, kCFStreamPropertySSLContext)
                ?: return  // no SSL context to verify
            defer { CFRelease(sslCtx) }
            val ctx = sslCtx.reinterpret<platform.Security.OpaqueSSLContext>()
            memScoped {
                val certs = alloc<platform.CoreFoundation.CFArrayRefVar>()
                platform.Security.SSLCopyPeerCertificates(ctx, certs.ptr)
                val peerCerts = certs.value ?: return
                defer { CFRelease(peerCerts) }
                if (platform.CoreFoundation.CFArrayGetCount(peerCerts) == 0L) {
                    throw java.io.IOException("No peer certificate.")
                }
                val certRef = platform.CoreFoundation.CFArrayGetValueAtIndex(peerCerts, 0)
                    ?.reinterpret<platform.Security.OpaqueSecCertificate>()
                    ?: throw java.io.IOException("Could not read peer cert.")
                val certData = platform.Security.SecCertificateCopyData(certRef)
                    ?: throw java.io.IOException("Could not copy peer cert data.")
                defer { CFRelease(certData) }
                val certBytes = (certData as platform.Foundation.NSData).toByteArray()
                val spki = extractSpkiFromDer(certBytes)
                val peerFp = sha256Hex(spki)
                if (expectedFp != null && !pinsEqual(peerFp, expectedFp)) {
                    throw java.io.IOException("The paired device key changed. Unpair and pair again.")
                }
                if (expectedFp == null && !allowNew) {
                    throw java.io.IOException("This device is not paired.")
                }
            }
        }

        // Convenience: run block then release
        private inline fun defer(crossinline block: () -> Unit): Unit = block()
    }
}

// ===================================================================================
// Array helper for CFDictionaryCreate
// ===================================================================================

@OptIn(ExperimentalForeignApi::class)
private fun Array<*>.toCFValues(): kotlinx.cinterop.CPointer<kotlinx.cinterop.COpaquePointerVar>? =
    memScoped {
        val arr = allocArray<kotlinx.cinterop.COpaquePointerVar>(size)
        forEachIndexed { i, v -> arr[i] = v as? kotlinx.cinterop.CValuesRef<*> as kotlinx.cinterop.COpaquePointer? }
        arr
    }

// ===================================================================================
// Relay: WebSocket over HTTPS 443, bridged to a POSIX pipe pair
// ===================================================================================

@OptIn(ExperimentalForeignApi::class)
private fun openRelayWebSocket(
    relayUrl: String,
    room: String,
    role: String,
    entitlementKey: String?
): PeerConnection {
    // Build wss:// URL from the relay URL
    val wsUrl = relayUrl
        .replace(Regex("^https://"), "wss://")
        .replace(Regex("^http://"), "ws://")
    val url = NSURL.URLWithString(wsUrl) ?: throw java.io.IOException("Invalid relay URL: $relayUrl")

    // Pipe pair: Kotlin reads from pipeToKotlin[0]; WebSocket pump writes there.
    //            Kotlin writes to pipeToRelay[1]; pump reads and forwards.
    val pipeToKotlin = memScoped { allocArray<platform.posix.IntVar>(2).also { platform.posix.pipe(it) } }
    val pipeToRelay  = memScoped { allocArray<platform.posix.IntVar>(2).also { platform.posix.pipe(it) } }
    val readFd  = pipeToKotlin[0]
    val writeKotlinFd = pipeToKotlin[1]  // WebSocket pump writes here
    val readRelayFd = pipeToRelay[0]     // pump reads here
    val writeFd = pipeToRelay[1]         // Kotlin writes here

    val task = NSURLSession.sharedSession().webSocketTaskWithURL(url)

    // Send join message
    val joinMsg = RelayJoin.write(room, role, entitlementKey)
    task.sendMessage(NSURLSessionWebSocketMessage(joinMsg)) { _ -> }
    task.resume()

    // Pump: WebSocket → pipe[writeKotlinFd]
    fun scheduleRelayRead() {
        task.receiveMessageWithCompletionHandler { msg, err ->
            if (err != null || msg == null) { posixClose(writeKotlinFd); return@receiveMessageWithCompletionHandler }
            val bytes = when (msg.type) {
                NSURLSessionWebSocketMessageTypeData -> msg.data?.toByteArray()
                else -> return@receiveMessageWithCompletionHandler also { scheduleRelayRead() }
            } ?: return@receiveMessageWithCompletionHandler also { scheduleRelayRead() }

            // Intercept quota/relay text replies
            bytes.usePinned { pinned ->
                var written = 0
                while (written < bytes.size) {
                    val n = posixWrite(writeKotlinFd, pinned.addressOf(written), (bytes.size - written).convert()).toInt()
                    if (n <= 0) { posixClose(writeKotlinFd); return@receiveMessageWithCompletionHandler }
                    written += n
                }
            }
            scheduleRelayRead()
        }
    }
    scheduleRelayRead()

    // Pump: pipe[readRelayFd] → WebSocket
    Thread {
        val buf = ByteArray(16_384)
        buf.usePinned { pinned ->
            while (true) {
                val n = posixRead(readRelayFd, pinned.addressOf(0), buf.size.convert()).toInt()
                if (n <= 0) break
                val nsData = buf.copyOf(n).toNSData()
                val sem = NSCondition()
                sem.lock()
                task.sendMessage(NSURLSessionWebSocketMessage(nsData)) { _ -> sem.lock(); sem.signal(); sem.unlock() }
                sem.waitUntilDate(NSDate.dateWithTimeIntervalSinceNow(30.0))
                sem.unlock()
            }
        }
        task.cancel()
        posixClose(readRelayFd)
    }.also { it.isDaemon = true; it.start() }

    return PipePeerConnection(readFd, writeFd, onClose = {
        task.cancel()
        posixClose(readFd); posixClose(writeFd)
        posixClose(writeKotlinFd); posixClose(readRelayFd)
    })
}

// ===================================================================================
// Pipe-backed PeerConnection (used by relay)
// ===================================================================================

@OptIn(ExperimentalForeignApi::class)
private class PipePeerConnection(
    private val readFd: Int,
    private val writeFd: Int,
    private val onClose: () -> Unit = {}
) : PeerConnection {

    override suspend fun write(data: ByteArray): Unit = withContext(Dispatchers.Default) {
        var sent = 0
        data.usePinned { pinned ->
            while (sent < data.size) {
                val n = posixWrite(writeFd, pinned.addressOf(sent), (data.size - sent).convert()).toInt()
                if (n <= 0) throw java.io.IOException("Relay pipe write failed")
                sent += n
            }
        }
    }

    override suspend fun readExact(count: Int): ByteArray = withContext(Dispatchers.Default) {
        val buf = ByteArray(count)
        var got = 0
        buf.usePinned { pinned ->
            while (got < count) {
                val n = posixRead(readFd, pinned.addressOf(got), (count - got).convert()).toInt()
                if (n <= 0) throw java.io.EOFException("Relay pipe closed")
                got += n
            }
        }
        buf
    }

    override fun close() = onClose()
}

// ===================================================================================
// Relay health check (HttpURLConnection-equivalent via NSURLSession)
// ===================================================================================

private fun relayHealthy(endpoint: RelayEndpoint): Boolean {
    val scheme = if (endpoint.secure) "https" else "http"
    val authority = if ((endpoint.secure && endpoint.port == 443) || (!endpoint.secure && endpoint.port == 80)) endpoint.host
                    else "${endpoint.host}:${endpoint.port}"
    val url = NSURL.URLWithString("$scheme://$authority/healthz") ?: return false
    val req = NSMutableURLRequest.requestWithURL(url)
    req.timeoutInterval = RelayWake.ATTEMPT_MS / 1000.0
    var healthy = false
    val sem = NSCondition()
    sem.lock()
    NSURLSession.sharedSession().dataTaskWithRequest(req) { _, response, _ ->
        val code = (response as? platform.Foundation.NSHTTPURLResponse)?.statusCode?.toInt()
        healthy = code == 200 || code == 404
        sem.lock(); sem.signal(); sem.unlock()
    }.resume()
    sem.waitUntilDate(NSDate.dateWithTimeIntervalSinceNow(RelayWake.ATTEMPT_MS / 1000.0))
    sem.unlock()
    return healthy
}

// ===================================================================================
// UDP broadcast (pure POSIX)
// ===================================================================================

@OptIn(ExperimentalForeignApi::class)
private fun udpAnnounce(deviceId: String, port: Int, name: String, code: String?) {
    val payload = BeaconCodec.encode(deviceId, port, name, code)
    val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
    if (fd < 0) return
    try {
        memScoped {
            val on = alloc<platform.posix.IntVar>(); on.value = 1
            setsockopt(fd, SOL_SOCKET, SO_BROADCAST, on.ptr, sizeOf<platform.posix.IntVar>().convert())
            val dest = alloc<sockaddr_in>()
            memset(dest.ptr, 0, sizeOf<sockaddr_in>().convert())
            dest.sin_family = AF_INET.convert()
            dest.sin_port = htons(ProtocolLimits.UDP_PORT.toUShort())
            dest.sin_addr.s_addr = htonl(0xFFFFFFFFu)
            payload.usePinned { pinned ->
                sendto(fd, pinned.addressOf(0), payload.size.convert(), 0, dest.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            }
        }
    } finally { posixClose(fd) }
}

@OptIn(ExperimentalForeignApi::class)
private fun udpDiscover(timeoutMs: Long): List<DiscoveredPeer> {
    val results = mutableListOf<DiscoveredPeer>()
    val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
    if (fd < 0) return results
    try {
        memScoped {
            val on = alloc<platform.posix.IntVar>(); on.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, on.ptr, sizeOf<platform.posix.IntVar>().convert())
            val addr = alloc<sockaddr_in>()
            memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
            addr.sin_family = AF_INET.convert()
            addr.sin_port = htons(ProtocolLimits.UDP_PORT.toUShort())
            addr.sin_addr.s_addr = htonl(0u)
            bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            val tv = alloc<timeval>()
            tv.tv_sec = 0.convert(); tv.tv_usec = 300_000.convert()
            setsockopt(fd, SOL_SOCKET, platform.posix.SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
        }
        val buf = ByteArray(512)
        val deadline = NSDate.date().timeIntervalSince1970 * 1000 + timeoutMs
        while (NSDate.date().timeIntervalSince1970 * 1000 < deadline) {
            val n = buf.usePinned { pinned -> recv(fd, pinned.addressOf(0), buf.size.convert(), 0) }.toInt()
            if (n <= 0) continue
            val map = BeaconCodec.tryParse(buf.copyOf(n)) ?: continue
            val peerPort = map["port"]?.toIntOrNull() ?: continue
            results += DiscoveredPeer("", peerPort, map["name"] ?: "", map["id"] ?: "")
        }
    } finally { posixClose(fd) }
    return results
}

// ===================================================================================
// SPKI extraction from DER (matches Android sha256Hex(cert.publicKey.encoded))
// ===================================================================================

private fun extractSpkiFromDer(der: ByteArray): ByteArray {
    var pos = 0
    fun readLen(): Int {
        val first = der[pos++].toInt() and 0xFF
        return if (first < 0x80) first
        else {
            val n = first and 0x7F
            var len = 0
            repeat(n) { len = (len shl 8) or (der[pos++].toInt() and 0xFF) }
            len
        }
    }
    fun expectTag(tag: Int) { check((der[pos++].toInt() and 0xFF) == tag) { "Expected tag 0x${tag.toString(16)}" } }
    fun skip(len: Int) { pos += len }

    expectTag(0x30)           // SEQUENCE (Certificate)
    readLen()
    expectTag(0x30)           // SEQUENCE (TBSCertificate)
    val tbsLen = readLen()
    val tbsEnd = pos + tbsLen
    while (pos < tbsEnd) {
        val tag = der[pos].toInt() and 0xFF
        val tagStart = pos
        pos++
        val len = readLen()
        if (tag == 0x30) {   // Found a SEQUENCE — this is subjectPublicKeyInfo
            return der.copyOfRange(tagStart, pos + len)
        }
        pos += len
    }
    throw IllegalArgumentException("SubjectPublicKeyInfo not found in certificate DER.")
}
