package org.stellartimelock.send.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Phone → PC remote desktop. The phone does not ask for an Android permission.
 * The PC shows one Allow. Frames stay on the paired link: same Wi-Fi, then the
 * punched direct link, then the relay.
 */
object RemoteDesktop {
    val LABEL: String get() = RemoteControl.LABEL
    const val LINE = "See your computer's screen and control it from this phone."
    const val PHONE_CONTROL_KEY = "phone_control"
    const val PHONE_CONTROL_LABEL = "Control this phone"
    const val PHONE_CONTROL_LINE = "Let the paired computer tap and type on this phone after you tap Allow."
    val READY: String get() = RemotePhrases.current.ready
    const val WAITING = "Waiting for Allow on the computer."
    const val SHOWING = "Showing the paired computer."
    const val SESSION_CODE = "remote-session"
    const val TRY_WATCH = "try_watch"
    const val WIFI_INTERVAL_MS = 100L
    const val WIFI_MAX_WIDTH = 1280
    const val WIFI_JPEG_QUALITY = 60
    const val RELAY_INTERVAL_MS = 250L
    const val RELAY_MAX_WIDTH = 960
    const val RELAY_JPEG_QUALITY = 45

    fun profile(relay: Boolean): DesktopProfile =
        if (relay) DesktopProfile(RELAY_INTERVAL_MS, RELAY_MAX_WIDTH, RELAY_JPEG_QUALITY)
        else DesktopProfile(WIFI_INTERVAL_MS, WIFI_MAX_WIDTH, WIFI_JPEG_QUALITY)

    val FAILED: String get() = RemotePhrases.current.failed

    /**
     * The computer already accepted the request. A closed link, including one
     * that already carried a frame, should open the watch connection.
     * The person stopping the view does not.
     */
    fun opensWatch(requestAccepted: Boolean, userStopped: Boolean): Boolean =
        requestAccepted && !userStopped

    const val KEPT = "The pairing is still saved."
    const val LINK = "The computer's screen stopped."

    /** Same Wi-Fi, then the punched direct link, then the relay. */
    fun route(lan: Boolean, punched: Boolean, relay: Boolean): String = when {
        lan -> "lan"
        punched -> "direct"
        relay -> "relay"
        else -> "offline"
    }

    /** Plain words for the phone. Never the re-pair sentence. */
    fun explain(code: String?, message: String?): String {
        val text = message?.trim().orEmpty()
        if (text.contains("no longer recognizes", ignoreCase = true) ||
            text == RepairPrompt.MESSAGE ||
            RepairPrompt.looksLikeCertificateUnknown(text)
        ) {
            return "$FAILED $KEPT"
        }
        if (code == ErrorCodes.KEY_CHANGED) return TrustNewKey.message(null)
        if (code == ErrorCodes.UNKNOWN_PEER && !text.contains("pair a", ignoreCase = true)) {
            return "$FAILED $KEPT"
        }
        if (text.isBlank()) return FAILED
        return text
    }

    fun isLinkFailure(code: String?, message: String?): Boolean {
        if (RepairPrompt.looksLikeCertificateUnknown(message)) return true
        val text = message?.lowercase().orEmpty()
        val needle = RemotePhrases.current.linkNeedle
        return code == ErrorCodes.IO || code == ErrorCodes.LIMIT || code == ErrorCodes.DECLINED ||
            text.contains("secure connection") || text.contains("already open") ||
            (needle.isNotEmpty() && text.contains(needle))
    }

    /** One silent retry after a drop. An explicit deny or a missing pairing does not retry. */
    fun autoRetry(attempt: Int, userStopped: Boolean, code: String?, message: String?): Boolean {
        if (userStopped || attempt > 0) return false
        if (code == ErrorCodes.KEY_CHANGED) return false
        if (TrustNewKey.applies(true, code, message)) return false
        if (code == ErrorCodes.DECLINED) return false
        if (message?.contains("pair a", ignoreCase = true) == true) return false
        if (message?.contains("not allowed", ignoreCase = true) == true) return false
        return true
    }
}

/** Splits a frame so one relay message stays under the 64 KB websocket cap. */
object FrameSlices {
    const val MAX_SLICE = 32 * 1024

