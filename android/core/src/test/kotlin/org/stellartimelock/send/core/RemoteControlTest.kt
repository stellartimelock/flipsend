package org.stellartimelock.send.core

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class RemoteControlTest {
    @BeforeEach
    fun sideloadWords() {
        SideloadRemoteWords.install()
    }

    @Test
    fun remoteInputIsTypeFourteenAndUsesThePairedService() {
        val frame = FrameCodec.encode(MessageType.REMOTE_INPUT, "{}".toByteArray())
        assertEquals(14, frame[5].toInt())
        assertEquals(14, MessageType.REMOTE_INPUT.code)
        assertEquals("remote.control", CompanionServices.REMOTE_CONTROL)
        assertEquals("remote.control", RemoteControl.SERVICE)
        assertEquals("remote_control", RemoteControl.PREF_KEY)
        assertEquals(ScreenShare.MAX_WIDTH, RemoteControl.MAX_WIDTH)
        assertEquals("Remote desktop", RemoteControl.LABEL)
        assertEquals(RemoteDesktop.LABEL, RemoteControl.LABEL)
        val session = UUID.randomUUID().toString()
        val held = RemoteInputCodec.parse(JSONObject().put("kind", "longpress").put("session", session).put("x", 0.4).put("y", 0.6))
        assertEquals("longpress", held!!.kind)
        assertEquals("right", RemotePointer.button(held.kind))
    }

    @Test
    fun jpegScaleKeepsTheSameAspectAndMapsBackToTheDevice() {
        val (jw, jh) = RemoteCoords.jpegSize(1440, 3200)
        assertEquals(720, jw)
        assertEquals(1600, jh)
        val (x, y) = RemoteCoords.toDevice(0.25, 0.5, 1440, 3200)
        assertEquals(360, x)
        assertEquals(1600, y)
        val (dx, dy) = RemoteCoords.jpegToDevice(180, 800, 720, 1600, 1440, 3200)
        assertEquals(360, dx)
        assertEquals(1600, dy)
        val (fullW, fullH) = RemoteCoords.jpegSize(720, 1280)
        assertEquals(720, fullW)
        assertEquals(1280, fullH)
    }

    @Test
    fun viewClicksIgnoreLetterboxAndStayOnTheImage() {
        val hit = RemoteCoords.fromView(200.0, 50.0, 400.0, 200.0, 100.0, 200.0)
        assertNotNull(hit)
        assertEquals(0.5, hit!!.first, 0.001)
        assertEquals(0.25, hit.second, 0.001)
        assertNull(RemoteCoords.fromView(10.0, 10.0, 400.0, 200.0, 100.0, 200.0))
        assertNull(RemoteCoords.fromView(0.0, 0.0, 0.0, 400.0, 100.0, 200.0))
    }

    @Test
    fun inputJsonRejectsUnknownKindsAndOverlongText() {
        val session = UUID.randomUUID().toString()
        val tap = RemoteInputCodec.parse(JSONObject().put("kind", "tap").put("session", session).put("x", 0.2).put("y", 1.5))
        assertNotNull(tap)
        assertEquals("tap", tap!!.kind)
        assertEquals(0.2, tap.x, 0.0001)
        assertEquals(1.0, tap.y, 0.0001)
        assertNull(RemoteInputCodec.parse(JSONObject().put("kind", "explode").put("session", session)))
        assertNull(RemoteInputCodec.parse(JSONObject().put("kind", "tap").put("session", "nope")))
        assertNull(RemoteInputCodec.parse(JSONObject().put("kind", "text").put("session", session).put("text", "x".repeat(RemoteControl.MAX_TEXT_CHARS + 1))))
        val text = RemoteInputCodec.parse(JSONObject().put("kind", "text").put("session", session).put("text", "hi"))
        assertEquals("hi", text!!.text)
        val round = RemoteInputCodec.parse(RemoteInputCodec.encode(text))
        assertEquals("text", round!!.kind)
        assertEquals("hi", round.text)
    }

    @Test
    fun sessionNeedsPairToggleAllowThenStop() {
        val session = RemoteSession()
        val id = UUID.randomUUID().toString()
        val unpaired = session.request(id, RemoteControl.FROM_PC)
        assertFalse(unpaired.ok)
        assertEquals(ErrorCodes.UNKNOWN_PEER, unpaired.code)
        session.paired = true
        val off = session.request(id, RemoteControl.FROM_PC)
        assertFalse(off.ok)
        assertEquals(RemoteControl.NEED_TOGGLE, off.message)
        session.enabled = true
        val asked = session.request(id, RemoteControl.FROM_PC)
        assertTrue(asked.ok)
        assertEquals(RemoteSessionState.PENDING, session.state)
        assertEquals(RemoteControl.ASK_PHONE, asked.message)
        assertFalse(session.acceptInput(id))
        val allowed = session.allow(id)
        assertTrue(allowed.ok)
        assertEquals(RemoteSessionState.ACTIVE, session.state)
        assertTrue(session.acceptInput(id, 1_000))
        assertFalse(session.acceptInput(id, 1_010))
        assertTrue(session.acceptInput(id, 1_025))
        val other = UUID.randomUUID().toString()
        assertFalse(session.acceptInput(other, 1_050))
        val stopped = session.stop(id)
        assertTrue(stopped.ok)
        assertEquals(RemoteSessionState.IDLE, session.state)
        assertFalse(session.acceptInput(id, 2_000))
    }

    @Test
    fun denyAndBusySessionsDoNotOpenInput() {
        val session = RemoteSession()
        session.paired = true
        session.enabled = true
        val id = UUID.randomUUID().toString()
        session.request(id, RemoteControl.FROM_PHONE)
        val denied = session.deny(id)
        assertFalse(denied.ok)
        assertEquals(ErrorCodes.DECLINED, denied.code)
        assertEquals(RemoteSessionState.IDLE, session.state)
        session.request(id, RemoteControl.FROM_PHONE)
        session.allow(id)
        assertTrue(session.beginWatch(id))
        val busy = session.request(UUID.randomUUID().toString(), RemoteControl.FROM_PC)
        assertFalse(busy.ok)
        session.endWatch(id)
        val again = session.request(UUID.randomUUID().toString(), RemoteControl.FROM_PHONE)
        assertTrue(again.ok)
        session.stop()
        assertEquals(RemoteSessionState.IDLE, session.state)
    }

    @Test
    fun rateLimitCapsABurstToThirtyPerSecond() {
        val session = RemoteSession()
        session.paired = true
        session.enabled = true
        val id = UUID.randomUUID().toString()
        session.request(id, RemoteControl.FROM_PC)
        session.allow(id)
        var accepted = 0
        for (i in 0 until 40) {
            if (session.acceptInput(id, 10_000L + i * RemoteControl.MIN_INPUT_GAP_MS)) accepted++
        }
        assertEquals(RemoteControl.MAX_INPUTS_PER_SECOND, accepted)
        assertTrue(session.acceptInput(id, 11_000L + RemoteControl.MIN_INPUT_GAP_MS))
    }
}
