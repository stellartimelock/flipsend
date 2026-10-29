package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

class TransferResumeTest {
    @Test
    fun relayAckIntervalStaysInsideWebsocketLimits() {
        assertEquals(8 * 1024, ResumeAck.RELAY_SLICE_BYTES)
        assertTrue(ResumeAck.RELAY_SLICE_BYTES < 64 * 1024)
        assertEquals(0, ResumeAck.INTERVAL_BYTES % ResumeAck.RELAY_SLICE_BYTES)
        assertEquals(2 * 1024 * 1024, ResumeAck.WINDOW_BYTES)
        assertEquals(15, MessageType.ACK.code)
    }

    @Test
    fun aDroppedSendResumesFromTheLastAckedOffsetBothWays() {
        resumeAfterDrop(phoneSends = true)
        resumeAfterDrop(phoneSends = false)
    }

    private fun resumeAfterDrop(phoneSends: Boolean) {
        val phone = Identities.generate(name = "Phone")
        val desk = Identities.generate(name = "Desk")
        val sender = if (phoneSends) phone else desk
        val receiver = if (phoneSends) desk else phone
        val size = ResumeAck.INTERVAL_BYTES * 3 + 120_000
        val payload = ByteArray(size) { (it * 31).toByte() }
        val sha = sha256Hex(payload)
        val transferId = UUID.randomUUID().toString()
        val inbox = java.nio.file.Files.createTempDirectory("ss-resume-inbox").toFile()
        val dropAt = ResumeAck.INTERVAL_BYTES + ResumeAck.INTERVAL_BYTES / 2
        dropOnce(sender, receiver, payload, sha, transferId, inbox, dropAt)
        val partials = inbox.listFiles { _, name -> name.endsWith(".partial") } ?: emptyArray()
        assertTrue(partials.isNotEmpty(), "expected a partial after the drop")
        val partialLength = partials[0].length()
        assertTrue(partialLength >= ResumeAck.INTERVAL_BYTES, "partial was $partialLength")
        assertTrue(partialLength < size, "partial was $partialLength")
        val finished = completeOnce(sender, receiver, payload, sha, transferId, inbox)
        assertEquals(TransferState.SUCCEEDED, finished.state)
        assertArrayEquals(payload, File(finished.savedPath!!).readBytes())
        assertTrue(inbox.listFiles { _, name -> name.endsWith(".partial") }.isNullOrEmpty())
    }

    private fun dropOnce(
        sender: DeviceMaterial,
        receiver: DeviceMaterial,
        payload: ByteArray,
        sha: String,
        transferId: String,
        inbox: File,
        dropAt: Int
    ) {
        val server = TlsPins.server(receiver, sender.fingerprint, allowNew = false)
        val client = TlsPins.client(sender, receiver.fingerprint)
        val senderSsl = AtomicReference<SSLSocket?>()
        val dropped = AtomicBoolean(false)
        val receiveDone = CountDownLatch(1)
        ServerSocket(0).use { listen ->
            val thread = Thread {
                try {
                    val accepted = listen.accept()
                    val ssl = server.socketFactory.createSocket(accepted, null, accepted.port, true) as SSLSocket
                    TlsPins.configure(ssl, false)
                    ssl.startHandshake()
                    val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as X509Certificate).publicKey.encoded)
                    TransferSession.receive(
                        ssl.outputStream,
                        ssl.inputStream,
                        receiver,
                        sender.fingerprint,
                        sender.deviceId,
                        remoteFp,
                        allowNew = false,
                        confirmPeer = null,
                        confirmOffer = { _, _ -> true },
                        openFile = { FileNames.allocate(inbox, it) },
                        onProgress = { update ->
                            if (update.bytes >= dropAt && dropped.compareAndSet(false, true)) {
                                runCatching { senderSsl.get()?.close() }
                                runCatching { ssl.close() }
                            }
                        }
                    )
                } catch (_: Exception) {
                } finally {
                    receiveDone.countDown()
                }
            }
            thread.isDaemon = true
            thread.start()
            val raw = Socket()
            raw.connect(InetSocketAddress("127.0.0.1", listen.localPort), 5000)
            val ssl = client.socketFactory.createSocket(raw, "127.0.0.1", listen.localPort, true) as SSLSocket
            TlsPins.configure(ssl, true)
            ssl.startHandshake()
            senderSsl.set(ssl)
            val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as X509Certificate).publicKey.encoded)
            try {
                TransferSession.send(
                    ssl.outputStream,
                    ssl.inputStream,
                    sender,
                    receiver.deviceId,
                    receiver.fingerprint,
                    remoteFp,
                    "file",
                    "movie.bin",
                    "application/octet-stream",
                    payload.size.toLong(),
                    sha,
                    ByteArrayInputStream(payload),
                    transferId = transferId
                )
            } catch (_: Exception) {
            } finally {
                runCatching { ssl.close() }
            }
            assertTrue(receiveDone.await(20, TimeUnit.SECONDS))
            thread.join(5000)
        }
    }

    private fun completeOnce(
        sender: DeviceMaterial,
        receiver: DeviceMaterial,
        payload: ByteArray,
        sha: String,
        transferId: String,
        inbox: File
    ): TransferOutcome {
        val server = TlsPins.server(receiver, sender.fingerprint, allowNew = false)
        val client = TlsPins.client(sender, receiver.fingerprint)
        val outcome = AtomicReference<TransferOutcome>()
        val failure = AtomicReference<Throwable>()
        ServerSocket(0).use { listen ->
            val thread = Thread {
                try {
                    val accepted = listen.accept()
                    val ssl = server.socketFactory.createSocket(accepted, null, accepted.port, true) as SSLSocket
                    TlsPins.configure(ssl, false)
                    ssl.startHandshake()
                    val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as X509Certificate).publicKey.encoded)
                    outcome.set(
                        TransferSession.receive(
                            ssl.outputStream,
                            ssl.inputStream,
                            receiver,
                            sender.fingerprint,
                            sender.deviceId,
                            remoteFp,
                            allowNew = false,
                            confirmPeer = null,
                            confirmOffer = { _, _ -> true },
                            openFile = { FileNames.allocate(inbox, it) }
                        )
                    )
                    ssl.close()
                } catch (ex: Throwable) {
                    failure.set(ex)
                }
            }
            thread.isDaemon = true
            thread.start()
            val raw = Socket()
            raw.connect(InetSocketAddress("127.0.0.1", listen.localPort), 5000)
            val ssl = client.socketFactory.createSocket(raw, "127.0.0.1", listen.localPort, true) as SSLSocket
            TlsPins.configure(ssl, true)
            ssl.startHandshake()
            val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as X509Certificate).publicKey.encoded)
            val sent = TransferSession.send(
                ssl.outputStream,
                ssl.inputStream,
                sender,
                receiver.deviceId,
                receiver.fingerprint,
                remoteFp,
                "file",
                "movie.bin",
                "application/octet-stream",
                payload.size.toLong(),
                sha,
                ByteArrayInputStream(payload),
                transferId = transferId
            )
            ssl.close()
            thread.join(20000)
            if (failure.get() != null && outcome.get() == null) throw failure.get()!!
            assertEquals(TransferState.SUCCEEDED, sent.state)
            assertTrue(sent.chunks < payload.size / ProtocolLimits.MAX_CHUNK_DATA)
            return outcome.get()
        }
    }
}