    fun split(payload: ByteArray, maxSlice: Int = MAX_SLICE): List<ByteArray> {
        require(maxSlice in 1..65_535)
        if (payload.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>((payload.size + maxSlice - 1) / maxSlice)
        var index = 0
        while (index < payload.size) {
            val count = minOf(maxSlice, payload.size - index)
            out += payload.copyOfRange(index, index + count)
            index += count
        }
        return out
    }

    fun join(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }
}

/**
 * Keeps only the newest frame. A slow link drops the stale ones instead of
 * filling the socket until the session dies.
 */
class FramePump {
    private val gate = Any()
    private var pending: ByteArray? = null
    var dropped: Long = 0
        private set
    var sentBytes: Long = 0
        private set
    var sentFrames: Int = 0
        private set

    fun offer(frame: ByteArray): Boolean = synchronized(gate) {
        val replaced = pending != null
        if (replaced) dropped++
        pending = frame
        !replaced
    }

    fun take(): ByteArray? = synchronized(gate) {
        val frame = pending
        pending = null
        frame
    }

    fun noteSent(bytes: Int) {
        sentBytes += bytes.coerceAtLeast(0)
        sentFrames++
    }
}

/** While this device is dialing the relay it must not also be the listener in that room. */
class RelayDialGate {
    private val dials = java.util.concurrent.atomic.AtomicInteger(0)

    fun begin() {
        dials.incrementAndGet()
    }

    fun end() {
        dials.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun shouldListen(): Boolean = dials.get() == 0
}

/** Enlarge shows a big code. It does not move the paired relay room. */
object PairingEnlarge {
    fun movesRelay(paired: Boolean): Boolean = !paired
}

data class DesktopProfile(val intervalMs: Long, val maxWidth: Int, val jpegQuality: Int)

/** An allowed phone request stays open and streams. It is not a file transfer. */
object RemoteDesktopSession {
    fun keepsLink(op: String?, from: String?, allowed: Boolean): Boolean =
        allowed && from == RemoteControl.FROM_PHONE &&
            (op == RemoteControl.OP_REQUEST || op == RemoteControl.OP_WATCH)

    fun showsTransferBar(code: String?): Boolean = code != RemoteDesktop.SESSION_CODE
}

/**
 * Build flag for phone → PC remote desktop.
 * Production Play sets [enabled] false. Sideload and the Play beta track set it true.
 * The visible name on those builds is [BETA_LABEL]. A disabled build answers
 * [UNAVAILABLE] and does not use the feature name.
 */
object RemoteDesktopGate {
    val BETA_LABEL: String get() = RemotePhrases.current.betaLabel
    const val LINE = "See your computer's screen and control it from this phone."
    val SETTINGS_LEAD: String get() = RemotePhrases.current.settingsLead
    const val SETTINGS_LEAD_HIDDEN = "TV, folders, and received files are here."
    val PHONE_CONTROL_DETAIL: String get() = RemotePhrases.current.checklistDetail
    const val PHONE_CONTROL_DETAIL_HIDDEN = "This lets the computer tap and type on this phone."
    const val UNAVAILABLE = "Not available."

    fun label(enabled: Boolean): String = if (enabled) BETA_LABEL else ""

    fun settingsLead(enabled: Boolean): String = if (enabled) SETTINGS_LEAD else SETTINGS_LEAD_HIDDEN

    fun phoneControlDetail(enabled: Boolean): String =
        if (enabled) PHONE_CONTROL_DETAIL else PHONE_CONTROL_DETAIL_HIDDEN

    /**
     * Phone → PC. status and watch are that session. A request from the phone is that session.
     * A request from the PC, and drive, are Control this phone.
     */
    fun isDesktopOp(op: String, from: String): Boolean = when (op) {
        RemoteControl.OP_STATUS, RemoteControl.OP_WATCH -> true
        RemoteControl.OP_REQUEST -> !from.equals(RemoteControl.FROM_PC, ignoreCase = true)
        else -> false
    }

    fun allow(enabled: Boolean, service: String, op: String, from: String): Boolean {
        if (service != CompanionServices.REMOTE_CONTROL) return true
        if (!isDesktopOp(op, from)) return true
        return enabled
    }
}

/**
 * What each direction is allowed to ask for.
 * Phone → PC never needs an Android permission or the phone-control setting.
 */
object RemoteGate {
    fun phoneToPc(paired: Boolean): SessionDecision {
        if (!paired) return SessionDecision.fail(ErrorCodes.UNKNOWN_PEER, RemoteControl.NEED_PAIR)
        return SessionDecision.done(RemoteControl.ASK_PC)
    }

