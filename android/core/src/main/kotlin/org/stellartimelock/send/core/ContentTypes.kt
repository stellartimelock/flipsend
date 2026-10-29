package org.stellartimelock.send.core

/** Real types for videos and other files so pickers and share sheets do not hide them. */
object ContentTypes {
    const val ANY = "*/*"
    const val BYTES = "application/octet-stream"
    val PICK = arrayOf("image/*", "video/*", ANY)

    fun of(name: String?, declared: String? = null): String {
        val given = declared?.trim().orEmpty()
        if (given.isNotEmpty() && !generic(given)) return given.take(127)
        return ofName(name) ?: given.ifBlank { BYTES }
    }

    fun ofName(name: String?): String? {
        val leaf = name.orEmpty().replace('\\', '/').substringAfterLast('/')
        val ext = leaf.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext == leaf.lowercase()) return null
        return when (ext) {
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "m4v" -> "video/x-m4v"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "3g2" -> "video/3gpp2"
            "mpeg", "mpg" -> "video/mpeg"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "txt", "text" -> "text/plain"
            else -> null
        }
    }

    fun generic(mime: String): Boolean {
        val value = mime.trim().lowercase()
        return value.isEmpty() || value == BYTES || value == ANY || value == "application/*"
    }
}
