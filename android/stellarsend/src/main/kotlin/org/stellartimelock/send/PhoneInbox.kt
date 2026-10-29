package org.stellartimelock.send

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.stellartimelock.send.core.ContentTypes
import org.stellartimelock.send.core.FileNames
import org.stellartimelock.send.core.PhoneSaveFolder
import java.io.File

/**
 * Receives into app-private filesDir/inbox. After a successful receive,
 * FlipSend copies the file to StellarTimeLock backups/FlipSend/ through a
 * persisted SAF tree (DocumentFile). Android 15 MediaStore will not create
 * that top-level folder. The transfer itself never waits on the picker.
 */
object PhoneInbox {
    private const val PREFS = "stellarsend"
    private const val PATH_KEY = "phone_save_path"
    private const val PROMPTED_KEY = "phone_save_folder_prompted"
    private const val EXPORT_TREE_PROMPTED_KEY = "phone_export_tree_prompted"

    fun label(): String = PhoneSaveFolder.LABEL

    fun directory(context: Context): File {
        val inbox = File(context.applicationContext.filesDir, "inbox")
        inbox.mkdirs()
        return inbox
    }

    fun lookupDirs(context: Context): List<File> {
        val app = context.applicationContext
        val dirs = linkedSetOf<File>()
        dirs += directory(app)
        dirs += File(app.filesDir, "inbox")
        storageRoot()?.let { root ->
            dirs += PhoneSaveFolder.canonical(root)
            dirs += PhoneSaveFolder.oldCandidates(root)
        }
        rememberedFile(app)?.let { dirs += it }
        return dirs.mapNotNull { dir ->
            val ready = dir.isDirectory
            if (ready) runCatching { dir.canonicalFile }.getOrNull() else null
        }.distinct()
    }

    fun sweepDirs(context: Context): List<File> {
        val app = context.applicationContext
        val dirs = linkedSetOf<File>()
        dirs += directory(app)
        dirs += File(app.filesDir, "inbox")
        return dirs.mapNotNull { dir ->
            if (dir.isDirectory) runCatching { dir.canonicalFile }.getOrNull() else null
        }.distinct()
    }

    fun needsPrompt(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PhoneSaveFolder.needsExportPrompt(
            alreadyPrompted = prefs.getBoolean(EXPORT_TREE_PROMPTED_KEY, false),
            hasPersistedTree = !InboxFolder(app).tree().isNullOrBlank(),
            fileExportWritable = exportDirectory(app)?.let { PhoneSaveFolder.ensureWritable(it) } == true
        )
    }

    fun markPrompted(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PROMPTED_KEY, true)
            .putBoolean(EXPORT_TREE_PROMPTED_KEY, true)
            .apply()
    }

    fun acceptPickedTree(context: Context, uri: Uri) {
        val app = context.applicationContext
        markPrompted(app)
        InboxFolder(app).set(uri.toString(), confirmed = true)
        fileFromTreeUri(uri)?.let { picked ->
            val dest = PhoneSaveFolder.normalizePicked(picked)
            runCatching { dest.mkdirs() }
            rememberPath(app, dest)
        }
        exportPending(app)
    }

    fun exportTree(context: Context): String? = InboxFolder(context).tree()

    fun maybeExport(context: Context, source: File): Boolean {
        if (!source.exists()) return false
        val storage = storageRoot()
        if (storage != null && PhoneSaveFolder.isInsideAppFolder(source, storage)) return true
        if (exportViaFolder(context, source)) return true
        if (exportViaTree(context, source)) return true
        if (PhoneSaveFolder.mediaStoreRelativePathAllowed(PhoneSaveFolder.LABEL)) {
            return exportViaMediaStore(context, source)
        }
        return false
    }

    fun exportPending(context: Context) {
        val inbox = directory(context)
        inbox.listFiles()?.forEach { file ->
            if (file.name.startsWith(".")) return@forEach
            maybeExport(context, file)
        }
    }

    private fun exportViaTree(context: Context, source: File): Boolean {
        val tree = exportTree(context) ?: return false
        return ChosenFolder.export(context, tree, source)
    }

    private fun exportViaFolder(context: Context, source: File): Boolean {
        val destDir = exportDirectory(context) ?: return false
        if (!PhoneSaveFolder.ensureWritable(destDir)) return false
        return runCatching {
            if (source.isDirectory) {
                val dest = FileNames.allocate(destDir, source.name)
                source.copyRecursively(dest, overwrite = false)
                dest.isDirectory
            } else {
                val dest = FileNames.allocate(destDir, source.name)
                source.copyTo(dest, overwrite = false)
                dest.isFile
            }
        }.getOrDefault(false)
    }

    private fun exportViaMediaStore(context: Context, source: File): Boolean {
        if (Build.VERSION.SDK_INT < 29 || source.isDirectory) return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FileNames.sanitize(source.name))
            put(MediaStore.MediaColumns.MIME_TYPE, ContentTypes.of(source.name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, PhoneSaveFolder.LABEL)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: return false
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    private fun exportDirectory(context: Context): File? {
        rememberedFile(context)?.takeIf { it.exists() }?.let { return it }
        return storageRoot()?.let { PhoneSaveFolder.canonical(it) }
    }

    fun fileFromTreeUri(uri: Uri): File? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val split = id.split(":", limit = 2)
        if (split.size != 2) return null
        if (!split[0].equals("primary", ignoreCase = true)) return null
        val root = storageRoot() ?: return null
        return split[1].split('/').fold(root) { acc, part -> File(acc, part) }
    }

    private fun rememberedFile(context: Context): File? {
        val path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PATH_KEY, null)
            ?.trim()
            ?.ifBlank { null }
            ?: return null
        return File(path)
    }

    private fun rememberPath(context: Context, dir: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PATH_KEY, dir.absolutePath)
            .putBoolean(PROMPTED_KEY, true)
            .apply()
    }

    private fun storageRoot(): File? =
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
}