    fun phoneNeedsSpecialPermission(): Boolean = false

    fun pcToPhone(paired: Boolean, phoneControlOn: Boolean): SessionDecision {
        if (!paired) return SessionDecision.fail(ErrorCodes.UNKNOWN_PEER, RemoteControl.NEED_PAIR)
        if (!phoneControlOn) return SessionDecision.fail(ErrorCodes.DECLINED, RemoteControl.NEED_TOGGLE)
        return SessionDecision.done(RemoteControl.ASK_PHONE)
    }
}

/**
 * The 1.5.18 button opened [GENERIC_PAGE], the system Accessibility list.
 * On OnePlus / ColorOS that list is TalkBack and similar switches, not FlipSend.
 * A sideloaded accessibility toggle that Android 13+ has not released under
 * restricted settings snaps off and leaves the user on that same page.
 * Remote desktop must not open it. Phone control opens [DETAILS_PAGE] only
 * when the person taps and the service is still off.
 */
object PhoneControlChecklist {
    const val GENERIC_PAGE = "android.settings.ACCESSIBILITY_SETTINGS"
    const val DETAILS_PAGE = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    const val COMPONENT_EXTRA = "android.intent.extra.COMPONENT_NAME"
    const val APP_INFO_PAGE = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val MANUAL =
        "Phone control settings did not open. In Settings, open Accessibility, then Downloaded apps, then FlipSend."
    const val RESTRICTED_DETAIL =
        "If that switch will not stay on, open App info and allow restricted settings. Then open phone control settings again."

    data class Target(val action: String, val component: String?, val packageUri: String?)

    data class Row(
        val id: String,
        val title: String,
        val status: String,
        val detail: String,
        val actionLabel: String,
        val target: Target
    )

    fun isWrongPage(action: String): Boolean = action == GENERIC_PAGE

    fun shouldOpen(serviceOn: Boolean, userTapped: Boolean): Boolean = userTapped && !serviceOn

    fun accessibilityTarget(packageName: String, serviceClass: String): Target =
        Target(DETAILS_PAGE, "$packageName/$serviceClass", null)

    fun appInfoTarget(packageName: String): Target =
        Target(APP_INFO_PAGE, null, "package:$packageName")

    fun rows(
        packageName: String,
        serviceClass: String,
        serviceOn: Boolean,
        sdk: Int,
        remoteDesktop: Boolean = true
    ): List<Row> {
        val rows = mutableListOf(
            Row(
                id = "accessibility",
                title = "Phone control",
                status = if (serviceOn) "On" else "Off",
                detail = RemoteDesktopGate.phoneControlDetail(remoteDesktop),
                actionLabel = if (serviceOn) "Phone control is on" else "Turn on phone control",
                target = accessibilityTarget(packageName, serviceClass)
            )
        )
        if (sdk >= 33) {
            rows += Row(
                id = "restricted",
                title = "Restricted settings",
                status = "Check",
                detail = RESTRICTED_DETAIL,
                actionLabel = "Open app info",
                target = appInfoTarget(packageName)
            )
        }
        return rows
    }
}

object AlwaysAllow {
    fun isDeviceId(value: String): Boolean = runCatching { java.util.UUID.fromString(value) }.isSuccess

    /** A certificate pin is 64 hex characters. It is never an always-allow id. */
    fun contains(ids: Collection<String>, deviceId: String): Boolean =
        isDeviceId(deviceId) && ids.any { isDeviceId(it) && it.equals(deviceId, ignoreCase = true) }

    fun add(ids: Collection<String>, deviceId: String): List<String> {
        if (deviceId.isBlank()) return ids.toList()
        val next = ids.filterNot { it.equals(deviceId, ignoreCase = true) }.toMutableList()
        next += deviceId
        return next
    }

    fun remove(ids: Collection<String>, deviceId: String): List<String> =
        ids.filterNot { it.equals(deviceId, ignoreCase = true) }
}

/** Touch on the phone picture becomes a mouse or wheel event. Pinch stays on the phone. */
object RemotePointer {
    const val LONG_PRESS_MS = 450L
    const val SLOP_PX = 28.0
    private const val PIXELS_PER_NOTCH = 64.0

