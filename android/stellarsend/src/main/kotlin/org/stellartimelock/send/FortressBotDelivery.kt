package org.stellartimelock.send

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.stellartimelock.send.core.CopyToPc
import org.stellartimelock.send.core.FileNames
import org.stellartimelock.send.core.FortressBotHandoff
import org.stellartimelock.send.core.LogRedaction
import java.io.File

object FortressBotDelivery {
    fun handOff(context: Context, inboxFile: File): Boolean {
        if (!inboxFile.exists()) return false
        val cache = File(context.cacheDir, "handoff").apply { mkdirs() }
        cache.listFiles()?.forEach { it.delete() }
        val staged = FileNames.exact(cache, inboxFile.name)
        inboxFile.inputStream().use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        }
        inboxFile.delete()
        val text = staged.readText(Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.stellarsend.files",
            staged
        )
        val intent = intent(uri, staged.name, text)
        context.grantUriPermission(
            CopyToPc.FORTRESS_PACKAGE,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        return try {
            context.startActivity(intent)
            android.util.Log.i("StellarSend", LogRedaction.redact(FortressBotHandoff.HANDED))
            true
        } catch (_: ActivityNotFoundException) {
            try {
                context.startService(Intent(intent).apply { flags = Intent.FLAG_GRANT_READ_URI_PERMISSION })
                android.util.Log.i("StellarSend", LogRedaction.redact(FortressBotHandoff.HANDED))
                true
            } catch (_: Exception) {
                staged.delete()
                android.util.Log.i("StellarSend", LogRedaction.redact(FortressBotHandoff.FORTRESS_MISSING))
                false
            }
        }
    }

    fun intent(uri: Uri, filename: String, content: String): Intent {
        val safeName = FileNames.sanitize(filename)
        return Intent(FortressBotHandoff.ACTION_BOT_HANDOFF).apply {
            setPackage(CopyToPc.FORTRESS_PACKAGE)
            setDataAndType(uri, FortressBotHandoff.MIME_JSON)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(FortressBotHandoff.EXTRA_NAME, safeName)
            putExtra(FortressBotHandoff.EXTRA_CONTENT, content)
            putExtra(FortressBotHandoff.EXTRA_FILENAME, safeName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri(safeName, uri)
        }
    }
}
