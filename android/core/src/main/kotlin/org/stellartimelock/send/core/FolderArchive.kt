package org.stellartimelock.send.core

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object FolderArchive {
    const val MIME = "application/x-stellarsend-folder"
    const val SUFFIX = ".stellarsend-folder.zip"
    const val MARKER = "stellarsend-folder.txt"

    fun isBundle(name: String?, mime: String? = null): Boolean {
        if (!mime.isNullOrBlank() && mime.equals(MIME, ignoreCase = true)) return true
        val fileName = name?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
        return fileName.endsWith(SUFFIX, ignoreCase = true)
    }

    fun pack(directory: File, destination: File) {
        if (!directory.isDirectory) throw java.io.IOException("Folder not found.")
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            zip.putNextEntry(ZipEntry(MARKER))
            zip.write(directory.name.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            val root = directory.canonicalFile
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = root.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
                if (relative.split('/').any { it == ".." }) return@forEach
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun unpack(zipPath: File, inbox: File): File {
        var folderName = zipPath.name
        if (folderName.endsWith(SUFFIX, ignoreCase = true)) {
            folderName = folderName.dropLast(SUFFIX.length)
        }
        folderName = FileNames.sanitize(folderName)
        inbox.mkdirs()
        var target = File(inbox, folderName)
        var index = 1
        while (target.exists()) {
            target = File(inbox, "$folderName ($index)")
            index++
        }
        target.mkdirs()
        ZipFile(zipPath).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.trimEnd('/') == MARKER) continue
                val destination = safeEntry(target, entry.name)
                if (entry.isDirectory) {
                    destination.mkdirs()
                    continue
                }
                destination.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                }
            }
        }
        return target
    }

    fun safeEntry(root: File, entryName: String): File {
        val normalized = entryName.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty() || normalized.split('/').any { it == ".." } || entryName.startsWith("/") || entryName.contains(":")) {
            throw java.io.IOException("Folder archive contains an unsafe path.")
        }
        val destination = File(root, normalized).canonicalFile
        val prefix = root.canonicalFile.path + File.separator
        if (!destination.path.startsWith(prefix) && destination.path != root.canonicalFile.path) {
            throw java.io.IOException("Folder archive contains an unsafe path.")
        }
        return destination
    }
}

object ResumeFiles {
    fun match(partial: File, sha256: String, total: Long): Long = match(partial, sha256, total, null)

    fun match(partial: File, sha256: String, total: Long, transferId: String?): Long {
        val meta = File(partial.parentFile, partial.name + ".meta")
        if (!partial.isFile || !meta.isFile) return 0
        return try {
            val json = org.json.JSONObject(meta.readText())
            val length = json.optLong("length")
            val savedId = json.optString("transferId")
            val savedSize = json.optLong("size")
            val sameId = savedId.isEmpty() || transferId.isNullOrEmpty() || savedId == transferId
            val sameSize = savedSize <= 0L || savedSize == total
            if (json.optString("sha256") == sha256 && sameId && sameSize && length > 0 && length < total && partial.length() == length) length else 0
        } catch (_: Exception) {
            0
        }
    }

    fun preserve(partial: File, sha256: String, length: Long) = preserve(partial, sha256, length, null, 0)

    fun preserve(partial: File, sha256: String, length: Long, transferId: String?, total: Long) {
        if (length <= 0) {
            discard(partial)
            return
        }
        val meta = File(partial.parentFile, partial.name + ".meta")
        val json = org.json.JSONObject().put("sha256", sha256).put("length", length)
        if (!transferId.isNullOrEmpty()) json.put("transferId", transferId)
        if (total > 0) json.put("size", total)
        DurableFile.write(meta, json.toString().toByteArray(), allowShrink = true)
    }

    fun discard(partial: File) {
        partial.delete()
        File(partial.parentFile, partial.name + ".meta").delete()
    }
}
