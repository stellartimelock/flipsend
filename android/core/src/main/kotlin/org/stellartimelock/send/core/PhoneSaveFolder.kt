package org.stellartimelock.send.core

import java.io.File

/**
 * Phone save layout as of 2026-10-06: one top-level folder in main storage,
 * then one subfolder per app. FlipSend is not an exception.
 *
 *   StellarTimeLock backups/FlipSend/
 *
 * New files are never created in the older loose folders.
 */
object PhoneSaveFolder {
    const val ROOT_NAME = "StellarTimeLock backups"
    const val APP_NAME = "FlipSend"
    const val LABEL = "StellarTimeLock backups/FlipSend"

    private val MEDIASTORE_ROOTS = setOf(
        "Download",
        "Downloads",
        "Documents",
        "DCIM",
        "Pictures",
        "Movies",
        "Music",
        "Alarms",
        "Ringtones",
        "Notifications",
        "Podcasts",
        "Audiobooks",
        "Recordings"
    )

    val OLD_RELATIVE = listOf(
        "FlipSend",
        "StellarSend",
        "Download/StellarSend",
        "Download/FlipSend",
        "StellarTimeLock/FlipSend",
        "Documents/Stellar",
        "Stellar"
    )

    fun canonical(storageRoot: File): File = File(File(storageRoot, ROOT_NAME), APP_NAME)

    fun oldCandidates(storageRoot: File): List<File> =
        OLD_RELATIVE.map { relative(storageRoot, it) }

    fun findExistingOld(storageRoot: File): File? =
        oldCandidates(storageRoot).firstOrNull { it.isDirectory }

    fun isInsideAppFolder(file: File, storageRoot: File): Boolean {
        val root = runCatching { canonical(storageRoot).canonicalFile }.getOrNull() ?: return false
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return target == root || target.path.startsWith(root.path + File.separator)
    }

    /**
     * If the picker landed on the backups root or a parent, use the FlipSend
     * subfolder. Otherwise keep the folder the user pointed at.
     */
    fun normalizePicked(dir: File): File {
        if (dir.name == APP_NAME && dir.parentFile?.name == ROOT_NAME) return dir
        if (dir.name == ROOT_NAME) return File(dir, APP_NAME)
        val nested = File(File(dir, ROOT_NAME), APP_NAME)
        if (nested.isDirectory) return nested
        val child = File(dir, APP_NAME)
        if (child.isDirectory) return child
        return dir
    }

    /**
     * Prompt once when FlipSend cannot write the export folder and does not
     * yet have a persisted SAF tree. A MediaProvider-owned folder that exists
     * but rejects creates still needs the one-time pick.
     */
    fun needsExportPrompt(
        alreadyPrompted: Boolean,
        hasPersistedTree: Boolean,
        fileExportWritable: Boolean
    ): Boolean = !alreadyPrompted && !hasPersistedTree && !fileExportWritable

    /**
     * Android 15 MediaStore will not create a top-level non-standard folder
     * such as StellarTimeLock backups/. Only well-known collection roots
     * (Download, Documents, DCIM, …) accept RELATIVE_PATH without All files
     * access.
     */
    fun mediaStoreRelativePathAllowed(relativePath: String): Boolean {
        val root = relativePath.trim().trimStart('/').substringBefore('/')
        return root in MEDIASTORE_ROOTS
    }

    /**
     * Extra SAF folders to create under the picked tree so the copy lands in
     * StellarTimeLock backups/FlipSend. Empty when the user already picked
     * that FlipSend folder.
     */
    fun treeChildSegments(pickedName: String?, pickedParentName: String?): List<String> {
        val name = pickedName?.trim().orEmpty()
        val parent = pickedParentName?.trim().orEmpty()
        if (name.equals(APP_NAME, ignoreCase = true) &&
            (parent.equals(ROOT_NAME, ignoreCase = true) || parent.isEmpty())
        ) {
            return emptyList()
        }
        if (name.equals(ROOT_NAME, ignoreCase = true)) return listOf(APP_NAME)
        return listOf(ROOT_NAME, APP_NAME)
    }

    fun ensureWritable(dir: File): Boolean {
        val ready = dir.isDirectory || runCatching { dir.mkdirs() }.getOrDefault(false)
        if (!ready || !dir.isDirectory || !dir.canWrite()) return false
        val probe = File(dir, ".flipsend-write-${System.nanoTime()}")
        return try {
            probe.outputStream().use { it.write(1) }
            true
        } catch (_: Exception) {
            false
        } finally {
            probe.delete()
        }
    }

    private fun relative(root: File, path: String): File =
        path.split('/').fold(root) { acc, part -> File(acc, part) }
}
