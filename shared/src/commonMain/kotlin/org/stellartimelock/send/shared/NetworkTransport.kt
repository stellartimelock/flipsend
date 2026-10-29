package org.stellartimelock.send.shared

import kotlinx.coroutines.flow.Flow

/**
 * Platform-specific network transport for peer-to-peer transfers.
 *
 * Android: wraps java.net.Socket + SSLSocket (existing Engine.kt approach).
 * iOS: wraps Network.framework NWConnection with TLS.
 *
 * Rule: try direct LAN first; fall back to end-to-end encrypted relay
 * over outbound HTTPS port 443. Never require VPN to be off.
 */

/** A bidirectional byte-stream connection. */
interface PeerConnection {
    /** Write all bytes and flush. */
    suspend fun write(data: ByteArray)
    /** Read exactly [count] bytes or throw on EOF/error. */
    suspend fun readExact(count: Int): ByteArray
    fun close()
}

/** Outcome of a mDNS/UDP discovery scan. */
data class DiscoveredPeer(
    val host: String,
    val port: Int,
    val name: String,
    val deviceId: String
)

/** Platform-specific factory. Get the singleton via [networkTransport]. */
expect class NetworkTransportFactory() {
    /** Connect to [host]:[port] with mutual TLS, validating [expectedFingerprint]. */
    suspend fun connect(
        host: String,
        port: Int,
        local: DeviceIdentity,
        expectedFingerprint: String
    ): PeerConnection

    /** Listen for incoming connections on [port]. Returns one connection. */
    suspend fun acceptOne(
        port: Int,
        local: DeviceIdentity,
        allowNewFingerprint: Boolean
    ): PeerConnection

    /**
     * Send a UDP discovery beacon and return a flow of discovered peers.
     * Completes when [timeoutMs] elapses.
     */
    fun discover(timeoutMs: Long): Flow<DiscoveredPeer>

    /**
     * Broadcast our own presence via UDP.
     * Call while listening to allow incoming connections from unpaired peers.
     */
    suspend fun announce(local: DeviceIdentity, port: Int, pairingCode: String?)
}

/** Read a complete frame using the protocol header. */
suspend fun PeerConnection.readFrame(): Frame {
    val header = readExact(ProtocolLimits.HEADER_SIZE)
    val (type, flags, length) = FrameCodec.parseHeader(header)
    val payload = if (length == 0) ByteArray(0) else readExact(length)
    return Frame(ProtocolLimits.VERSION, type, flags, payload)
}

/** Encode and write a frame. */
suspend fun PeerConnection.writeFrame(type: MessageType, payload: ByteArray) {
    write(FrameCodec.encode(type, payload))
}

suspend fun PeerConnection.writeFrameJson(type: MessageType, json: String) {
    writeFrame(type, json.encodeToByteArray())
}
