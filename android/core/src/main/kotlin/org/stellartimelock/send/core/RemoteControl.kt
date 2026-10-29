package org.stellartimelock.send.core

import org.json.JSONObject

/**
 * Phone ↔ PC control on the paired TLS link. Remote desktop (phone sees the
 * PC) needs no Android permission. Control this phone is a separate setting
 * and uses the accessibility service. No new ports.
 */
object RemoteControl {
    const val SERVICE = CompanionServices.REMOTE_CONTROL
    const val PREF_KEY = "remote_control"
    const val MAX_WIDTH = ScreenShare.MAX_WIDTH
    const val MAX_JPEG_BYTES = ScreenShare.MAX_JPEG_BYTES
    const val INTERVAL_MS = ScreenShare.INTERVAL_MS
    const val MAX_TEXT_CHARS = 256
    const val MAX_INPUTS_PER_SECOND = 30
    const val MIN_INPUT_GAP_MS = 20L
    const val MAX_SCROLL = 8.0
    val LABEL: String get() = RemotePhrases.current.label
    val NEED_PAIR: String get() = RemotePhrases.current.needPair
    const val NEED_TOGGLE = "Turn on Control this phone in Settings first."
    const val NEED_ALLOW = "The other device has to tap Allow first."
    const val OFF = "Control this phone is off."
    const val ASK_PHONE = "Let the paired computer control this phone?"
    const val ASK_PC = "Let the paired phone control this computer?"
    val ALLOWED: String get() = RemotePhrases.current.allowed
    val DENIED: String get() = RemotePhrases.current.denied
    val STOPPED: String get() = RemotePhrases.current.stopped
    const val BANNER = "Being controlled — Stop"
    const val BANNER_DETAIL = "Your paired device can tap and type here until you tap Stop."
    val ACTIVE_LINE: String get() = RemotePhrases.current.activeLine
    val UPDATE_PHONE: String get() = RemotePhrases.current.updatePhone
    val ACCESSIBILITY_HINT: String get() = RemotePhrases.current.accessibilityHint
    val ACCESSIBILITY_DESCRIPTION: String get() = RemotePhrases.current.accessibilityDescription
    val IOS_LIMIT: String get() = RemotePhrases.current.iosLimit
    const val FROM_PC = "pc"
    const val FROM_PHONE = "phone"
    const val OP_STATUS = "status"
    const val OP_REQUEST = "request"
    const val OP_DRIVE = "drive"
    const val OP_WATCH = "watch"
    const val OP_STOP = "stop"

    val KINDS = setOf(
        "tap", "swipe", "scroll", "move", "down", "up", "text", "key",
        "back", "home", "recents", "wheel", "longpress"
    )
}

enum class RemoteSessionState {
    IDLE, PENDING, ACTIVE
}

data class RemoteInput(
    val kind: String,
    val session: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val x2: Double = 0.0,
    val y2: Double = 0.0,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val text: String? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
            .put("kind", kind)
            .put("session", session)
            .put("x", x)
            .put("y", y)
        if (kind == "swipe") {
            json.put("x2", x2).put("y2", y2)
        }
        if (kind == "scroll" || kind == "wheel") {
            json.put("dx", dx).put("dy", dy)
        }
        if (text != null) json.put("text", text)
        return json
    }
}

