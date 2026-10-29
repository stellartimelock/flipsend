package org.stellartimelock.send.core

object LogRedaction {
    fun redact(message: String?): String {
        if (message.isNullOrEmpty()) return message.orEmpty()
        val lower = message.lowercase()
        if ("stellarsend://" in lower || "\"pk\"" in lower || "\"password\"" in lower || "\"seed\"" in lower || "\"mnemonic\"" in lower || "private key" in lower || "begin private" in lower || "begin certificate" in lower || "purchasetoken" in lower || "private_key" in lower) {
            return "[redacted]"
        }
        return message
    }
}

object ProgressFormat {
    fun bytes(value: Long): String {
        var amount = if (value < 0) 0 else value
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var unit = 0
        var scaled = amount.toDouble()
        while (scaled >= 1024 && unit < units.size - 1) {
            scaled /= 1024
            unit++
        }
        if (unit == 0) return "$amount B"
        val text = if (scaled >= 10) scaled.toInt().toString() else String.format(java.util.Locale.US, "%.1f", scaled).trimEnd('0').trimEnd('.')
        return "$text ${units[unit]}"
    }

    fun line(done: Long, total: Long, bytesPerSecond: Double?, secondsRemaining: Double?, state: TransferState, detail: String?): String {
        return when (state) {
            TransferState.SUCCEEDED -> "Sent"
            TransferState.CANCELLED -> "Cancelled"
            TransferState.FAILED -> if (detail.isNullOrBlank()) "Failed" else detail
            TransferState.AWAITING_CONSENT -> "Waiting for approval"
            TransferState.IN_PROGRESS -> active(done, total, bytesPerSecond, secondsRemaining)
        }
    }

    private fun active(done: Long, total: Long, bytesPerSecond: Double?, secondsRemaining: Double?): String {
        val text = StringBuilder("${bytes(done)} / ${bytes(total)}")
        if (bytesPerSecond != null && bytesPerSecond > 1) {
            text.append(" · ${bytes(bytesPerSecond.toLong())}/s")
        }
        if (secondsRemaining != null && secondsRemaining >= 0 && secondsRemaining < 86_400) {
            val seconds = kotlin.math.round(secondsRemaining).toInt()
            text.append(if (seconds < 60) " · $seconds s left" else " · ${seconds / 60} min left")
        }
        return text.toString()
    }
}
