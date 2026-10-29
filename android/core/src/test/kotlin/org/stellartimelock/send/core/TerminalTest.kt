package org.stellartimelock.send.core

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class TerminalTest {
    @Test
    fun destructiveCommandsAreTheNamedToolsOnly() {
        assertTrue(TerminalPolicy.isDestructive("del /q *.*"))
        assertTrue(TerminalPolicy.isDestructive("rm -rf /"))
        assertTrue(TerminalPolicy.isDestructive("format C:"))
        assertTrue(TerminalPolicy.isDestructive("shutdown /s /t 0"))
        assertTrue(TerminalPolicy.isDestructive("rmdir /s build"))
        assertTrue(TerminalPolicy.isDestructive("cmd /c del notes.txt"))
        assertFalse(TerminalPolicy.isDestructive("echo hello"))
        assertFalse(TerminalPolicy.isDestructive("dir"))
        assertFalse(TerminalPolicy.isDestructive("delta"))
        assertFalse(TerminalPolicy.isDestructive("formatted"))
    }

    @Test
    fun allowlistMatchesTheWholeCommand() {
        assertTrue(TerminalPolicy.isAllowlisted(" Echo Hi ", listOf("echo hi")))
        assertFalse(TerminalPolicy.isAllowlisted("echo hi /s", listOf("echo hi")))
    }

    @Test
    fun signatureUsesThePairingKey() {
        val material = Identities.generate()
        val id = UUID.randomUUID().toString()
        val command = "echo hi"
        val signature = TerminalAuth.sign(material.privateKey, id, command, false)
        val spki = material.certificate.publicKey.encoded
        assertTrue(TerminalAuth.verify(spki, id, command, false, signature))
        assertFalse(TerminalAuth.verify(spki, id, "echo no", false, signature))
        assertFalse(TerminalAuth.verify(spki, id, command, true, signature))
    }

    @Test
    fun terminalFrameIsTypeTwelve() {
        val frame = FrameCodec.encode(MessageType.TERMINAL_DATA, "{}".toByteArray())
        assertEquals(12, frame[5].toInt())
        assertEquals(3, FrameCodec.encode(MessageType.OFFER, "{}".toByteArray())[5].toInt())
    }

    @Test
    fun outputChunkParsesStreamText() {
        val payload = JSONObject()
            .put("id", "11111111-1111-1111-1111-111111111111")
            .put("seq", 1)
            .put("stream", "out")
            .put("text", "hi")
            .toString()
            .toByteArray()
        val chunk = TerminalCodec.parseChunk(payload)
        assertEquals("out", chunk.stream)
        assertEquals("hi", chunk.text)
    }

    @Test
    fun phoneSignatureVectorStillVerifies() {
        val json = JSONObject(File(repoRoot(), "testdata/terminal-signature-phone.json").readText())
        assertTrue(
            TerminalAuth.verify(
                base64UrlDecode(json.getString("spki")),
                json.getString("id"),
                json.getString("command"),
                json.getBoolean("confirmed"),
                json.getString("sig")
            )
        )
    }

    @Test
    fun windowsSignatureVerifiesWithThePairingPublicKey() {
        val file = File(repoRoot(), "testdata/terminal-signature.json")
        assertTrue(file.isFile)
        val json = JSONObject(file.readText())
        val spki = base64UrlDecode(json.getString("spki"))
        assertTrue(
            TerminalAuth.verify(
                spki,
                json.getString("id"),
                json.getString("command"),
                json.getBoolean("confirmed"),
                json.getString("sig")
            )
        )
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "testdata/pairing.json").isFile) return dir
            dir = dir.parentFile ?: error("testdata not found from ${System.getProperty("user.dir")}")
        }
    }
}
