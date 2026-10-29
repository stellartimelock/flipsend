package org.stellartimelock.send.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class SaveBlockedException(
    message: String = "Refusing to replace a save file with an empty or much smaller one."
) : IOException(message)

object DurableFile {
    const val BACKUP_COUNT = 5
    const val MIN_GUARDED_BYTES = 32L

    fun wouldReplaceWithMuchSmaller(existing: Long, incoming: Long): Boolean {
        if (existing <= 0) return false
        if (incoming <= 0) return true
        if (existing < MIN_GUARDED_BYTES) return false
        return incoming * 2 < existing
    }

    fun write(file: File, content: ByteArray, allowShrink: Boolean): Boolean {
        file.parentFile?.mkdirs()
        if (file.exists()) {
            if (!allowShrink && wouldReplaceWithMuchSmaller(file.length(), content.size.toLong())) {
                return false
            }
            shiftBackups(file)
            file.copyTo(backup(file, 1), overwrite = true)
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(content)
            out.fd.sync()
        }
        if (file.exists() && !file.delete()) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
            return true
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        return true
    }

    fun writeOrThrow(file: File, content: ByteArray, allowShrink: Boolean = false) {
        if (!write(file, content, allowShrink)) throw SaveBlockedException()
    }

    fun deleteAll(file: File) {
        if (file.exists()) file.delete()
        for (index in 1..BACKUP_COUNT) backup(file, index).delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    fun backup(file: File, index: Int) = File(file.parentFile, file.name + ".bak." + index)

    private fun shiftBackups(file: File) {
        for (index in BACKUP_COUNT downTo 2) {
            val source = backup(file, index - 1)
            val destination = backup(file, index)
            if (!source.exists()) continue
            if (destination.exists()) destination.delete()
            source.renameTo(destination)
        }
    }
}

object UsDate {
    fun format(epochMillis: Long): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = epochMillis
        return String.format(
            java.util.Locale.US,
            "%02d/%02d/%04d",
            calendar.get(java.util.Calendar.MONTH) + 1,
            calendar.get(java.util.Calendar.DAY_OF_MONTH),
            calendar.get(java.util.Calendar.YEAR)
        )
    }
}

object ShortCode {
    fun format(fingerprint: String): String {
        if (!isFingerprint(fingerprint)) throw ProtocolException("Invalid fingerprint.")
        return fingerprint.substring(0, 4).uppercase() + "-" + fingerprint.substring(4, 8).uppercase()
    }

    fun looksLike(typed: String?): Boolean {
        val compact = compact(typed)
        return compact.length == 8 && compact.all { it in '0'..'9' || it in 'a'..'f' }
    }

    fun matches(typed: String?, fingerprint: String): Boolean {
        if (!isFingerprint(fingerprint)) return false
        val compact = compact(typed)
        return compact.length == 8 && fingerprint.startsWith(compact)
    }

    fun same(left: String?, right: String?): Boolean {
        val a = compact(left)
        val b = compact(right)
        return a.length == 8 && a == b
    }

    private fun compact(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.trim().filter { it != '-' && it != ' ' }.lowercase()
    }
}

object SaveFileCheck {
    fun problem(files: List<File>): String? {
        for (file in files) {
            if (!file.exists()) continue
            if (file.length() == 0L) {
                return "${file.name} is empty. The app was not replaced."
            }
            val backup = DurableFile.backup(file, 1)
            if (backup.exists() && DurableFile.wouldReplaceWithMuchSmaller(backup.length(), file.length())) {
                return "${file.name} is much smaller than its last backup. The app was not replaced."
            }
        }
        return null
    }
}
