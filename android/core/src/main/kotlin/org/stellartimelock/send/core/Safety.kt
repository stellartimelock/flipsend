package org.stellartimelock.send.core

import java.io.File
import java.util.Locale

object FileNames {
    private val reserved = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return "file"
        val normalized = raw.replace('\\', '/')
        val slash = normalized.lastIndexOf('/')
        var name = if (slash >= 0) normalized.substring(slash + 1) else normalized
        if (name == "." || name == "..") return "file"
        name = name.map { ch ->
            if (ch.code < 32 || ch in "<>:\"/\\|?*") '_' else ch
        }.joinToString("").trim().trimEnd('.', ' ')
        if (name.isEmpty()) return "file"
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        if (reserved.contains(stem.uppercase(Locale.US))) name = "_$name"
        if (name.length > ProtocolLimits.MAX_NAME_CHARS) {
            val extensionIndex = name.lastIndexOf('.')
            var extension = if (extensionIndex > 0) name.substring(extensionIndex) else ""
            if (extension.length > 20) extension = ""
            name = name.take(ProtocolLimits.MAX_NAME_CHARS - extension.length) + extension
        }
        return name
    }

    fun allocate(directory: File, rawName: String): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw java.io.IOException("Could not create the destination folder.")
        }
        val root = directory.canonicalFile
        val safe = sanitize(rawName)
        for (index in 0 until 1000) {
            val fileName = if (index == 0) safe else insertSuffix(safe, " ($index)")
            val candidate = File(root, fileName).canonicalFile
            val prefix = root.path + File.separator
            if (!candidate.path.startsWith(prefix) && candidate.path != root.path) {
                throw java.io.IOException("Refusing to write outside the destination folder.")
            }
            if (!candidate.exists()) return candidate
        }
        throw java.io.IOException("Too many files with that name.")
    }

    fun exact(directory: File, rawName: String): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw java.io.IOException("Could not create the destination folder.")
        }
        val root = directory.canonicalFile
        val candidate = File(root, sanitize(rawName)).canonicalFile
        val prefix = root.path + File.separator
        if (!candidate.path.startsWith(prefix) && candidate.path != root.path) {
            throw java.io.IOException("Refusing to write outside the destination folder.")
        }
        return candidate
    }

    private fun insertSuffix(name: String, suffix: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name + suffix
        return name.substring(0, dot) + suffix + name.substring(dot)
    }
}

enum class PayloadKind { FILE, TEXT }

object MethodIds {
    const val WIFI = "wifi-pc"
    const val USB = "usb"
    const val MEMO = "stellar-memo"
    const val GMAIL = "gmail"
    const val SMS = "sms"
    const val TEXTPIPE = "textpipe"
    const val SHARE = "share-sheet"
}

data class SendMethod(
    val id: String,
    val title: String,
    val kinds: Set<PayloadKind>,
    val requiredPackage: String? = null
)

object SendMethodRegistry {
    const val GMAIL_PACKAGE = "com.google.android.gm"
    const val TEXTPIPE_PACKAGE = "app.textpipe"

    val defaults = listOf(
        SendMethod(MethodIds.WIFI, "To my PC (Wi-Fi)", setOf(PayloadKind.FILE, PayloadKind.TEXT)),
        SendMethod(MethodIds.USB, "USB", setOf(PayloadKind.FILE, PayloadKind.TEXT)),
        SendMethod(MethodIds.MEMO, "Stellar memo (text only)", setOf(PayloadKind.TEXT)),
        SendMethod(MethodIds.GMAIL, "Gmail", setOf(PayloadKind.FILE, PayloadKind.TEXT), GMAIL_PACKAGE),
        SendMethod(MethodIds.SMS, "Text message", setOf(PayloadKind.FILE, PayloadKind.TEXT)),
        SendMethod(MethodIds.TEXTPIPE, "AI Text Manager", setOf(PayloadKind.FILE, PayloadKind.TEXT), TEXTPIPE_PACKAGE),
        SendMethod(MethodIds.SHARE, "Share sheet", setOf(PayloadKind.FILE, PayloadKind.TEXT))
    )

    fun available(kind: PayloadKind, installedPackages: Set<String>, smsAvailable: Boolean, methods: List<SendMethod> = defaults): List<SendMethod> {
        return methods.filter { method ->
            kind in method.kinds && when (method.id) {
                MethodIds.SMS -> smsAvailable
                MethodIds.GMAIL -> GMAIL_PACKAGE in installedPackages
                MethodIds.TEXTPIPE -> TEXTPIPE_PACKAGE in installedPackages
                else -> method.requiredPackage == null || method.requiredPackage in installedPackages
            }
        }
    }
}

sealed class MemoResult {
    data object NotImplemented : MemoResult()
    data class Rejected(val message: String) : MemoResult()
}

interface StellarMemo {
    fun sendText(text: String): MemoResult
}

class StellarMemoStub : StellarMemo {
    override fun sendText(text: String): MemoResult {
        if (text.isEmpty()) return MemoResult.Rejected("Enter text first.")
        if (text.toByteArray(Charsets.UTF_8).size > ProtocolLimits.MAX_TEXT_BYTES) {
            return MemoResult.Rejected("Text is too long for a memo.")
        }
        return MemoResult.NotImplemented
    }
}

fun memoForFile(): MemoResult = MemoResult.Rejected("Stellar memo accepts text only.")

const val MEMO_NOT_IMPLEMENTED =
    "Send via Stellar memo is not available in this version. No key, network, or time-lock operation was performed."

data class OutboundIntent(
    val action: String,
    val packageName: String?,
    val mimeType: String,
    val chooser: Boolean,
    val grantRead: Boolean,
    val textBody: Boolean
)

object ShareRequests {
    fun gmail(file: Boolean) = OutboundIntent(
        "android.intent.action.SEND",
        SendMethodRegistry.GMAIL_PACKAGE,
        if (file) "*/*" else "text/plain",
        chooser = false,
        grantRead = file,
        textBody = !file
    )

    fun sms(defaultPackage: String?, file: Boolean) = OutboundIntent(
        "android.intent.action.SEND",
        defaultPackage,
        if (file) "*/*" else "text/plain",
        chooser = false,
        grantRead = file,
        textBody = true
    )

    fun textPipe(file: Boolean) = OutboundIntent(
        "android.intent.action.SEND",
        SendMethodRegistry.TEXTPIPE_PACKAGE,
        if (file) "*/*" else "text/plain",
        chooser = false,
        grantRead = file,
        textBody = true
    )

    fun shareSheet(file: Boolean) = OutboundIntent(
        "android.intent.action.SEND",
        null,
        if (file) "*/*" else "text/plain",
        chooser = true,
        grantRead = file,
        textBody = true
    )

    fun gmailInstalled(packages: Set<String>) = SendMethodRegistry.GMAIL_PACKAGE in packages
}

object RetryPolicy {
    const val MAX_ATTEMPTS = 5

    fun shouldRetry(remoteCompleted: Boolean, cancelled: Boolean, attempt: Int, transientFailure: Boolean) =
        !remoteCompleted && !cancelled && attempt < MAX_ATTEMPTS && transientFailure

    fun delayMs(failedAttempt: Int): Long {
        val shift = (failedAttempt - 1).coerceIn(0, 4)
        return 400L shl shift
    }
}
