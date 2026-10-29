package org.stellartimelock.send.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayTest {

    @Test
    fun pairedRoomIsDeterministicAndSymmetric() {
        val a = "aaaa"
        val b = "bbbb"
        assertEquals(RelayRoom.paired(a, b), RelayRoom.paired(b, a))
        assertTrue(RelayRoom.isToken(RelayRoom.paired(a, b)))
    }

    @Test
    fun botHubRoomIsPairingKeyed() {
        val a = "11111111-1111-1111-1111-111111111111"
        val b = "22222222-2222-2222-2222-222222222222"
        assertEquals(RelayRoom.botHub(a, b), RelayRoom.botHub(b, a))
        assertEquals("334a25bf94da6b3aafe7eeba7e60156426598d8f7bf57a9a0dde0101244fa90a", RelayRoom.botHub(a, b))
        assertTrue(RelayRoom.isToken(RelayRoom.botHub(a, b)))
    }

    @Test
    fun pairingRoomIsReproducible() {
        val id = "test-device-id"
        assertEquals(RelayRoom.pairing(id), RelayRoom.pairing(id))
        assertTrue(RelayRoom.isToken(RelayRoom.pairing(id)))
    }

    @Test
    fun relayAddressParseWss() {
        val ep = RelayAddress.parse("wss://relay.example.com/v1")
        assertNotNull(ep)
        assertEquals("relay.example.com", ep!!.host)
        assertEquals(443, ep.port)
        assertEquals("/v1", ep.path)
        assertTrue(ep.secure)
    }

    @Test
    fun relayAddressParseHttpsCustomPort() {
        val ep = RelayAddress.parse("https://relay.example.com:8443/v2")
        assertNotNull(ep)
        assertEquals(8443, ep!!.port)
        assertEquals("/v2", ep.path)
    }

    @Test
    fun relayAddressRejectsPlainHttp() {
        assertNull(RelayAddress.parse("http://public.example.com/v1"))
    }

    @Test
    fun relayAddressRejectsInvalid() {
        assertNull(RelayAddress.parse(null))
        assertNull(RelayAddress.parse(""))
        assertNull(RelayAddress.parse("ftp://example.com"))
    }

    @Test
    fun relayJoinWrite() {
        val room = "a".repeat(64)
        val msg = RelayJoin.write(room, "listen")
        assertTrue(msg.contains("\"room\""))
        assertTrue(msg.contains("\"listen\""))
        assertTrue(msg.contains("\"v\":1"))
    }

    @Test
    fun relayJoinParseReply() {
        val reply = RelayJoin.parseReply("{\"ok\":true,\"tier\":\"free\",\"used\":1024}")
        assertTrue(reply.ok)
        assertEquals("free", reply.tier)
        assertEquals(1024L, reply.used)
        assertFalse(reply.quota)
    }

    @Test
    fun relayJoinParseQuotaReply() {
        val reply = RelayJoin.parseReply("{\"ok\":false,\"quota\":true,\"message\":\"Limit reached.\"}")
        assertFalse(reply.ok)
        assertTrue(reply.quota)
        assertEquals("Limit reached.", reply.message)
    }
}
