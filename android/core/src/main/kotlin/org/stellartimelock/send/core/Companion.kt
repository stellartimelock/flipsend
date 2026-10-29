package org.stellartimelock.send.core

import org.json.JSONObject
import java.net.URI

object CompanionServices {
    const val VAULT_CLIPBOARD = "vault.clipboard"
    const val REMOTE_BROWSER = "remote.browser"
    const val TERMINAL = "terminal"
    const val SCREEN_SHARE = "screen.share"
    const val REMOTE_CONTROL = "remote.control"
    const val INSTALL_RESULT = "install.result"
    const val CLEAR_AFTER_MS = 45_000L
    const val MAX_PASSWORD_CHARS = 1024
    const val MAX_URL_CHARS = 2048
    const val MAX_FRAME_BYTES = 200_000
}

data class ServiceRequest(val id: String, val service: String, val op: String, val body: JSONObject) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("service", service)
        .put("op", op)
        .put("body", body)
}

data class ServiceResult(val id: String, val ok: Boolean, val code: String, val message: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("ok", ok)
        .put("code", code)
        .put("message", message)

    companion object {
        fun done(id: String, message: String) = ServiceResult(id, true, "ok", message)
        fun fail(id: String, code: String, message: String) = ServiceResult(id, false, code, message)
    }
}

