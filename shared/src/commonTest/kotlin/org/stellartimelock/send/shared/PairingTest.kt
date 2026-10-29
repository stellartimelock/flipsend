package org.stellartimelock.send.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingTest {

    @Test
    fun qrRoundTrip() {
        val fp = sha256Hex("fake-spki".encodeToByteArray())
        val advert = PairingAdvert(
            version = 1,
            deviceId = "12345678-1234-1234-1234-123456789abc",
            publicKeySpkiBase64Url = base64UrlEncode("fake-spki".encodeToByteArray()),
            fingerprint = fp,
            host = "192.168.1.1",
            port = 53321,
            name = "My PC",
            hosts = listOf("192.168.1.1")
        )
        val qr = PairingCodec.toQr(advert)
        assertTrue(qr.startsWith(ProtocolLimits.QR_PREFIX))
        val parsed = PairingCodec.parse(qr)
        assertEquals(advert.deviceId, parsed.deviceId)
        assertEquals(advert.fingerprint, parsed.fingerprint)
        assertEquals(53321, parsed.port)
        assertEquals("My PC", parsed.name)
    }

    @Test
    fun rejectsWrongPrefix() {
        assertFailsWith<ProtocolException> {
            PairingCodec.parse("https://example.com/pair?v=1")
        }
    }

    @Test
    fun localAddressPolicyTests() {
        assertTrue(LocalAddressPolicy.isAllowedHost("192.168.1.100", allowLoopback = false))
        assertTrue(LocalAddressPolicy.isAllowedHost("10.0.0.1", allowLoopback = false))
        assertTrue(LocalAddressPolicy.isAllowedHost("172.16.0.1", allowLoopback = false))
        assertFalse(LocalAddressPolicy.isAllowedHost("127.0.0.1", allowLoopback = false))
        assertTrue(LocalAddressPolicy.isAllowedHost("127.0.0.1", allowLoopback = true))
        assertFalse(LocalAddressPolicy.isAllowedHost("8.8.8.8", allowLoopback = false))
        assertFalse(LocalAddressPolicy.isAllowedHost("224.0.0.1", allowLoopback = false))
    }

    @Test
    fun shortCodeFormat() {
        val fp = "abcd1234" + "0".repeat(56)
        assertEquals("ABCD-1234", ShortCode.format(fp))
        assertTrue(ShortCode.matches("ABCD-1234", fp))
        assertFalse(ShortCode.matches("XXXX-0000", fp))
    }
}