    /**
     * One finger past the slop is a cursor move. It is a button drag only when
     * [selecting] (long-press or two-finger drag-select). Two fingers otherwise scroll.
     */
    fun classify(pointers: Int, movedPx: Double, heldMs: Long, selecting: Boolean = false): String {
        if (pointers >= 2 && !selecting) return "scroll"
        if (selecting && movedPx > SLOP_PX) return "drag"
        if (heldMs >= LONG_PRESS_MS && movedPx <= SLOP_PX) return "longpress"
        if (movedPx > SLOP_PX) return "move"
        return "tap"
    }

    /** Finger travel in pixels. Up (negative dy) is a positive wheel notch. */
    fun scrollNotches(dyPx: Double): Double {
        if (dyPx.isNaN() || dyPx.isInfinite()) return 0.0
        return (-dyPx / PIXELS_PER_NOTCH).coerceIn(-RemoteControl.MAX_SCROLL, RemoteControl.MAX_SCROLL)
    }

    /** A move repositions the pointer. It does not press a button. */
    fun pressesButton(kind: String): Boolean =
        kind == "down" || kind == "tap" || kind == "swipe" || kind == "longpress"

    fun button(kind: String): String = when (kind) {
        "longpress" -> "right"
        "tap", "down", "up", "swipe" -> "left"
        "scroll", "wheel" -> "wheel"
        else -> "none"
    }
}

/** One touch sample. "move" never holds the mouse button. "pan" stays on the phone. */
data class TouchStep(
    val kind: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val dx: Double = 0.0,
    val dy: Double = 0.0
)

/**
 * Eases the cursor toward the finger. One sample per touch event, so a large
 * jump does not land in a single step. [snap] catches up when the finger lifts.
 */
class CursorGlide(private val alpha: Double = ALPHA) {
    private var ready = false
    private var x = 0.0
    private var y = 0.0

    fun seed(x: Double, y: Double) {
        ready = true
        this.x = x
        this.y = y
    }

    fun follow(targetX: Double, targetY: Double): Pair<Double, Double> {
        if (!ready) {
            seed(targetX, targetY)
            return x to y
        }
        x += (targetX - x) * alpha
        y += (targetY - y) * alpha
        return x to y
    }

    fun snap(x: Double, y: Double) = seed(x, y)

    fun reset() {
        ready = false
    }

    companion object {
        const val ALPHA = 0.65
    }
}

/**
 * Phone touch on the computer picture.
 * One finger past the slop moves the cursor, or pans when the picture is zoomed.
 * A short tap clicks. Pinch does not click.
 * The mouse button goes down only after a long-press, or a two-finger drag that
 * is not a vertical scroll.
 */
class RemoteTouch {
    var phase: String = "idle"
        private set

    private var anchorX = 0.0
    private var anchorY = 0.0
    private var lastX = 0.0
    private var lastY = 0.0
    private var downAt = 0L
    private var selectArmed = false
    private var buttonDown = false
    private var multi = ""
    private var scrollY = 0.0
    private var sentX = Double.NaN
    private var sentY = Double.NaN
    private val glide = CursorGlide()

    fun down(x: Double, y: Double, timeMs: Long): List<TouchStep> {
        clear()
        anchorX = x
        anchorY = y
        lastX = x
        lastY = y
        downAt = timeMs
        phase = "armed"
        return emptyList()
    }

    /** The long-press delay elapsed and the finger is still inside the slop. */
    fun hold(timeMs: Long): List<TouchStep> {
        if (phase != "armed" || selectArmed) return emptyList()
        if (timeMs - downAt < RemotePointer.LONG_PRESS_MS) return emptyList()
        if (hypot(lastX - anchorX, lastY - anchorY) > RemotePointer.SLOP_PX) return emptyList()
        selectArmed = true
        return emptyList()
    }

    fun extraFinger(x: Double, y: Double): List<TouchStep> {
        if (phase == "idle" || phase == "pinch" || phase == "select" || buttonDown) return emptyList()
        if (multi == "scroll" || multi == "select") return emptyList()
        phase = "multi"
        multi = ""
        anchorX = x
        anchorY = y
        lastX = x
        lastY = y
        scrollY = y
        selectArmed = false
        return emptyList()
    }

    fun pinch(): List<TouchStep> {
        val steps = ArrayList<TouchStep>()
        if (buttonDown) {
            steps += TouchStep("up", lastX, lastY)
            buttonDown = false
        }
        phase = "pinch"
        multi = "pinch"
        selectArmed = false
        return steps
    }

