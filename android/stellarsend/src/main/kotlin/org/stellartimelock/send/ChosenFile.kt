package org.stellartimelock.send

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.stellartimelock.send.core.ContentTypes
import org.stellartimelock.send.core.FileNames

data class ChosenItem(val name: String, val uri: Uri, val mime: String)

object ChosenFile {
    fun fromUri(context: Context, uri: Uri): ChosenItem {
        var name = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "file"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    val display = cursor.getString(index)
                    if (!display.isNullOrBlank()) name = display
                }
            }
        }
        name = FileNames.sanitize(name)
        val mime = ContentTypes.of(name, context.contentResolver.getType(uri))
        return ChosenItem(name, uri, mime)
    }
}