object ServiceCodec {
    fun parseRequest(payload: ByteArray): ServiceRequest {
        val root = try {
            JSONObject(payload.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw ProtocolException("Unreadable companion call.")
        }
        val id = required(root, "id")
        val service = required(root, "service")
        val op = required(root, "op")
        if (!isUuid(id) || !isToken(service, 64) || !isToken(op, 32)) {
            throw ProtocolException("Companion call is invalid.")
        }
        val body = root.optJSONObject("body") ?: throw ProtocolException("Companion call is missing a body.")
        return ServiceRequest(id, service, op, body)
    }

    fun parseResult(payload: ByteArray): ServiceResult {
        val json = try {
            JSONObject(payload.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw ProtocolException("Unreadable companion result.")
        }
        return ServiceResult(json.optString("id"), json.optBoolean("ok"), json.optString("code"), json.optString("message"))
    }

    fun isToken(value: String, max: Int): Boolean {
        if (value.isEmpty() || value.length > max) return false
        return value.all { it in 'a'..'z' || it in '0'..'9' || it == '.' }
    }

    private fun required(root: JSONObject, name: String): String {
        if (!root.has(name) || root.isNull(name)) throw ProtocolException("Companion call is invalid.")
        return root.optString(name, "")
    }

    private fun isUuid(value: String): Boolean = runCatching { java.util.UUID.fromString(value) }.isSuccess
}

object ClipboardPolicy {
    fun rejectReason(password: String?): String? {
        if (password.isNullOrEmpty()) return "Password is empty."
        if (password.length > CompanionServices.MAX_PASSWORD_CHARS) return "Password is too long."
        return null
    }

    fun shouldClear(
        placedAtMs: Long,
        nowMs: Long,
        current: String?,
        secret: String,
        clearAfterMs: Long = CompanionServices.CLEAR_AFTER_MS
    ): Boolean {
        if (nowMs - placedAtMs < clearAfterMs) return false
        return current == secret
    }
}

class ClipboardHandoff(
    private val clipboard: SecretClipboard,
    private val notice: UserNotice
) {
    private var secret: String? = null
    private var placedAtMs: Long = 0
    private var clearAfterMs: Long = CompanionServices.CLEAR_AFTER_MS

    fun put(id: String, password: String?, nowMs: Long, clearAfterSeconds: Int? = null): ServiceResult {
        val error = ClipboardPolicy.rejectReason(password)
        if (error != null) return ServiceResult.fail(id, ErrorCodes.BAD_REQUEST, error)
        val seconds = CopyToPc.clampClearAfter(clearAfterSeconds)
        clipboard.setText(password!!)
        secret = password
        placedAtMs = nowMs
        clearAfterMs = seconds * 1000L
        val message = CopyToPc.copiedMessage(seconds)
        notice.show(message)
        return ServiceResult.done(id, message)
    }

    fun tick(nowMs: Long): Boolean {
        val currentSecret = secret ?: return false
        if (!ClipboardPolicy.shouldClear(placedAtMs, nowMs, clipboard.getText(), currentSecret, clearAfterMs)) {
            if (nowMs - placedAtMs >= clearAfterMs) secret = null
            return false
        }
        clipboard.setText("")
        secret = null
        return true
    }
}

interface SecretClipboard {
    fun setText(text: String)
    fun getText(): String?
}

interface UserNotice {
    fun show(text: String)
}

interface BrowserSurface {
    val canOpen: Boolean
    val canView: Boolean
    val canInput: Boolean
    fun open(url: String)
    fun capture(): ByteArray?
    fun input(kind: String, x: Double, y: Double, text: String?)
}

class UrlOnlyBrowserSurface : BrowserSurface {
    override val canOpen = true
    override val canView = false
    override val canInput = false
    var opened: String? = null
        private set

    override fun open(url: String) {
        opened = url
    }

    override fun capture(): ByteArray? = null

    override fun input(kind: String, x: Double, y: Double, text: String?) {
        throw IllegalStateException("This browser surface does not take input.")
    }
}

data class UrlCheck(val ok: Boolean, val absolute: String, val error: String)

object BrowserUrls {
    fun tryNormalize(raw: String?): UrlCheck {
        if (raw.isNullOrBlank() || raw.length > CompanionServices.MAX_URL_CHARS || raw.any { it.isISOControl() }) {
            return UrlCheck(false, "", "Enter an http or https link.")
        }
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
        if (uri == null || !uri.isAbsolute || (uri.scheme != "https" && uri.scheme != "http") || !uri.userInfo.isNullOrEmpty()) {
            return UrlCheck(false, "", "Only http and https links can be opened. Desktop control is not available.")
        }
        return UrlCheck(true, uri.toASCIIString(), "")
    }
}

data class RemotePluginInfo(
    val id: String,
    val title: String,
    val summary: String,
    val open: Boolean,
    val view: Boolean,
    val input: Boolean
)

class RemoteBrowserPlugin(private val surface: BrowserSurface) {
    fun describe() = RemotePluginInfo(
        CompanionServices.REMOTE_BROWSER,
        "Remote Browser",
        "Opens a link in a browser on this PC. Live view and typing stay inside that browser surface. The desktop is not controlled.",
        surface.canOpen,
        surface.canView,
        surface.canInput
    )

    fun handle(request: ServiceRequest): ServiceResult {
        return when (request.op) {
            "status" -> ServiceResult.done(request.id, statusLine())
            "open" -> open(request)
            "frame" -> frame(request)
            "input" -> input(request)
            else -> ServiceResult.fail(request.id, ErrorCodes.NOT_IMPLEMENTED, "That browser action is not available.")
        }
    }

    private fun open(request: ServiceRequest): ServiceResult {
        if (!surface.canOpen) return ServiceResult.fail(request.id, ErrorCodes.NOT_IMPLEMENTED, "Opening links is not available.")
        val check = BrowserUrls.tryNormalize(if (request.body.has("url")) request.body.optString("url", "") else "")
        if (!check.ok) return ServiceResult.fail(request.id, ErrorCodes.BAD_REQUEST, check.error)
        surface.open(check.absolute)
        return ServiceResult.done(request.id, "Opened on this PC.")
    }

    private fun frame(request: ServiceRequest): ServiceResult {
        if (!surface.canView) {
            return ServiceResult.fail(request.id, VIEW_UNAVAILABLE, "Live view is not available on this PC. Remote Browser can open a link, and it does not control the desktop.")
        }
        val bytes = surface.capture()
        if (bytes == null || bytes.isEmpty() || bytes.size > CompanionServices.MAX_FRAME_BYTES) {
            return ServiceResult.fail(request.id, ErrorCodes.LIMIT, "The browser view was empty or too large.")
        }
        return ServiceResult.done(request.id, "Browser view updated.")
    }

    private fun input(request: ServiceRequest): ServiceResult {
        if (!surface.canInput) {
            return ServiceResult.fail(request.id, INPUT_UNAVAILABLE, "Typing and taps are not available until a browser surface is attached. Desktop control is not supported.")
        }
        val kind = request.body.optString("kind", "")
        if (kind != "tap" && kind != "text") {
            return ServiceResult.fail(request.id, ErrorCodes.BAD_REQUEST, "Browser input must be a tap or text.")
        }
        val text = if (request.body.has("text") && !request.body.isNull("text")) request.body.optString("text") else null
        if (kind == "text" && (text.isNullOrEmpty() || text.length > 512)) {
            return ServiceResult.fail(request.id, ErrorCodes.BAD_REQUEST, "Type a short string.")
        }
        surface.input(kind, number(request.body, "x"), number(request.body, "y"), text)
        return ServiceResult.done(request.id, "Sent to the browser.")
    }

    private fun statusLine(): String =
        "Open links: ${on(surface.canOpen)}. Live view: ${on(surface.canView)}. Typing: ${on(surface.canInput)}."

    private fun on(value: Boolean) = if (value) "on" else "off"

    private fun number(body: JSONObject, name: String): Double {
        if (!body.has(name)) return 0.0
        val number = body.optDouble(name, 0.0)
        if (number < 0 || number > 1) return 0.0
        return number
    }

    companion object {
        const val VIEW_UNAVAILABLE = "browser_view_unavailable"
        const val INPUT_UNAVAILABLE = "browser_input_unavailable"
    }
}

class CompanionHost(
    clipboard: SecretClipboard,
    notice: UserNotice,
    browser: BrowserSurface,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val clipboardHandoff = ClipboardHandoff(clipboard, notice)
    private val browserPlugin = RemoteBrowserPlugin(browser)

    fun plugins(): List<RemotePluginInfo> = listOf(browserPlugin.describe())

    fun handle(request: ServiceRequest): ServiceResult {
        return when {
            request.service == CompanionServices.VAULT_CLIPBOARD && request.op == "put" ->
                clipboardHandoff.put(
                    request.id,
                    stringOrNull(request.body, "password"),
                    clock(),
                    intOrNull(request.body, "clearAfterSeconds")
                )
            request.service == CompanionServices.VAULT_CLIPBOARD ->
                ServiceResult.fail(request.id, ErrorCodes.NOT_IMPLEMENTED, "That Vault action is not available.")
            request.service == CompanionServices.REMOTE_BROWSER -> browserPlugin.handle(request)
            else -> ServiceResult.fail(request.id, ErrorCodes.NOT_IMPLEMENTED, "That companion feature is not available.")
        }
    }

    fun tick(): Boolean = clipboardHandoff.tick(clock())

    private fun stringOrNull(body: JSONObject, name: String): String? {
        if (!body.has(name) || body.isNull(name)) return null
        return body.optString(name)
    }

    private fun intOrNull(body: JSONObject, name: String): Int? {
        if (!body.has(name) || body.isNull(name)) return null
        val raw = body.opt(name)
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }
}