    fun move(x: Double, y: Double, pointers: Int, zoom: Double, timeMs: Long): List<TouchStep> {
        if (phase == "idle" || phase == "pinch") {
            lastX = x
            lastY = y
            return emptyList()
        }
        val steps = ArrayList<TouchStep>()
        if (pointers >= 2) return moveMulti(x, y, steps)
        val dist = hypot(x - anchorX, y - anchorY)
        when (phase) {
            "armed" -> {
                if (dist <= RemotePointer.SLOP_PX) {
                    lastX = x
                    lastY = y
                    if (!selectArmed && timeMs - downAt >= RemotePointer.LONG_PRESS_MS) selectArmed = true
                    return steps
                }
                if (selectArmed || timeMs - downAt >= RemotePointer.LONG_PRESS_MS) {
                    beginSelect(steps, anchorX, anchorY)
                    emitCursor(steps, x, y)
                } else if (zoom > 1.0) {
                    phase = "pan"
                    steps += TouchStep("pan", dx = x - lastX, dy = y - lastY)
                } else {
                    phase = "hover"
                    glide.seed(anchorX, anchorY)
                    emitCursor(steps, x, y)
                }
            }
            "hover" -> emitCursor(steps, x, y)
            "pan" -> steps += TouchStep("pan", dx = x - lastX, dy = y - lastY)
            "select" -> emitCursor(steps, x, y)
        }
        lastX = x
        lastY = y
        return steps
    }

    fun up(x: Double, y: Double, timeMs: Long, zoom: Double = 1.0): List<TouchStep> {
        if (phase == "idle") return emptyList()
        val steps = ArrayList<TouchStep>()
        val dist = hypot(x - anchorX, y - anchorY)
        val held = timeMs - downAt
        when {
            phase == "pinch" || multi == "pinch" -> Unit
            buttonDown -> steps += TouchStep("up", x, y)
            multi == "scroll" || phase == "scroll" || (phase == "multi" && multi.isEmpty()) -> Unit
            phase == "hover" -> steps += TouchStep("move", x, y)
            phase == "pan" -> Unit
            dist <= RemotePointer.SLOP_PX && (selectArmed || held >= RemotePointer.LONG_PRESS_MS) ->
                steps += TouchStep("longpress", anchorX, anchorY)
            dist <= RemotePointer.SLOP_PX ->
                steps += TouchStep("tap", anchorX, anchorY)
            selectArmed || held >= RemotePointer.LONG_PRESS_MS -> {
                steps += TouchStep("down", anchorX, anchorY)
                steps += TouchStep("up", x, y)
            }
            zoom > 1.0 -> Unit
            else -> steps += TouchStep("move", x, y)
        }
        clear()
        return steps
    }

    fun pointerUp(pointersLeft: Int, x: Double, y: Double): List<TouchStep> {
        val steps = ArrayList<TouchStep>()
        if (pointersLeft > 1) return steps
        if (buttonDown) steps += TouchStep("up", x, y)
        clear()
        return steps
    }

    fun cancel(): List<TouchStep> {
        val steps = ArrayList<TouchStep>()
        if (buttonDown) steps += TouchStep("up", lastX, lastY)
        clear()
        return steps
    }

    private fun moveMulti(x: Double, y: Double, steps: ArrayList<TouchStep>): List<TouchStep> {
        if (multi.isEmpty()) {
            if (phase != "multi") {
                anchorX = lastX
                anchorY = lastY
                scrollY = lastY
                phase = "multi"
            }
            val dx = x - anchorX
            val dy = y - anchorY
            if (hypot(dx, dy) <= RemotePointer.SLOP_PX) {
                lastX = x
                lastY = y
                return steps
            }
            // Up/down scrolls. Any other two-finger drag holds the button.
            multi = if (abs(dy) > abs(dx) * 1.5) "scroll" else "select"
            phase = multi
            if (multi == "select") beginSelect(steps, anchorX, anchorY)
        }
        if (multi == "scroll") {
            val delta = y - scrollY
            scrollY = y
            if (abs(delta) >= 8.0) {
                val notches = RemotePointer.scrollNotches(delta)
                if (notches != 0.0) steps += TouchStep("scroll", x, y, dy = notches)
            }
        } else if (multi == "select") {
            emitCursor(steps, x, y)
        }
        lastX = x
        lastY = y
        return steps
    }

