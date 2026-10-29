package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PeerTrustTest {
    @Test
    fun samePhoneKeepsTheStoredPinAndMergesHosts() {
        val stored = PeerTrust.Snapshot(
            deviceId = "11111111-1111-1111-1111-111111111111",
            name = "Phone",
            fingerprint = "aa".repeat(32),
            publicKey = "old-key",
            host = "192.168.1.20",
            port = 53321,
            pairedAtMillis = 1_000,
            hosts = listOf("192.168.1.20")
        )
        val incoming = stored.copy(
            name = "Alex's phone",
            fingerprint = "bb".repeat(32),
            publicKey = "new-key",
            host = "10.0.0.8",
            port = 53321,
            pairedAtMillis = 9_000,
            hosts = listOf("10.0.0.8")
        )
        val merged = PeerTrust.merge(stored, incoming)
        assertEquals(stored.fingerprint, merged.fingerprint)
        assertEquals(stored.publicKey, merged.publicKey)
        assertEquals(stored.pairedAtMillis, merged.pairedAtMillis)
        assertEquals("Alex's phone", merged.name)
        assertEquals("10.0.0.8", merged.host)
        assertEquals(setOf("192.168.1.20", "10.0.0.8"), merged.hosts.toSet())
        assertTrue(PeerTrust.shouldWrite(stored, merged))
        assertFalse(PeerTrust.shouldWrite(stored, stored))
    }

    @Test
    fun aDifferentPhoneReplacesTrust() {
        val stored = PeerTrust.Snapshot(
            deviceId = "11111111-1111-1111-1111-111111111111",
            name = "Phone",
            fingerprint = "aa".repeat(32),
            publicKey = "old-key",
            host = "192.168.1.20",
            port = 53321,
            pairedAtMillis = 1_000
        )
        val other = stored.copy(deviceId = "22222222-2222-2222-2222-222222222222", fingerprint = "cc".repeat(32))
        val merged = PeerTrust.merge(stored, other)
        assertEquals(other.deviceId, merged.deviceId)
        assertEquals(other.fingerprint, merged.fingerprint)
        assertTrue(PeerTrust.shouldWrite(stored, merged))
    }

    @Test
    fun firstPairWritesTheIncomingPeer() {
        val incoming = PeerTrust.Snapshot(
            deviceId = "11111111-1111-1111-1111-111111111111",
            name = "Phone",
            fingerprint = "aa".repeat(32),
            publicKey = "key",
            host = "192.168.1.20",
            port = 53321,
            pairedAtMillis = 1_000
        )
        assertEquals(incoming, PeerTrust.merge(null, incoming))
        assertTrue(PeerTrust.shouldWrite(null, incoming))
        assertTrue(PeerTrust.sameDevice(incoming.deviceId, incoming.deviceId.uppercase()))
        assertFalse(PeerTrust.sameDevice(null, incoming.deviceId))
    }

    @Test
    fun aFreshScanWritesBothPinsForTheSamePhone() {
        val stored = PeerTrust.Snapshot(
            deviceId = "11111111-1111-1111-1111-111111111111",
            name = "Phone",
            fingerprint = "aa".repeat(32),
            publicKey = "old-key",
            host = "192.168.1.20",
            port = 53321,
            pairedAtMillis = 1_000,
            hosts = listOf("192.168.1.20")
        )
        val incoming = stored.copy(
            fingerprint = "bb".repeat(32),
            publicKey = "new-key",
            host = "10.0.0.8",
            pairedAtMillis = 9_000,
            hosts = listOf("10.0.0.8")
        )
        val scanned = PeerTrust.scan(stored, incoming)
        assertEquals("bb".repeat(32), scanned.fingerprint)
        assertEquals("new-key", scanned.publicKey)
        assertEquals(9_000, scanned.pairedAtMillis)
        assertEquals("10.0.0.8", scanned.host)
        assertEquals(setOf("192.168.1.20", "10.0.0.8"), scanned.hosts.toSet())
        val merged = PeerTrust.merge(stored, incoming)
        assertEquals(stored.fingerprint, merged.fingerprint)
        assertTrue(PeerTrust.shouldWrite(stored, scanned))
    }

    @Test
    fun trustNewKeyRepinsTheAlreadyPairedComputer() {
        val stored = PeerTrust.Snapshot(
            deviceId = "11111111-1111-1111-1111-111111111111",
            name = "Windows PC",
            fingerprint = "aa".repeat(32),
            publicKey = "old-key",
            host = "192.168.1.20",
            port = 53321,
            pairedAtMillis = 1_000
        )
        val pinned = PeerTrust.repin(stored, "bb".repeat(32), "new-key")
        assertEquals("bb".repeat(32), pinned.fingerprint)
        assertEquals("new-key", pinned.publicKey)
        assertEquals(stored.deviceId, pinned.deviceId)
        assertEquals("Windows PC", pinned.name)
        val merged = PeerTrust.merge(stored, stored.copy(fingerprint = "cc".repeat(32), publicKey = "other"))
        assertEquals(stored.fingerprint, merged.fingerprint)
        val hosted = PeerTrust.withHost(pinned, "10.0.0.8", 53321)
        assertEquals(pinned.fingerprint, hosted.fingerprint)
        assertEquals(pinned.publicKey, hosted.publicKey)
        assertEquals("10.0.0.8", hosted.host)
        assertTrue(PeerTrust.shouldWrite(stored, hosted))
    }

    @Test
    fun forgetThenScanRewritesBothPins() {
        val stored = PeerTrust.Snapshot(
            deviceId = "11111111-1111-1111-1111-111111111111",
            name = "Windows PC",
            fingerprint = "aa".repeat(32),
            publicKey = "old-key",
            host = "192.168.1.20",
            port = 53321,
            pairedAtMillis = 1_000
        )
        val incoming = stored.copy(
            fingerprint = "bb".repeat(32),
            publicKey = "new-key",
            host = "10.0.0.8",
            pairedAtMillis = 9_000
        )
        val afterForget = PeerTrust.scan(null, incoming)
        assertEquals("bb".repeat(32), afterForget.fingerprint)
        assertEquals("new-key", afterForget.publicKey)
        assertEquals(incoming.deviceId, afterForget.deviceId)
        val scannedWhilePaired = PeerTrust.scan(stored, incoming)
        assertEquals("bb".repeat(32), scannedWhilePaired.fingerprint)
        assertEquals("new-key", scannedWhilePaired.publicKey)
        val merged = PeerTrust.merge(stored, incoming)
        assertEquals(stored.fingerprint, merged.fingerprint)
    }

    @Test
    fun shortPinShowsTheFirstEightHexChars() {
        assertEquals("aabbccdd", TrustNewKey.shortPin("AABBCCDDEEFF0011"))
        assertEquals("(empty)", TrustNewKey.shortPin(""))
        assertEquals("(empty)", TrustNewKey.shortPin(null))
    }
}
