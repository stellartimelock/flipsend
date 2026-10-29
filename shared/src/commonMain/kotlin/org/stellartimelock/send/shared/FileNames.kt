package org.stellartimelock.send.shared

/**
 * Cross-platform file name sanitisation.
 * No file-system access — pure string operations.
 * The set of reserved names matches Windows (worst-case platform) so
 * save files are portable between Android, iOS, and Windows desktops.
 */
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
        if (reserved.contains(stem.uppercase())) name = "_$name"
        if (name.length > ProtocolLimits.MAX_NAME_CHARS) {
            val extensionIndex = name.lastIndexOf('.')
            var ext = if (extensionIndex > 0) name.substring(extensionIndex) else ""
            if (ext.length > 20) ext = ""
            name = name.take(ProtocolLimits.MAX_NAME_CHARS - ext.length) + ext
        }
        return name
    }

    /**
     * Given a desired file name, return a numbered variant that does not
     * collide with [existingNames]. Up to 1000 attempts.
     */
    fun allocate(rawName: String, existingNames: Set<String>): String {
        val safe = sanitize(rawName)
        for (index in 0 until 1000) {
            val candidate = if (index == 0) safe else insertSuffix(safe, " ($index)")
            if (!existingNames.contains(candidate)) return candidate
        }
        throw IllegalStateException("Too many files with that name.")
    }

    private fun insertSuffix(name: String, suffix: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name + suffix
        return name.substring(0, dot) + suffix + name.substring(dot)
    }
}

/**
 * Folder layout for FlipSend saves.
 * Root: StellarTimeLock/FlipSend/
 * Sub-folders by extension category:
 *   images/  documents/  media/  apps/  other/
 */
object FolderLayout {
    const val ROOT = "StellarTimeLock"
    const val APP = "FlipSend"

    fun subfolder(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff" -> "images"
            "mp4", "mov", "avi", "mkv", "m4v", "mp3", "m4a", "aac", "flac", "wav", "ogg" -> "media"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv" -> "documents"
            "apk", "ipa" -> "apps"
            else -> "other"
        }
    }

    fun relativePath(fileName: String): String =
        "$ROOT/$APP/${subfolder(fileName)}/${FileNames.sanitize(fileName)}"
}