    private fun beginSelect(steps: MutableList<TouchStep>, x: Double, y: Double) {
        if (buttonDown) return
        buttonDown = true
        phase = "select"
        glide.seed(x, y)
        steps += TouchStep("down", x, y)
    }

    private fun emitCursor(steps: MutableList<TouchStep>, x: Double, y: Double) {
        val (sx, sy) = glide.follow(x, y)
        if (!sentX.isNaN() && hypot(sx - sentX, sy - sentY) < 1.0) return
        sentX = sx
        sentY = sy
        steps += TouchStep("move", sx, sy)
    }

    private fun clear() {
        phase = "idle"
        selectArmed = false
        buttonDown = false
        multi = ""
        sentX = Double.NaN
        sentY = Double.NaN
        glide.reset()
    }
}

data class DesktopPlace(val left: Double, val top: Double, val drawnW: Double, val drawnH: Double)

/** Letterboxed picture with pinch zoom. Zoom 1 matches [RemoteCoords.fromView]. */
class RemoteViewport {
    var zoom: Double = 1.0
        private set
    var panX: Double = 0.0
        private set
    var panY: Double = 0.0
        private set

    fun reset() {
        zoom = 1.0
        panX = 0.0
        panY = 0.0
    }

    fun pinch(
        factor: Double,
        focusX: Double,
        focusY: Double,
        viewW: Double,
        viewH: Double,
        imageW: Double,
        imageH: Double
    ) {
        if (factor.isNaN() || factor.isInfinite() || factor <= 0.0) return
        val before = point(focusX, focusY, viewW, viewH, imageW, imageH)
        zoom = (zoom * factor).coerceIn(1.0, 4.0)
        if (zoom <= 1.0) {
            reset()
            return
        }
        if (before != null && viewW > 0.0 && imageW > 0.0) {
            val fit = min(viewW / imageW, viewH / imageH)
            val drawnW = imageW * fit * zoom
            val drawnH = imageH * fit * zoom
            panX = focusX - before.first * drawnW - (viewW - drawnW) / 2.0
            panY = focusY - before.second * drawnH - (viewH - drawnH) / 2.0
            clamp(viewW, viewH, imageW, imageH)
        }
    }

    /** One-finger drag while zoomed. Zoom 1 has nowhere to pan. */
    fun panBy(dx: Double, dy: Double, viewW: Double, viewH: Double, imageW: Double, imageH: Double) {
        if (zoom <= 1.0) return
        if (viewW <= 0.0 || viewH <= 0.0 || imageW <= 0.0 || imageH <= 0.0) return
        if (dx.isNaN() || dy.isNaN() || dx.isInfinite() || dy.isInfinite()) return
        panX += dx
        panY += dy
        clamp(viewW, viewH, imageW, imageH)
    }

    fun placement(viewW: Double, viewH: Double, imageW: Double, imageH: Double): DesktopPlace? {
        if (viewW <= 0.0 || viewH <= 0.0 || imageW <= 0.0 || imageH <= 0.0) return null
        val fit = min(viewW / imageW, viewH / imageH)
        val drawnW = imageW * fit * zoom
        val drawnH = imageH * fit * zoom
        val left = (viewW - drawnW) / 2.0 + panX
        val top = (viewH - drawnH) / 2.0 + panY
        return DesktopPlace(left, top, drawnW, drawnH)
    }

    fun point(
        viewX: Double,
        viewY: Double,
        viewW: Double,
        viewH: Double,
        imageW: Double,
        imageH: Double
    ): Pair<Double, Double>? {
        val place = placement(viewW, viewH, imageW, imageH) ?: return null
        val ix = viewX - place.left
        val iy = viewY - place.top
        if (ix < 0.0 || iy < 0.0 || ix > place.drawnW || iy > place.drawnH) return null
        return (ix / place.drawnW).coerceIn(0.0, 1.0) to (iy / place.drawnH).coerceIn(0.0, 1.0)
    }

    private fun clamp(viewW: Double, viewH: Double, imageW: Double, imageH: Double) {
        val fit = min(viewW / imageW, viewH / imageH)
        val drawnW = imageW * fit * zoom
        val drawnH = imageH * fit * zoom
        val maxX = abs(drawnW - viewW) / 2.0
        val maxY = abs(drawnH - viewH) / 2.0
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }
}
