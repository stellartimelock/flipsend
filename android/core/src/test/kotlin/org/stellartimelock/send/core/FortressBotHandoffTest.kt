package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class FortressBotHandoffTest {
    private val payload = """{"v":1,"ct":"SECRET_PAYLOAD_XYZ","item":"bank"}"""

    @Test
    fun routesFortressBotJsonNamesAndLeavesOrdinaryFilesAlone() {
        assertTrue(FortressBotHandoff.isIncomingName("fortress-bot-request.json"))
        assertTrue(FortressBotHandoff.isIncomingName("fortress-bot-failed.json"))
        assertTrue(FortressBotHandoff.isIncomingName("fortress-bot-abc-123.json"))
        assertTrue(FortressBotHandoff.isIncomingName("Fortress-Bot-Save.JSON"))
        assertTrue(FortressBotHandoff.isIncomingName("fortress-bot-response.json"))
        assertTrue(FortressBotHandoff.isIncomingName("../fortress-bot-request.json"))
        assertFalse(FortressBotHandoff.isIncomingName("fortress-bot.json"))
        assertFalse(FortressBotHandoff.isIncomingName("fortress-bot-response.txt"))
        assertFalse(FortressBotHandoff.isIncomingName("notes.json"))
        assertFalse(FortressBotHandoff.isIncomingName("my-fortress-bot-x.json"))
        assertFalse(FortressBotHandoff.isIncomingName(null))
        assertFalse(FortressBotHandoff.isIncomingName(""))
        assertTrue(FortressBotHandoff.isResponseName("fortress-bot-response.json"))
        assertTrue(FortressBotHandoff.isResponseName("Fortress-Bot-Response.JSON"))
        assertFalse(FortressBotHandoff.isResponseName("fortress-bot-request.json"))
        assertTrue(FortressBotHandoff.handOffToFortress("fortress-bot-request.json"))
        assertTrue(FortressBotHandoff.handOffToFortress("fortress-bot-failed.json"))
        assertFalse(FortressBotHandoff.handOffToFortress("fortress-bot-response.json"))
        assertFalse(FortressBotHandoff.handOffToFortress("notes.json"))
        assertEquals("fortress-bot-response.json", FortressBotHandoff.outgoingName())
        assertEquals("fortress-bot-response.json", FortressBotHandoff.outgoingName("fortress-bot-response.json"))
        assertEquals("fortress-bot-response.json", FortressBotHandoff.outgoingName("notes.json"))
        assertTrue(FortressBotHandoff.hideFromReceivedList("fortress-bot-request.json"))
        assertTrue(FortressBotHandoff.hideFromReceivedList("fortress-bot-response.json"))
        assertFalse(FortressBotHandoff.hideFromReceivedList("photo.jpg"))
    }

    @Test
    fun fileSendsRequireTheFortressPackageAndAVerifiedSigner() {
        assertTrue(FortressBotHandoff.allowFileCaller(CopyToPc.FORTRESS_PACKAGE, signed = true))
        assertFalse(FortressBotHandoff.allowFileCaller(CopyToPc.FORTRESS_PACKAGE, signed = false))
        assertFalse(FortressBotHandoff.allowFileCaller(null, signed = true))
        assertFalse(FortressBotHandoff.allowFileCaller(null, signed = false))
        assertFalse(FortressBotHandoff.allowFileCaller("com.other.app", signed = true))
        assertFalse(FortressBotHandoff.allowFileCaller(CopyToPc.FLIP_SEND_PACKAGE, signed = true))
        assertFalse(CopyToPc.allow("com.other.app", CopyToPc.SOURCE, sameSigner = true))
        assertTrue(FortressBotHandoff.knownFortressCert("fac61745dc0903786fb9ede62a962b399f7348f0"))
        assertTrue(FortressBotHandoff.knownFortressCert("7994727dAABBCC"))
        assertTrue(FortressBotHandoff.knownFortressCert("FA:C6:17:45:00"))
        assertFalse(FortressBotHandoff.knownFortressCert("68:23:54:03:aa"))
        assertFalse(FortressBotHandoff.knownFortressCert(null))
        assertTrue(FortressBotHandoff.isFileKind("file"))
        assertTrue(FortressBotHandoff.isFileKind("FILE"))
        assertFalse(FortressBotHandoff.isFileKind(CopyToPc.KIND_LOGIN))
        assertFalse(FortressBotHandoff.isFileKind(null))
    }

    @Test
    fun pairedDevicesAutoAcceptAndUnpairedPeersAreRejected() {
        assertEquals(true, FortressBotHandoff.acceptOffer("fortress-bot-request.json", paired = true))
        assertEquals(true, FortressBotHandoff.acceptOffer("fortress-bot-failed.json", paired = true))
        assertEquals(false, FortressBotHandoff.acceptOffer("fortress-bot-request.json", paired = false))
        assertEquals(false, FortressBotHandoff.acceptOffer("fortress-bot-failed.json", paired = false))
        assertNull(FortressBotHandoff.acceptOffer("notes.txt", paired = true))
        assertNull(FortressBotHandoff.acceptOffer("photo.jpg", paired = false))
    }

    @Test
    fun logLinesNameTheFileAndNeverRepeatItsContents() {
        val handed = FortressBotHandoff.logLine("Handed", "fortress-bot-request.json")
        val sent = FortressBotHandoff.logLine("Sent", FortressBotHandoff.RESPONSE_NAME)
        assertEquals("Handed fortress-bot-request.json", handed)
        assertEquals("Sent fortress-bot-response.json", sent)
        assertFalse(FortressBotHandoff.containsPayload(handed, payload))
        assertFalse(FortressBotHandoff.containsPayload(sent, payload))
        assertFalse(FortressBotHandoff.containsPayload(FortressBotHandoff.HANDED, payload))
        assertFalse(FortressBotHandoff.containsPayload(FortressBotHandoff.SENT, payload))
        assertFalse(FortressBotHandoff.containsPayload(FortressBotHandoff.NOTHING, payload))
        assertFalse(FortressBotHandoff.containsPayload(CopyToPc.NOT_FORTRESS, payload))
        assertFalse(FortressBotHandoff.logLine("incoming", "fortress-bot-request.json").contains("SECRET_PAYLOAD_XYZ"))
        assertEquals(FortressBotHandoff.ACTION_BOT_HANDOFF, "com.stellartimelock.stellarvault.action.BOT_HANDOFF")
        assertEquals(FortressBotHandoff.ACTION_SEND_FILE_TO_PC, "org.stellartimelock.stellarsend.action.SEND_FILE_TO_PC")
        assertEquals(FortressBotHandoff.EXTRA_STREAM, "android.intent.extra.STREAM")
        assertEquals(FortressBotHandoff.EXTRA_NAME, "name")
        assertEquals(FortressBotHandoff.EXTRA_CONTENT, "content")
        assertEquals(FortressBotHandoff.EXTRA_FILENAME, "filename")
        assertEquals(FortressBotHandoff.MIME_JSON, "application/json")
        assertEquals(FortressBotHandoff.REQUEST_NAME, "fortress-bot-request.json")
        assertEquals(FortressBotHandoff.FAILED_NAME, "fortress-bot-failed.json")
    }

    @Test
    fun inlineContentIsWrittenAsUtf8UnderTheResponseName() {
        val inbox = File.createTempFile("ss-inline", "").apply {
            delete()
            mkdirs()
        }
        try {
            val staged = FortressBotHandoff.stageOutgoing(inbox, "fortress-bot-response.json", payload)
            assertEquals("fortress-bot-response.json", staged.name)
            assertEquals(payload, staged.readText(Charsets.UTF_8))
            assertFalse(FortressBotHandoff.logLine("Sent", staged.name).contains("SECRET_PAYLOAD_XYZ"))
        } finally {
            inbox.deleteRecursively()
        }
    }

    @Test
    fun exactResponsePathOverwritesInsideTheInbox() {
        val inbox = File.createTempFile("ss-handoff", "").apply {
            delete()
            mkdirs()
        }
        try {
            val first = FileNames.exact(inbox, "fortress-bot-response.json")
            first.writeText("one")
            val again = FileNames.exact(inbox, "fortress-bot-response.json")
            assertEquals(first.canonicalPath, again.canonicalPath)
            assertEquals("fortress-bot-response.json", again.name)
            again.writeText("two")
            assertEquals("two", first.readText())
        } finally {
            inbox.deleteRecursively()
        }
    }
}
