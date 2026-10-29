package org.stellartimelock.send.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class RemoteDesktopTest {
    @BeforeEach
    fun sideloadWords() {
        SideloadRemoteWords.install()
    }

    @Test
    fun anAllowedPhoneRequestKeepsTheLinkAndSkipsTheTransferBar() {
        assertTrue(RemoteDesktopSession.keepsLink(RemoteControl.OP_REQUEST, RemoteControl.FROM_PHONE, true))
        assertTrue(RemoteDesktopSession.keepsLink(RemoteControl.OP_WATCH, RemoteControl.FROM_PHONE, true))
        assertFalse(RemoteDesktopSession.keepsLink(RemoteControl.OP_REQUEST, RemoteControl.FROM_PHONE, false))
        assertFalse(RemoteDesktopSession.keepsLink(RemoteControl.OP_REQUEST, RemoteControl.FROM_PC, true))
        assertFalse(RemoteDesktopSession.keepsLink(RemoteControl.OP_STATUS, RemoteControl.FROM_PHONE, true))
        assertFalse(RemoteDesktopSession.showsTransferBar(RemoteDesktop.SESSION_CODE))
        assertTrue(RemoteDesktopSession.showsTransferBar("ok"))
        assertEquals("Remote desktop active - Disconnect", RemoteControl.ACTIVE_LINE)
        assertEquals("Update the FlipSend app on this phone, then tap Remote desktop again.", RemoteControl.UPDATE_PHONE)
        assertTrue(RemoteDesktop.opensWatch(requestAccepted = true, userStopped = false))
        assertFalse(RemoteDesktop.opensWatch(requestAccepted = false, userStopped = false))
        assertFalse(RemoteDesktop.opensWatch(requestAccepted = true, userStopped = true))
    }

    @Test
    fun aClosedRequestLeavesTheAllowInPlaceForWatch() {
        val session = RemoteSession()
        session.paired = true
        val id = UUID.randomUUID().toString()
        assertTrue(session.request(id, RemoteControl.FROM_PHONE).ok)
        assertTrue(session.allow(id).ok)
        assertTrue(session.beginWatch(id))
        session.parkWatch(id)
        assertEquals(RemoteSessionState.ACTIVE, session.state)
        assertFalse(session.watching)
        assertTrue(session.beginWatch(id))
    }

    @Test
    fun framesAndInputStayOnThePairedTypes() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val frame = FrameCodec.encode(MessageType.SCREEN_FRAME, jpeg)
        assertEquals(13, frame[5].toInt())
        assertTrue(ScreenShare.acceptJpeg(jpeg))
        val session = UUID.randomUUID().toString()
        val input = RemoteInput("longpress", session, 0.25, 0.5)
        val encoded = FrameCodec.encode(MessageType.REMOTE_INPUT, RemoteInputCodec.encode(input))
        assertEquals(14, encoded[5].toInt())
        val parsed = RemoteInputCodec.parse(encoded.copyOfRange(12, encoded.size))
        assertEquals("longpress", parsed!!.kind)
        assertEquals(0.25, parsed.x, 0.0001)
    }

    @Test
    fun phoneToPcNeedsNoAndroidPermissionAndNoPhoneToggle() {
        assertFalse(RemoteGate.phoneNeedsSpecialPermission())
        val unpaired = RemoteGate.phoneToPc(paired = false)
        assertFalse(unpaired.ok)
        assertEquals(ErrorCodes.UNKNOWN_PEER, unpaired.code)
        val ready = RemoteGate.phoneToPc(paired = true)
        assertTrue(ready.ok)
        assertEquals(RemoteControl.ASK_PC, ready.message)

        val session = RemoteSession()
        session.paired = true
        session.enabled = false
        val id = UUID.randomUUID().toString()
        val asked = session.request(id, RemoteControl.FROM_PHONE)
        assertTrue(asked.ok)
        assertEquals(RemoteSessionState.PENDING, session.state)
        assertTrue(session.allow(id).ok)
        assertTrue(session.acceptInput(id, 5_000))
    }

    @Test
    fun pcToPhoneStaysBehindItsOwnSetting() {
        val off = RemoteGate.pcToPhone(paired = true, phoneControlOn = false)
        assertFalse(off.ok)
        assertEquals(RemoteControl.NEED_TOGGLE, off.message)
        val on = RemoteGate.pcToPhone(paired = true, phoneControlOn = true)
        assertTrue(on.ok)
        assertEquals(RemoteControl.ASK_PHONE, on.message)
    }

    @Test
    fun phoneControlOpensTheServicePageAndDoesNotLoop() {
        assertTrue(PhoneControlChecklist.isWrongPage(PhoneControlChecklist.GENERIC_PAGE))
        assertFalse(PhoneControlChecklist.isWrongPage(PhoneControlChecklist.DETAILS_PAGE))
        assertFalse(PhoneControlChecklist.shouldOpen(serviceOn = true, userTapped = true))
        assertFalse(PhoneControlChecklist.shouldOpen(serviceOn = false, userTapped = false))
        assertTrue(PhoneControlChecklist.shouldOpen(serviceOn = false, userTapped = true))
        val target = PhoneControlChecklist.accessibilityTarget("org.stellartimelock.stellarsend", "org.stellartimelock.send.RemoteControlService")
        assertEquals(PhoneControlChecklist.DETAILS_PAGE, target.action)
        assertEquals(
            "org.stellartimelock.stellarsend/org.stellartimelock.send.RemoteControlService",
            target.component
        )
        val rows = PhoneControlChecklist.rows("org.stellartimelock.stellarsend", "org.stellartimelock.send.RemoteControlService", serviceOn = false, sdk = 36)
        assertEquals("Off", rows.first().status)
        assertEquals("Turn on phone control", rows.first().actionLabel)
        assertTrue(rows.any { it.id == "restricted" && it.target.action == PhoneControlChecklist.APP_INFO_PAGE })
        assertEquals("On", PhoneControlChecklist.rows("a", "b", serviceOn = true, sdk = 28).first().status)
        assertEquals(1, PhoneControlChecklist.rows("a", "b", serviceOn = true, sdk = 28).size)
    }

    @Test
    fun pointerMapsTapDragLongPressAndScroll() {
        assertEquals("tap", RemotePointer.classify(1, 4.0, 80))
        assertEquals("longpress", RemotePointer.classify(1, 4.0, RemotePointer.LONG_PRESS_MS))
        assertEquals("move", RemotePointer.classify(1, RemotePointer.SLOP_PX + 1, 100))
        assertEquals("drag", RemotePointer.classify(1, RemotePointer.SLOP_PX + 1, 100, selecting = true))
        assertEquals("scroll", RemotePointer.classify(2, 40.0, 100))
        assertEquals("right", RemotePointer.button("longpress"))
        assertEquals("left", RemotePointer.button("tap"))
        assertEquals("none", RemotePointer.button("move"))
        assertFalse(RemotePointer.pressesButton("move"))
        assertTrue(RemotePointer.pressesButton("down"))
        assertEquals("wheel", RemotePointer.button("scroll"))
        assertEquals(1.0, RemotePointer.scrollNotches(-64.0), 0.001)
        assertEquals(-1.0, RemotePointer.scrollNotches(64.0), 0.001)
        assertEquals(RemoteControl.MAX_SCROLL, RemotePointer.scrollNotches(-10_000.0), 0.001)
        assertEquals(0.0, RemotePointer.scrollNotches(Double.NaN), 0.001)
    }

    @Test
    fun oneFingerDragMovesOrPansAndDoesNotHoldTheButton() {
        val touch = RemoteTouch()
        touch.down(10.0, 20.0, 0)
        assertTrue(touch.move(16.0, 22.0, 1, 1.0, 40).isEmpty())
        val dragged = touch.move(80.0, 20.0, 1, 1.0, 80)
        assertEquals(listOf("move"), dragged.map { it.kind })
        val eased = 10.0 + (80.0 - 10.0) * CursorGlide.ALPHA
        assertEquals(eased, dragged[0].x, 0.001)
        assertTrue(dragged[0].x < 80.0)
        assertFalse(dragged.any { RemotePointer.pressesButton(it.kind) })
        val lifted = touch.up(90.0, 24.0, 140)
        assertEquals(listOf("move"), lifted.map { it.kind })
        assertEquals(90.0, lifted[0].x, 0.001)
        assertEquals("idle", touch.phase)

        val tap = RemoteTouch()
        tap.down(10.0, 20.0, 0)
        val clicked = tap.up(14.0, 22.0, 100)
        assertEquals("tap", clicked.single().kind)
        assertEquals(10.0, clicked.single().x, 0.001)

        val zoomed = RemoteTouch()
        zoomed.down(0.0, 0.0, 0)
        val pan = zoomed.move(40.0, 10.0, 1, 2.0, 50)
        assertEquals("pan", pan.single().kind)
        assertEquals(40.0, pan.single().dx, 0.001)
        assertEquals(10.0, pan.single().dy, 0.001)
        assertTrue(zoomed.up(50.0, 10.0, 80, zoom = 2.0).isEmpty())
    }

    @Test
    fun dragSelectNeedsALongPressOrASidewaysTwoFingerDrag() {
        val held = RemoteTouch()
        held.down(10.0, 20.0, 0)
        assertTrue(held.hold(RemotePointer.LONG_PRESS_MS).isEmpty())
        val right = held.up(12.0, 20.0, RemotePointer.LONG_PRESS_MS + 40)
        assertEquals("longpress", right.single().kind)
        assertEquals(10.0, right.single().x, 0.001)

        val select = RemoteTouch()
        select.down(10.0, 20.0, 0)
        select.hold(RemotePointer.LONG_PRESS_MS)
        val dragging = select.move(80.0, 20.0, 1, 1.0, RemotePointer.LONG_PRESS_MS + 20)
        assertEquals(listOf("down", "move"), dragging.map { it.kind })
        assertEquals(10.0, dragging[0].x, 0.001)
        assertTrue(dragging[1].x > 10.0 && dragging[1].x < 80.0)
        assertEquals("up", select.up(90.0, 20.0, RemotePointer.LONG_PRESS_MS + 80).single().kind)

        val dropped = RemoteTouch()
        dropped.down(0.0, 0.0, 0)
        dropped.hold(RemotePointer.LONG_PRESS_MS)
        dropped.move(60.0, 0.0, 1, 1.0, RemotePointer.LONG_PRESS_MS + 10)
        assertEquals("up", dropped.cancel().single().kind)

        val scroll = RemoteTouch()
        scroll.down(100.0, 100.0, 0)
        scroll.extraFinger(100.0, 100.0)
        val wheel = scroll.move(100.0, 160.0, 2, 1.0, 40)
        assertEquals("scroll", wheel.single().kind)
        assertTrue(wheel.single().dy < 0.0)
        assertFalse(wheel.any { RemotePointer.pressesButton(it.kind) })

        val sideways = RemoteTouch()
        sideways.down(100.0, 100.0, 0)
        sideways.extraFinger(100.0, 100.0)
        val grabbed = sideways.move(160.0, 100.0, 2, 1.0, 40)
        assertEquals(listOf("down", "move"), grabbed.map { it.kind })
        assertEquals("up", sideways.pointerUp(1, 170.0, 100.0).single().kind)

        val pinch = RemoteTouch()
        pinch.down(0.0, 0.0, 0)
        pinch.move(50.0, 0.0, 1, 1.0, 30)
        assertTrue(pinch.pinch().none { RemotePointer.pressesButton(it.kind) })
        assertTrue(pinch.move(80.0, 10.0, 2, 2.0, 50).isEmpty())
        assertTrue(pinch.up(80.0, 10.0, 80, zoom = 2.0).isEmpty())
    }

    @Test
    fun zoomedPanMovesThePictureAndFitDoesNot() {
        val fit = RemoteViewport()
        fit.panBy(40.0, 10.0, 400.0, 200.0, 100.0, 200.0)
        assertEquals(0.0, fit.panX, 0.001)
        assertEquals(0.0, fit.panY, 0.001)
        val view = RemoteViewport()
        view.pinch(2.0, 200.0, 100.0, 400.0, 200.0, 100.0, 200.0)
        val before = view.point(200.0, 100.0, 400.0, 200.0, 100.0, 200.0)
        view.panBy(30.0, 0.0, 400.0, 200.0, 100.0, 200.0)
        val after = view.point(200.0, 100.0, 400.0, 200.0, 100.0, 200.0)
        assertTrue(after!!.first < before!!.first)
    }

    @Test
    fun zoomKeepsThePointUnderTheFingers() {
        val view = RemoteViewport()
        val plain = RemoteCoords.fromView(200.0, 50.0, 400.0, 200.0, 100.0, 200.0)
        val same = view.point(200.0, 50.0, 400.0, 200.0, 100.0, 200.0)
        assertEquals(plain!!.first, same!!.first, 0.001)
        assertEquals(plain.second, same.second, 0.001)
        view.pinch(2.0, 200.0, 100.0, 400.0, 200.0, 100.0, 200.0)
        val held = view.point(200.0, 100.0, 400.0, 200.0, 100.0, 200.0)
        assertEquals(0.5, held!!.first, 0.02)
        assertEquals(0.5, held.second, 0.02)
        assertNull(view.point(0.0, 0.0, 0.0, 200.0, 100.0, 200.0))
    }

    @Test
    fun routePrefersLanThenDirectThenRelayAndSlowsDownOnRelay() {
        assertEquals("lan", RemoteDesktop.route(lan = true, punched = true, relay = true))
        assertEquals("direct", RemoteDesktop.route(lan = false, punched = true, relay = true))
        assertEquals("relay", RemoteDesktop.route(lan = false, punched = false, relay = true))
        assertEquals("offline", RemoteDesktop.route(lan = false, punched = false, relay = false))
        val wifi = RemoteDesktop.profile(relay = false)
        val relay = RemoteDesktop.profile(relay = true)
        assertEquals(100L, wifi.intervalMs)
        assertEquals(1280, wifi.maxWidth)
        assertEquals(60, wifi.jpegQuality)
        assertTrue(relay.intervalMs > wifi.intervalMs)
        assertTrue(relay.maxWidth < wifi.maxWidth)
        assertTrue(relay.jpegQuality < wifi.jpegQuality)
        assertTrue(wifi.maxWidth * wifi.jpegQuality > 0)
        assertEquals(RemoteDesktop.LINE, "See your computer's screen and control it from this phone.")
    }

    @Test
    fun alwaysAllowRemembersOnePhone() {
        val id = UUID.randomUUID().toString()
        assertFalse(AlwaysAllow.contains(emptyList(), id))
        val saved = AlwaysAllow.add(emptyList(), id)
        assertTrue(AlwaysAllow.contains(saved, id.uppercase()))
        assertFalse(AlwaysAllow.contains(AlwaysAllow.remove(saved, id), id))
        val pin = "ab".repeat(32)
        assertFalse(AlwaysAllow.contains(listOf(pin), id))
        assertFalse(AlwaysAllow.contains(saved, pin))
    }

    @Test
    fun aDroppedSessionAcceptsTheNextRequest() {
        val session = RemoteSession()
        session.paired = true
        val first = UUID.randomUUID().toString()
        assertTrue(session.request(first, RemoteControl.FROM_PHONE).ok)
        assertTrue(session.allow(first).ok)
        assertTrue(session.beginWatch(first))
        assertFalse(session.request(UUID.randomUUID().toString(), RemoteControl.FROM_PHONE).ok)
        assertTrue(session.endWatch(first).ok)
        assertEquals(RemoteSessionState.IDLE, session.state)
        val second = UUID.randomUUID().toString()
        assertTrue(session.request(second, RemoteControl.FROM_PHONE).ok)
        assertEquals(RemoteSessionState.PENDING, session.state)
    }

    @Test
    fun framesAreChunkedAndASlowLinkKeepsOnlyTheNewest() {
        val payload = ByteArray(FrameSlices.MAX_SLICE * 2 + 10) { it.toByte() }
        val parts = FrameSlices.split(payload)
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.size <= FrameSlices.MAX_SLICE && it.size < 65_536 })
        assertTrue(parts.toByteArrayContentEquals(payload))
        val pump = FramePump()
        assertTrue(pump.offer(byteArrayOf(1)))
        assertFalse(pump.offer(byteArrayOf(2)))
        assertFalse(pump.offer(byteArrayOf(3)))
        assertEquals(2, pump.dropped)
        assertEquals(3.toByte(), pump.take()!![0])
        pump.noteSent(30)
        assertEquals(1, pump.sentFrames)
        assertEquals(30L, pump.sentBytes)
    }

    @Test
    fun remoteDesktopNeverAsksTheUserToUnpair() {
        val alert = "Read error: SSLV3_ALERT_CERTIFICATE_UNKNOWN"
        val explained = RemoteDesktop.explain(ErrorCodes.IO, alert)
        assertFalse(explained.contains("no longer recognizes"))
        assertFalse(explained.contains("Re-scan"))
        assertTrue(explained.contains("pairing is still saved"))
        assertFalse(RepairPrompt.unrecognized(ErrorCodes.IO, alert))
        assertFalse(RepairPrompt.unrecognized(ErrorCodes.DECLINED, RemoteControl.DENIED))
        assertFalse(RepairPrompt.unrecognized(ErrorCodes.LIMIT, "A remote control session is already open."))
        assertEquals(RemoteDesktop.FAILED, RemoteDesktop.explain(ErrorCodes.IO, ""))
        assertTrue(RemoteDesktop.autoRetry(0, userStopped = false, code = ErrorCodes.IO, message = alert))
        assertFalse(RemoteDesktop.autoRetry(1, userStopped = false, code = ErrorCodes.IO, message = alert))
        assertFalse(RemoteDesktop.autoRetry(0, userStopped = true, code = ErrorCodes.IO, message = alert))
        assertFalse(RemoteDesktop.autoRetry(0, userStopped = false, code = ErrorCodes.DECLINED, message = RemoteControl.DENIED))
        assertFalse(RemoteDesktop.autoRetry(0, userStopped = false, code = ErrorCodes.KEY_CHANGED, message = "The paired device key changed."))
        assertEquals(TrustNewKey.message(null), RemoteDesktop.explain(ErrorCodes.KEY_CHANGED, "The paired device key changed."))
        assertFalse(PairingEnlarge.movesRelay(paired = true))
        assertTrue(PairingEnlarge.movesRelay(paired = false))
        val gate = RelayDialGate()
        assertTrue(gate.shouldListen())
        gate.begin()
        assertFalse(gate.shouldListen())
        gate.end()
        assertTrue(gate.shouldListen())
        val notices = NoticeGate()
        assertTrue(notices.allow("same", 1_000))
        assertFalse(notices.allow("same", 2_000))
        assertTrue(notices.allow("same", 10_000))
    }

    private fun List<ByteArray>.toByteArrayContentEquals(payload: ByteArray): Boolean {
        val joined = FrameSlices.join(this)
        return joined.contentEquals(payload)
    }
}
