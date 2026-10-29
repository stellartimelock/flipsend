package org.stellartimelock.send

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.stellartimelock.send.core.ContentTypes
import org.stellartimelock.send.core.FileNames
import org.stellartimelock.send.core.FolderArchive
import org.stellartimelock.send.core.PhoneSaveFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object ChosenFolder {
    fun export(context: Context, tree: String?, source: File): Boolean {
        if (tree.isNullOrBlank() || !source.exists()) return false
        return runCatching {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return false
            val folder = resolveFlipSend(root) ?: return false
            writeAny(context, folder, source)
        }.getOrDefault(false)
    }

    private fun resolveFlipSend(root: DocumentFile): DocumentFile? {
        val segments = PhoneSaveFolder.treeChildSegments(root.name, root.parentFile?.name)
        var dir = root
        for (name in segments) {
            dir = dir.findDirectory(name) ?: dir.createDirectory(name) ?: return null
        }
        return dir
    }

    private fun DocumentFile.findDirectory(name: String): DocumentFile? =
        listFiles().firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }

    private fun writeAny(context: Context, folder: DocumentFile, source: File): Boolean {
        if (source.isDirectory) {
            val child = folder.findDirectory(source.name)
                ?: folder.createDirectory(FileNames.sanitize(source.name))
                ?: return false
            val children = source.listFiles() ?: return true
            return children.all { writeAny(context, child, it) }
        }
        val name = FileNames.sanitize(source.name)
        val dest = folder.listFiles().firstOrNull { !it.isDirectory && it.name == name }
            ?: folder.createFile(ContentTypes.of(source.name), name)
            ?: return false
        context.contentResolver.openOutputStream(dest.uri, "w")?.use { output ->
            source.inputStream().use { it.copyTo(output) }
        } ?: return false
        return true
    }
}

object TreeZip {
    fun zip(context: Context, tree: Uri, destination: File) {
        destination.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            zip.putNextEntry(ZipEntry(FolderArchive.MARKER))
            zip.write("folder".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            walk(context, tree, root, "", zip)
        }
    }

    private fun walk(context: Context, tree: Uri, parent: Uri, prefix: String, zip: ZipOutputStream) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn) ?: continue
                val rawName = cursor.getString(nameColumn) ?: "file"
                val mime = cursor.getString(mimeColumn) ?: "application/octet-stream"
                val safe = FileNames.sanitize(rawName)
                val relative = if (prefix.isEmpty()) safe else "$prefix/$safe"
                val child = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(context, tree, child, relative, zip)
                } else {
                    zip.putNextEntry(ZipEntry(relative))
                    context.contentResolver.openInputStream(child)?.use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}
