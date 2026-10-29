package org.stellartimelock.send.core

/**
 * Caps the iPhone/browser path actually enforces. The 50 GB figure is the
 * shared free pool, not one person's allowance.
 */
object TransferCap {
    const val FILE_MEMORY_BYTES = 48L * 1024L * 1024L
    const val FREE_MONTH_BYTES = 100L * 1024L * 1024L
    const val ELITE_MONTH_BYTES = 1_000L * 1024L * 1024L
    const val FREE_RATE_BYTES_PER_MIN = 5L * 1024L * 1024L
    const val SHARED_POOL_BYTES = 50L * 1000L * 1000L * 1000L

    const val FILE_MEMORY_LABEL = "48 MB per file in this browser"
    const val FREE_MONTH_LABEL = "100 MB of relay this month"
    const val FREE_RATE_LABEL = "5 MB per minute on the free relay"
    const val RESETS = "The monthly relay allowance resets on the 1st (UTC)."
    const val RAISE = "Use the same Wi-Fi to go past the relay cap. The per-file browser cap stays 48 MB."

    enum class Kind { FILE_MEMORY, MONTH, RATE, POOL }

    fun personalLimit(reported: Long): Long {
        if (reported <= 0) return FREE_MONTH_BYTES
        if (reported >= 10L * 1024L * 1024L * 1024L) return FREE_MONTH_BYTES
        return reported
    }

    fun hit(kind: Kind): String = when (kind) {
        Kind.FILE_MEMORY ->
            "This file is over $FILE_MEMORY_LABEL. Send a smaller file, or use FlipSend on Android and Windows on the same Wi-Fi."
        Kind.MONTH ->
            "Hit $FREE_MONTH_LABEL. $RESETS $RAISE"
        Kind.RATE ->
            "Hit $FREE_RATE_LABEL. Wait a minute, or use the same Wi-Fi."
        Kind.POOL ->
            "The shared free relay is busy. $RAISE"
    }

    fun classify(message: String?): Kind? {
        val text = message?.lowercase().orEmpty()
        if (text.contains("48 mb") || text.contains("in memory") || text.contains("that large in memory")) {
            return Kind.FILE_MEMORY
        }
        if (text.contains("per minute") || text.contains("5 mb")) return Kind.RATE
        if (text.contains("busy")) return Kind.POOL
        if (text.contains("used up") || text.contains("this month") || text.contains("quota")) return Kind.MONTH
        return null
    }
}
