package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class CopyToPcTest {
    @Test
    fun aLoginSendsUsernameThenPasswordAndIgnoresTitle() {
        val request = CopyToPc.request(
            username = "alex",
            password = "hunter2",
            title = "Bank",
            kind = CopyToPc.KIND_LOGIN,
            source = CopyToPc.SOURCE,
            clearAfterSeconds = 30
        )
        assertEquals("alex\nhunter2", request.clipboardText)
        assertEquals(30, request.clearAfterSeconds)
        assertEquals("Bank", request.title)
        assertNull(CopyToPc.pair(null, null))
        assertEquals("only-pass", CopyToPc.pair("", "only-pass"))
    }

    @Test
    fun aCardCopiesTheUsernamePasswordPairAndKeepsCardExtrasOptional() {
        val request = CopyToPc.request(
            username = "4111111111111111",
            password = "123",
            kind = CopyToPc.KIND_CARD,
            source = CopyToPc.SOURCE,
            holder = "ALEX S",
            number = "4111111111111111",
            expMonth = "12",
            expYear = "29",
            cvv = "123"
        )
        assertEquals("4111111111111111\n123", request.clipboardText)
        assertEquals(CopyToPc.KIND_CARD, request.kind)
        assertEquals("ALEX S", request.holder)
    }

    @Test
    fun clearAfterSecondsIsClampedAndDefaultsToFortyFive() {
        assertEquals(45, CopyToPc.clampClearAfter(null))
        assertEquals(30, CopyToPc.clampClearAfter(30))
        assertEquals(5, CopyToPc.clampClearAfter(1))
        assertEquals(300, CopyToPc.clampClearAfter(400))
        assertEquals(30, CopyToPc.clampClearAfter(null, "30"))
        assertEquals(45, CopyToPc.clampClearAfter(null, "nope"))
    }

    @Test
    fun onlyFortressOrTheStellarVaultSourceIsAllowed() {
        assertTrue(CopyToPc.allow(CopyToPc.FORTRESS_PACKAGE, CopyToPc.SOURCE))
        assertTrue(CopyToPc.allow(CopyToPc.FORTRESS_PACKAGE, null))
        assertTrue(CopyToPc.allow(null, CopyToPc.SOURCE))
        assertFalse(CopyToPc.allow(null, null))
        assertFalse(CopyToPc.allow(null, "other"))
        assertFalse(CopyToPc.allow("com.other.app", CopyToPc.SOURCE))
        assertFalse(CopyToPc.allow("org.stellartimelock.stellarsend", CopyToPc.SOURCE))
    }

    @Test
    fun aMissingPcUsesPlainEnglishAndNeverEchoesTheSecret() {
        val secret = "alex\nhunter2"
        val disconnected = CopyToPc.outcomeFrom(false, ErrorCodes.UNKNOWN_PEER, "Pair a device before sending.", 30)
        assertFalse(disconnected.ok)
        assertEquals(CopyToPc.PC_NOT_CONNECTED, disconnected.message)
        assertFalse(disconnected.message.contains(secret))

        val io = CopyToPc.outcomeFrom(false, ErrorCodes.IO, secret, 30)
        assertEquals(CopyToPc.PC_NOT_CONNECTED, io.message)
        assertFalse(io.message.contains("hunter2"))

        val copied = CopyToPc.outcomeFrom(true, "ok", secret, 30)
        assertTrue(copied.ok)
        assertEquals("Copied to the PC clipboard. It will clear in 30 seconds.", copied.message)
        assertFalse(copied.message.contains("hunter2"))
    }

    @Test
    fun actionAndPackageMatchFortress() {
        assertEquals("org.stellartimelock.stellarsend.action.COPY_TO_PC", CopyToPc.ACTION)
        assertEquals("org.stellartimelock.stellarsend.action.SEND_FILE_TO_PC", CopyToPc.ACTION_SEND_FILE)
        assertEquals("org.stellartimelock.stellarsend", CopyToPc.FLIP_SEND_PACKAGE)
        assertEquals("com.stellartimelock.stellarvault", CopyToPc.FORTRESS_PACKAGE)
        assertEquals("stellar-vault", CopyToPc.SOURCE)
        assertEquals("file", CopyToPc.KIND_FILE)
    }

    @Test
    fun aFileKindDoesNotCopyClipboardTextAndKeepsTheUriOffTheLog() {
        val payload = """{"v":1,"ct":"SECRET_PAYLOAD_XYZ"}"""
        val request = CopyToPc.request(
            username = null,
            password = payload,
            kind = CopyToPc.KIND_FILE,
            source = CopyToPc.SOURCE,
            uri = "content://org.stellartimelock.stellarsend.stellarsend.files/handoff/fortress-bot-response.json",
            filename = FortressBotHandoff.RESPONSE_NAME,
            content = payload
        )
        assertTrue(request.fileKind)
        assertNull(request.clipboardText)
        assertEquals(payload, request.content)
        val sent = CopyToPc.fileOutcomeFrom(true, "ok", payload)
        assertTrue(sent.ok)
        assertEquals(FortressBotHandoff.SENT, sent.message)
        assertFalse(sent.message.contains("SECRET_PAYLOAD_XYZ"))
        val refused = CopyToPc.fileOutcomeFrom(false, ErrorCodes.UNKNOWN_PEER, payload)
        assertEquals(CopyToPc.PC_NOT_CONNECTED, refused.message)
        assertFalse(refused.message.contains("SECRET_PAYLOAD_XYZ"))
    }

    @Test
    fun clipboardHonorsAThirtySecondClear() {
        val clip = object : SecretClipboard {
            var value: String? = null
            override fun setText(text: String) { value = text }
            override fun getText(): String? = value
        }
        val handoff = ClipboardHandoff(clip, object : UserNotice {
            override fun show(text: String) {}
        })
        val secret = "alex\nhunter2"
        val placed = handoff.put(UUID.randomUUID().toString(), secret, 0, 30)
        assertEquals("Copied to the PC clipboard. It will clear in 30 seconds.", placed.message)
        assertFalse(placed.message.contains("hunter2"))
        assertEquals(secret, clip.value)
        assertFalse(handoff.tick(29_000))
        assertEquals(secret, clip.value)
        assertTrue(handoff.tick(30_000))
        assertEquals("", clip.value)
    }
}
