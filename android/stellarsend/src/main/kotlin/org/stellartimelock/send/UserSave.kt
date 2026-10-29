package org.stellartimelock.send

import android.content.Context
import org.stellartimelock.send.core.ApkReplacePreview
import org.stellartimelock.send.core.PhoneSaveFolder
import org.stellartimelock.send.core.OverwriteDecision
import org.stellartimelock.send.core.OverwriteKind
import org.stellartimelock.send.core.SaveBackup
import org.stellartimelock.send.core.SaveDocuments
import org.stellartimelock.send.core.SaveStore
import org.stellartimelock.send.core.UserPreferences
import org.stellartimelock.send.core.UserPreferencesCodec
import org.stellartimelock.send.core.trimDeviceName
import java.io.File

internal object UserFolders {
    const val LABEL = PhoneSaveFolder.LABEL

    fun writable(context: Context): File {
        val dest = PhoneInbox.directory(context)
        if (PhoneSaveFolder.ensureWritable(dest)) return dest
        return File(context.filesDir, "FlipSend").apply { mkdirs() }
    }

    fun locate(context: Context, packageName: String): File {
        val name = ApkReplacePreview.saveFileName(packageName)
        val dirs = linkedSetOf<File>()
        dirs += PhoneInbox.lookupDirs(context)
        if (packageName == context.packageName) {
            dirs += File(context.filesDir, "Stellar")
            dirs += File(context.filesDir, "FlipSend")
        }
        for (dir in dirs) {
            val direct = File(dir, name)
            if (direct.isFile) return direct
            val nested = File(dir, "saves/$name")
            if (nested.isFile) return nested
        }
        return File(writable(context), name)
    }
}

internal class UserSaveVault(private val context: Context) {
    private val prefs = context.getSharedPreferences("stellarsend", Context.MODE_PRIVATE)
    private val file: File = resolve()
    private var cache: UserPreferences = readFromDisk()

    fun manualHost(): String? = cache.manualHost

    fun deviceName(): String = trimDeviceName(cache.deviceName ?: "Android")

    fun setManualHost(host: String?, confirmed: Boolean): OverwriteDecision =
        write(cache.copy(manualHost = host?.trim()?.ifBlank { null }), confirmed)

    fun setDeviceName(name: String, confirmed: Boolean): OverwriteDecision =
        write(cache.copy(deviceName = trimDeviceName(name)), confirmed)

    fun backups(): List<SaveBackup> = runCatching { SaveStore.backups(file) }.getOrDefault(emptyList())

    fun restore(index: Int, confirmed: Boolean): OverwriteDecision {
        val decision = SaveStore.restore(file, index, confirmed)
        if (decision.kind == OverwriteKind.ALLOW) {
            cache = readFromDisk()
            mirror()
        }
        return decision
    }

    private fun write(next: UserPreferences, confirmed: Boolean): OverwriteDecision {
        val decision = SaveStore.commit(file, UserPreferencesCodec.write(next), confirmed)
        if (decision.kind == OverwriteKind.ALLOW) {
            cache = next
            mirror()
        }
        return decision
    }

    private fun mirror() {
        prefs.edit()
            .putString("manual_host", cache.manualHost ?: "")
            .putString("device_name", cache.deviceName ?: "")
            .apply()
    }

    private fun readFromDisk(): UserPreferences {
        val current = recognized(file)
        if (current != null) return current
        val backup = recognized(File(file.path + ".bak1"))
        if (backup != null) return backup
        return UserPreferences(
            manualHost = prefs.getString("manual_host", null)?.trim()?.ifBlank { null },
            deviceName = prefs.getString("device_name", null)?.trim()?.ifBlank { null }
        )
    }

    private fun recognized(candidate: File): UserPreferences? {
        if (!candidate.isFile) return null
        val bytes = runCatching { candidate.readBytes() }.getOrNull() ?: return null
        val inspection = SaveDocuments.inspect(bytes)
        if (!inspection.recognized) return null
        return UserPreferencesCodec.read(bytes)
    }

    private fun resolve(): File {
        val name = ApkReplacePreview.saveFileName(context.packageName)
        val dir = runCatching { UserFolders.writable(context) }.getOrElse { File(context.filesDir, "Stellar") }
        runCatching { if (!dir.exists()) dir.mkdirs() }
        return File(dir, name)
    }
}
