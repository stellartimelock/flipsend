package org.stellartimelock.send.core

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DirectLinkTest {
    @Test
    fun punchOpensADirectTlsSocketWithThePairedCertificates() {
        val phone = Identities.generate(name = "phone")
        val pc = Identities.generate(name = "pc")
        val left = DirectAttempt.start("127.0.0.1:9", listOf("127.0.0.1"))
        val right = DirectAttempt.start("127.0.0.1:9", listOf("127.0.0.1"))
        val helloPhone = JSONObject().put("deviceId", phone.deviceId).put("fingerprint", phone.fingerprint).put("direct", left.offer)
        val helloPc = JSONObject().put("deviceId", pc.deviceId).put("fingerprint", pc.fingerprint).put("direct", right.offer)
        val gate = CountDownLatch(2)
        val opened = arrayOfNulls<javax.net.ssl.SSLSocket>(2)
        val errors = AtomicReference<Throwable>()
        Thread {
            try {
                opened[0] = left.open(phone, helloPc, 4000)
            } catch (ex: Throwable) {
                errors.compareAndSet(null, ex)
            } finally {
                gate.countDown()
            }
        }.start()
        Thread {
            try {
                opened[1] = right.open(pc, helloPhone, 4000)
            } catch (ex: Throwable) {
                errors.compareAndSet(null, ex)
            } finally {
                gate.countDown()
            }
        }.start()
        assertTrue(gate.await(8, TimeUnit.SECONDS))
        assertNull(errors.get()?.message)
        assertNotNull(opened[0])
        assertNotNull(opened[1])
        opened[0]!!.outputStream.write(7)
        opened[0]!!.outputStream.flush()
        assertEquals(7, opened[1]!!.inputStream.read())
        left.close()
        right.close()
        opened[0]?.close()
        opened[1]?.close()
    }

    @Test
    fun punchGivesUpSoTheRelayCanCarryTheBytes() {
        val dead = ServerSocket(0)
        val port = dead.localPort
        dead.close()
        val attempt = DirectAttempt.start(null, emptyList())
        val remote = JSONObject()
            .put("deviceId", UUID.randomUUID().toString())
            .put("fingerprint", Identities.generate().fingerprint)
            .put("direct", JSONObject().put("token", UUID.randomUUID().toString()).put("udp", port).put("tcp", port).put("seen", "127.0.0.1:$port"))
        val started = System.currentTimeMillis()
        val opened = attempt.open(Identities.generate(), remote, 900)
        assertNull(opened)
        assertTrue(System.currentTimeMillis() - started < 5000)
        attempt.close()
        val reply = RelayJoin.parseReply("""{"ok":true,"seen":"203.0.113.9:41000"}""")
        assertEquals("203.0.113.9:41000", reply.seen)
        assertEquals(Reachability.RELAY, DirectLink.choose(punched = false, wifiDirectUp = false))
        assertEquals(Reachability.PUNCHED, DirectLink.choose(punched = true, wifiDirectUp = false))
    }

    @Test
    fun directRouteShowsWhenThatPathIsUp() {
        val face = ConnectionState.face(
            ConnectionInput(
                paired = true,
                listening = true,
                pairingWindowOpen = false,
                onLan = false,
                lanUp = false,
                directUp = true,
                directAvailable = true,
                relayUp = false,
                relayAvailable = true,
                usbUp = false,
                usbAvailable = false
            )
        )
        assertEquals(HomeStatus.CONNECTED, face.headline)
        assertEquals(ConnectionState.DIRECT, face.pathPill)
        assertTrue(face.routesLine.contains("Direct now"))
        assertTrue(face.agrees())
    }
}
