package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class PairingCommitTest {
    @Test
    fun bothFilesAreReplacedByTempRename() {
        val directory = Files.createTempDirectory("pair-commit").toFile()
        val identity = File(directory, "identity.bin")
        val peer = File(directory, "peer.bin")
        identity.writeBytes(byteArrayOf(1, 2, 3))
        peer.writeBytes(byteArrayOf(4, 5, 6))
        PairingCommit.commit(identity, byteArrayOf(7, 7, 7, 7), peer, byteArrayOf(8, 8))
        assertArrayEquals(byteArrayOf(7, 7, 7, 7), identity.readBytes())
        assertArrayEquals(byteArrayOf(8, 8), peer.readBytes())
        assertFalse(File(directory, "identity.bin.pairing.tmp").exists())
        assertFalse(File(directory, "peer.bin.pairing.tmp").exists())
        assertFalse(File(directory, "identity.bin.pairing.old").exists())
    }

    @Test
    fun aFailedPeerReplaceRestoresThePreviousIdentity() {
        val directory = Files.createTempDirectory("pair-rollback").toFile()
        val identity = File(directory, "identity.bin")
        val original = byteArrayOf(1, 2, 3, 4)
        identity.writeBytes(original)
        val peer = File(directory, "peer.bin")
        peer.mkdirs()
        var failed = false
        try {
            PairingCommit.commit(identity, byteArrayOf(9, 9, 9, 9), peer, byteArrayOf(8, 8, 8, 8))
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
        assertArrayEquals(original, identity.readBytes())
        assertFalse(File(directory, "identity.bin.pairing.tmp").exists())
        assertFalse(File(directory, "peer.bin.pairing.tmp").exists())
    }

    @Test
    fun thePhoneRecognizesAComputerThatNoLongerAcceptsIt() {
        assertTrue(RepairPrompt.unrecognized(ErrorCodes.UNKNOWN_PEER, "This device is not paired. Pair again from the QR code."))
        assertFalse(RepairPrompt.unrecognized(ErrorCodes.KEY_CHANGED, "The paired device key changed. Unpair both sides and pair again."))
        assertFalse(RepairPrompt.unrecognized(null, "The paired device key changed."))
        assertTrue(TrustNewKey.applies(true, ErrorCodes.KEY_CHANGED, "The paired device key changed."))
        assertFalse(TrustNewKey.applies(false, ErrorCodes.KEY_CHANGED, "The paired device key changed."))
        assertEquals("Windows PC has a new key. This is the computer you already paired. Trust the new key to keep using it.", TrustNewKey.message("Windows PC"))
        assertTrue(TrustNewKey.handshakeFailed("The phone answered on Wi-Fi, but the secure handshake did not finish. IOException: The secure connection closed"))
        assertTrue(TrustNewKey.handshakeFailed("Disconnected. The other device hung up. SSLHandshakeException: Handshake failed"))
        assertTrue(TrustNewKey.blocksConnected("The secure connection closed"))
        assertTrue(TrustNewKey.looksLikeMismatch(KeyChangedException("bb".repeat(32))))
        assertTrue(TrustNewKey.looksLikeMismatch(javax.net.ssl.SSLHandshakeException("other device hung up")))
        assertFalse(TrustNewKey.looksLikeMismatch(IOException("Could not find the paired PC.")))
        assertTrue(TrustNewKey.applies(true, ErrorCodes.IO, "The secure connection closed"))
        assertFalse(RepairPrompt.unrecognized(ErrorCodes.IO, "Read error: SSLV3_ALERT_CERTIFICATE_UNKNOWN OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN"))
        assertTrue(RepairPrompt.looksLikeCertificateUnknown("sslv3_alert_certificate_unknown (tls_record.cc:489)"))
        assertFalse(RepairPrompt.unrecognized(ErrorCodes.UNKNOWN_PEER, "Pair a device before sending."))
        assertFalse(RepairPrompt.unrecognized(ErrorCodes.IO, "Could not reach the PC."))
        val wrapped = IOException("handshake", ProtocolException("This device is not paired. Pair again from the QR code.", ErrorCodes.UNKNOWN_PEER))
        assertEquals(ErrorCodes.UNKNOWN_PEER, RepairPrompt.codeOf(wrapped))
        val changed = IOException("tls", java.security.cert.CertificateException("The paired device key changed."))
        assertEquals(ErrorCodes.KEY_CHANGED, RepairPrompt.codeOf(changed))
        val ssl = IOException("Read error: SSLV3_ALERT_CERTIFICATE_UNKNOWN")
        assertNull(RepairPrompt.codeOf(ssl))
    }

    @Test
    fun trustNeverPromptsWhenThePresentedPinMatchesOrIsMissing() {
        val stored = "24c159d8" + "ab".repeat(28)
        val presented = stored
        assertFalse(TrustNewKey.shouldPrompt(true, ErrorCodes.KEY_CHANGED, "The paired device key changed.", "none", stored))
        assertFalse(TrustNewKey.shouldPrompt(true, ErrorCodes.KEY_CHANGED, "The paired device key changed.", "", stored))
        assertFalse(TrustNewKey.shouldPrompt(true, ErrorCodes.KEY_CHANGED, "The paired device key changed.", null, stored))
        assertFalse(TrustNewKey.shouldPrompt(true, ErrorCodes.KEY_CHANGED, "The paired device key changed.", presented, stored))
        assertFalse(TrustNewKey.shouldRaiseKeyChanged("", stored))
        assertFalse(TrustNewKey.shouldRaiseKeyChanged("none", stored))
        assertFalse(TrustNewKey.shouldRaiseKeyChanged(presented, stored))
        val other = "a".repeat(64)
        assertTrue(TrustNewKey.shouldPrompt(true, ErrorCodes.KEY_CHANGED, "The paired device key changed.", other, stored))
        assertTrue(TrustNewKey.shouldRaiseKeyChanged(other, stored))
    }
}
