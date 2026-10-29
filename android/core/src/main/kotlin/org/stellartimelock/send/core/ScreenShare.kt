package org.stellartimelock.send.core

/** Phone-to-PC live screen frames. JPEG only, one frame per SCREEN_FRAME. */
object ScreenShare {
    const val MAX_JPEG_BYTES = CompanionServices.MAX_FRAME_BYTES
    const val MAX_WIDTH = 720
    const val INTERVAL_MS = 200L
    const val STARTED = "Showing this phone on the PC."
    const val STOPPED = "Screen share stopped."
    const val NEED_PAIR = "Pair a computer before sharing this screen."

    fun acceptJpeg(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 2 || bytes.size > MAX_JPEG_BYTES) return false
        return bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    }
}
