package org.stellartimelock.send.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolTest {

    @Test
    fun remoteInputIsTypeFourteen() {
        val encoded = FrameCodec.encode(MessageType.REMOTE_INPUT, "{}".encodeToByteArray())
        assertEquals(14, encoded[5].toInt())
        assertEquals(14, MessageType.REMOTE_INPUT.code)
    }

    @Test
    fun frameRoundTrip() {
        val payload = "hello world".encodeToByteArray()
        val encoded = FrameCodec.encode(MessageType.HELLO, payload)
        assertEquals(ProtocolLimits.HEADER_SIZE + payload.size, encoded.size)
        val (type, flags, length) = FrameCodec.parseHeader(encoded.copyOf(ProtocolLimits.HEADER_SIZE))
        assertEquals(MessageType.HELLO, type)
        assertEquals(0, flags)
        assertEquals(payload.size, length)
    }

    @Test
    fun chunkRoundTrip() {
        val id = "12345678-1234-1234-1234-123456789abc"
        val data = ByteArray(32) { it.toByte() }
        val encoded = ChunkCodec.encode(id, 1024L, data)
        val decoded = ChunkCodec.decode(encoded)
        assertEquals(id, decoded.transferId)
        assertEquals(1024L, decoded.offset)
        assertTrue(data.contentEquals(decoded.data))
    }

    @Test
    fun pinsEqualTest() {
        val fp = "a".repeat(64)
        assertTrue(pinsEqual(fp, fp))
        assertFalse(pinsEqual(fp, "b".repeat(64)))
        assertFalse(pinsEqual(null, fp))
    }

    @Test
    fun fingerprintCheck() {
        assertTrue(isFingerprint("0123456789abcdef".repeat(4)))
        assertFalse(isFingerprint("0123456789ABCDEF".repeat(4)))  // uppercase not allowed
        assertFalse(isFingerprint("short"))
    }

    @Test
    fun uuidRoundTrip() {
        val id = "12345678-abcd-ef01-2345-6789abcdef01"
        val bytes = uuidToBytes(id)
        assertEquals(16, bytes.size)
        val back = uuidFromBytes(bytes)
        assertEquals(id, back)
    }

    @Test
    fun trimDeviceNameTest() {
        assertEquals("Device", trimDeviceName(""))
        assertEquals("Device", trimDeviceName("   "))
        assertEquals("Test", trimDeviceName("Test"))
        assertEquals("A".repeat(64), trimDeviceName("A".repeat(100)))
    }
}
