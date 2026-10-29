package org.stellartimelock.send.core

import java.io.File

/**
 * Older copies of the same app: keep the newest APK, delete the rest from the
 * received list and from disk.
 */
object ApkLeftovers {
    data class DiskApk(val file: File, val packageName: String, val versionCode: Long)

    /** Log rows that are not the newest APK for their package. */
    fun olderLogEntries(entries: List<ReceivedEntry>): List<ReceivedEntry> {
        return entries
            .filter { it.packageName.isNotBlank() && ApkBatch.isApk(it.kind, it.name) }
            .groupBy { it.packageName }
            .flatMap { (_, group) ->
                if (group.size <= 1) emptyList()
                else {
                    val best = group.maxWith(
                        compareBy<ReceivedEntry> { it.incomingCode }.thenBy { it.receivedAtMillis }
                    )
                    group.filter { it.id != best.id }
                }
            }
    }

    /**
     * APK files on disk that are older copies of a package that already has a
     * newer file. Unknown files are left alone.
     */
    fun orphanFiles(
        files: List<File>,
        kept: List<ReceivedEntry>,
        identityOf: (File) -> Pair<String, Long>?
    ): List<File> {
        val identified = files.mapNotNull { file ->
            if (!file.isFile || !ApkReplacePreview.isApk(file.name)) return@mapNotNull null
            val fromLog = kept.find { it.name == file.name && it.packageName.isNotBlank() }
            val ident = if (fromLog != null) {
                fromLog.packageName to fromLog.incomingCode
            } else {
                identityOf(file)
            } ?: return@mapNotNull null
            DiskApk(file, ident.first, ident.second)
        }
        val drop = LinkedHashSet<File>()
        identified.groupBy { it.packageName }.forEach { (_, group) ->
            if (group.size <= 1) return@forEach
            val best = group.maxWith(
                compareBy<DiskApk> { it.versionCode }.thenBy { it.file.lastModified() }
            )
            drop += group.filter { it.file != best.file }.map { it.file }
        }
        return drop.toList()
    }
}