object RemoteInputCodec {
    fun parse(payload: ByteArray): RemoteInput? {
        val json = try {
            JSONObject(payload.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        return parse(json)
    }

    fun parse(json: JSONObject): RemoteInput? {
        val kind = json.optString("kind", "")
        val session = json.optString("session", "")
        if (kind !in RemoteControl.KINDS) return null
        if (!isUuid(session)) return null
        val text = if (json.has("text") && !json.isNull("text")) json.optString("text") else null
        if (kind == "text") {
            if (text.isNullOrEmpty() || text.length > RemoteControl.MAX_TEXT_CHARS) return null
        }
        if (kind == "key" && (text.isNullOrEmpty() || text.length > 32)) return null
        return RemoteInput(
            kind = kind,
            session = session,
            x = clamp01(json.optDouble("x", 0.0)),
            y = clamp01(json.optDouble("y", 0.0)),
            x2 = clamp01(json.optDouble("x2", 0.0)),
            y2 = clamp01(json.optDouble("y2", 0.0)),
            dx = clampScroll(json.optDouble("dx", 0.0)),
            dy = clampScroll(json.optDouble("dy", 0.0)),
            text = text
        )
    }

    fun encode(input: RemoteInput): ByteArray = input.toJson().toString().toByteArray(Charsets.UTF_8)

    private fun isUuid(value: String): Boolean = runCatching { java.util.UUID.fromString(value) }.isSuccess

    private fun clamp01(value: Double): Double {
        if (value.isNaN() || value.isInfinite()) return 0.0
        return value.coerceIn(0.0, 1.0)
    }

    private fun clampScroll(value: Double): Double {
        if (value.isNaN() || value.isInfinite()) return 0.0
        return value.coerceIn(-RemoteControl.MAX_SCROLL, RemoteControl.MAX_SCROLL)
    }
}

object RemoteCoords {
    fun jpegSize(deviceWidth: Int, deviceHeight: Int, maxWidth: Int = RemoteControl.MAX_WIDTH): Pair<Int, Int> {
        val width = deviceWidth.coerceAtLeast(1)
        val height = deviceHeight.coerceAtLeast(1)
        if (width <= maxWidth) return width to height
        val scale = maxWidth.toDouble() / width.toDouble()
        return maxWidth to (height * scale).toInt().coerceAtLeast(1)
    }

    /**
     * Map a click on a uniformly scaled view (letterboxed) onto 0..1 of the JPEG.
     * Frames are capped at [RemoteControl.MAX_WIDTH], then shown with Uniform stretch.
     */
    fun fromView(
        viewX: Double,
        viewY: Double,
        viewWidth: Double,
        viewHeight: Double,
        imageWidth: Double,
        imageHeight: Double
    ): Pair<Double, Double>? {
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
        val scale = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
        val drawnW = imageWidth * scale
        val drawnH = imageHeight * scale
        val left = (viewWidth - drawnW) / 2.0
        val top = (viewHeight - drawnH) / 2.0
        val ix = viewX - left
        val iy = viewY - top
        if (ix < 0 || iy < 0 || ix > drawnW || iy > drawnH) return null
        return (ix / drawnW).coerceIn(0.0, 1.0) to (iy / drawnH).coerceIn(0.0, 1.0)
    }

    /** 0..1 on the JPEG maps back to full device pixels (scale cancels). */
    fun toDevice(normX: Double, normY: Double, deviceWidth: Int, deviceHeight: Int): Pair<Int, Int> {
        val width = deviceWidth.coerceAtLeast(1)
        val height = deviceHeight.coerceAtLeast(1)
        val x = (normX.coerceIn(0.0, 1.0) * width).toInt().coerceIn(0, width - 1)
        val y = (normY.coerceIn(0.0, 1.0) * height).toInt().coerceIn(0, height - 1)
        return x to y
    }

    fun jpegToDevice(jpegX: Int, jpegY: Int, jpegWidth: Int, jpegHeight: Int, deviceWidth: Int, deviceHeight: Int): Pair<Int, Int> {
        val jw = jpegWidth.coerceAtLeast(1)
        val jh = jpegHeight.coerceAtLeast(1)
        return toDevice(jpegX.toDouble() / jw, jpegY.toDouble() / jh, deviceWidth, deviceHeight)
    }
}

data class SessionDecision(val ok: Boolean, val code: String, val message: String) {
    companion object {
        fun done(message: String) = SessionDecision(true, "ok", message)
        fun fail(code: String, message: String) = SessionDecision(false, code, message)
    }
}

/**
 * Allow / Stop state for one remote-control session. Pairing is checked by
 * the caller. The Settings toggle must be on before Allow.
 */
class RemoteSession(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile var enabled: Boolean = false
    @Volatile var paired: Boolean = false
    @Volatile var state: RemoteSessionState = RemoteSessionState.IDLE
        private set
    @Volatile var sessionId: String? = null
        private set
    @Volatile var from: String? = null
        private set
    @Volatile var watching: Boolean = false
        private set

    private val gate = Any()
    private var lastInputMs = 0L
    private var windowStartMs = 0L
    private var windowCount = 0

    fun request(session: String, from: String): SessionDecision = synchronized(gate) {
        if (!paired) return SessionDecision.fail(ErrorCodes.UNKNOWN_PEER, RemoteControl.NEED_PAIR)
        if (!isSessionId(session)) return SessionDecision.fail(ErrorCodes.BAD_REQUEST, "That session is not valid.")
        if (from != RemoteControl.FROM_PC && from != RemoteControl.FROM_PHONE) {
            return SessionDecision.fail(ErrorCodes.BAD_REQUEST, "Who is controlling is missing.")
        }
        // Phone → PC is remote desktop. The PC Allow tap is the gate.
        // PC → phone still needs the Control this phone setting.
        if (from != RemoteControl.FROM_PHONE && !enabled) {
            return SessionDecision.fail(ErrorCodes.DECLINED, RemoteControl.NEED_TOGGLE)
        }
        if (state != RemoteSessionState.IDLE) {
            if (!watching) {
                clear()
            } else if (state == RemoteSessionState.ACTIVE && sessionId == session) {
                return SessionDecision.done(RemoteControl.ALLOWED)
            } else {
                return SessionDecision.fail(ErrorCodes.LIMIT, "A remote control session is already open.")
            }
        }
        this.sessionId = session
        this.from = from
        this.state = RemoteSessionState.PENDING
        resetLimiter()
        return SessionDecision.done(if (from == RemoteControl.FROM_PC) RemoteControl.ASK_PHONE else RemoteControl.ASK_PC)
    }

    fun allow(session: String): SessionDecision = synchronized(gate) {
        if (!paired) return SessionDecision.fail(ErrorCodes.UNKNOWN_PEER, RemoteControl.NEED_PAIR)
        if (state != RemoteSessionState.PENDING || sessionId != session) {
            return SessionDecision.fail(ErrorCodes.DECLINED, RemoteControl.NEED_ALLOW)
        }
        if (from != RemoteControl.FROM_PHONE && !enabled) {
            return SessionDecision.fail(ErrorCodes.DECLINED, RemoteControl.NEED_TOGGLE)
        }
        state = RemoteSessionState.ACTIVE
        resetLimiter()
        return SessionDecision.done(RemoteControl.ALLOWED)
    }

    fun deny(session: String): SessionDecision = synchronized(gate) {
        if (state != RemoteSessionState.PENDING || sessionId != session) {
            return SessionDecision.fail(ErrorCodes.DECLINED, RemoteControl.DENIED)
        }
        clear()
        return SessionDecision.fail(ErrorCodes.DECLINED, RemoteControl.DENIED)
    }

    fun beginWatch(session: String): Boolean = synchronized(gate) {
        if (state != RemoteSessionState.ACTIVE || sessionId != session) return false
        watching = true
        true
    }

    /** The request socket closed before frames. A 1.5.34 phone opens watch next. */
    fun parkWatch(session: String? = null) {
        synchronized(gate) {
            if (session != null && sessionId != null && session != sessionId) return
            watching = false
        }
    }

    fun endWatch(session: String? = null): SessionDecision = synchronized(gate) {
        if (session != null && sessionId != null && session != sessionId) {
            return SessionDecision.fail(ErrorCodes.BAD_REQUEST, "That remote control session is not open.")
        }
        watching = false
        clear()
        return SessionDecision.done(RemoteControl.STOPPED)
    }

    fun stop(session: String? = null): SessionDecision = synchronized(gate) {
        if (session != null && sessionId != null && session != sessionId) {
            return SessionDecision.fail(ErrorCodes.BAD_REQUEST, "That remote control session is not open.")
        }
        val wasActive = state != RemoteSessionState.IDLE
        clear()
        return if (wasActive) SessionDecision.done(RemoteControl.STOPPED)
        else SessionDecision.done(RemoteControl.STOPPED)
    }

    /** Controller-side bookkeeping after the other device already allowed. */
    fun openLocal(session: String, from: String): SessionDecision = synchronized(gate) {
        if (!paired) return SessionDecision.fail(ErrorCodes.UNKNOWN_PEER, RemoteControl.NEED_PAIR)
        if (!isSessionId(session)) return SessionDecision.fail(ErrorCodes.BAD_REQUEST, "That session is not valid.")
        if (from != RemoteControl.FROM_PC && from != RemoteControl.FROM_PHONE) {
            return SessionDecision.fail(ErrorCodes.BAD_REQUEST, "Who is controlling is missing.")
        }
        sessionId = session
        this.from = from
        state = RemoteSessionState.ACTIVE
        resetLimiter()
        return SessionDecision.done(RemoteControl.ALLOWED)
    }

    fun acceptInput(session: String, nowMs: Long = clock()): Boolean = synchronized(gate) {
        if (!paired) return false
        if (state != RemoteSessionState.ACTIVE || sessionId != session) return false
        if (from != RemoteControl.FROM_PHONE && !enabled) return false
        if (nowMs < lastInputMs) {
            lastInputMs = nowMs
            windowStartMs = nowMs
            windowCount = 0
        }
        if (nowMs - lastInputMs < RemoteControl.MIN_INPUT_GAP_MS) return false
        if (nowMs - windowStartMs >= 1000L) {
            windowStartMs = nowMs
            windowCount = 0
        }
        if (windowCount >= RemoteControl.MAX_INPUTS_PER_SECOND) return false
        lastInputMs = nowMs
        windowCount++
        return true
    }

    fun isActive(session: String? = null): Boolean = synchronized(gate) {
        state == RemoteSessionState.ACTIVE && (session == null || session == sessionId)
    }

    fun reset() = synchronized(gate) { clear() }

    private fun clear() {
        state = RemoteSessionState.IDLE
        sessionId = null
        from = null
        watching = false
        resetLimiter()
    }

    private fun resetLimiter() {
        lastInputMs = 0L
        windowStartMs = 0L
        windowCount = 0
    }

    private fun isSessionId(value: String): Boolean = runCatching { java.util.UUID.fromString(value) }.isSuccess
}
