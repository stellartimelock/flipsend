package org.stellartimelock.send.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class OverwriteKind { ALLOW, NEEDS_CONFIRM }

data class OverwriteDecision(
    val kind: OverwriteKind,
    val reason: String,
    val existingItems: Int,
    val incomingItems: Int,
    val unexpected: Boolean
)

data class SaveInspection(val recognized: Boolean, val itemCount: Int, val version: Int?)

data class SaveSummary(
    val name: String,
    val exists: Boolean,
    val modifiedMillis: Long?,
    val modifiedDisplay: String?,
    val itemCount: Int?,
    val byteSize: Long
) {
    fun describe(): String {
        if (!exists) return "Save file $name was not found."
        val whenText = modifiedDisplay ?: "unknown date"
        val count = when (itemCount) {
            null -> "item count unavailable"
            1 -> "1 item"
            else -> "$itemCount items"
        }
        return "Save file $name, $whenText, $count."
    }
}

data class SaveBackup(val index: Int, val summary: SaveSummary)

object UsDates {
    fun format(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat("MM/dd/yyyy h:mm a", Locale.US)
        format.timeZone = zone
        return format.format(Date(epochMillis))
    }
}

object SaveDocuments {
    private const val MAX_INSPECT_BYTES = 2_000_000

    fun text(bytes: ByteArray): String =
        bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")

    fun inspect(bytes: ByteArray): SaveInspection {
        val body = text(bytes)
        if (body.isEmpty()) return SaveInspection(recognized = true, itemCount = 0, version = null)
        val parsed = try {
            JSONTokener(body).nextValue()
        } catch (_: Exception) {
            return SaveInspection(recognized = false, itemCount = 0, version = null)
        }
        return when (parsed) {
            is JSONArray -> SaveInspection(recognized = true, itemCount = parsed.length(), version = null)
            is JSONObject -> inspectObject(parsed)
            else -> SaveInspection(recognized = false, itemCount = 0, version = null)
        }
    }

    fun summary(file: File, zone: TimeZone = TimeZone.getDefault()): SaveSummary {
        if (!file.isFile) {
            return SaveSummary(file.name, false, null, null, null, 0)
        }
        val size = file.length()
        val inspection = if (size > MAX_INSPECT_BYTES) {
            SaveInspection(recognized = false, itemCount = 0, version = null)
        } else {
            inspect(file.readBytes())
        }
        val modified = file.lastModified()
        return SaveSummary(
            name = file.name,
            exists = true,
            modifiedMillis = modified,
            modifiedDisplay = UsDates.format(modified, zone),
            itemCount = if (inspection.recognized) inspection.itemCount else null,
            byteSize = size
        )
    }

    private fun inspectObject(json: JSONObject): SaveInspection {
        val version = if (json.has("v")) json.optInt("v") else null
        val items = json.opt("items")
        if (items is JSONArray) {
            return SaveInspection(recognized = true, itemCount = items.length(), version = version)
        }
        if (json.has("items")) {
            return SaveInspection(recognized = false, itemCount = 0, version = version)
        }
        var count = 0
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key != "v" && key != "updated") count++
        }
        return SaveInspection(recognized = true, itemCount = count, version = version)
    }
}

object SaveStore {
    const val BACKUPS = 5

    fun decide(existing: ByteArray?, incoming: ByteArray): OverwriteDecision {
        val incomingInfo = SaveDocuments.inspect(incoming)
        if (existing == null || existing.isEmpty()) {
            return OverwriteDecision(
                OverwriteKind.ALLOW,
                "No save is on disk yet.",
                0,
                incomingInfo.itemCount,
                unexpected = !incomingInfo.recognized
            )
        }
        val existingInfo = SaveDocuments.inspect(existing)
        val existingItems = if (existingInfo.recognized) existingInfo.itemCount else 0
        val incomingItems = incomingInfo.itemCount
        if (!existingInfo.recognized || !incomingInfo.recognized) {
            val biggerSurprise = !incomingInfo.recognized && incoming.size > existing.size
            val reason = when {
                biggerSurprise -> "A larger file that is not a save would replace a save with $existingItems items."
                !incomingInfo.recognized -> "A file that is not a save would replace a save with $existingItems items."
                else -> "The current file is not a save this version recognizes. Replacing it needs confirmation."
            }
            return OverwriteDecision(OverwriteKind.NEEDS_CONFIRM, reason, existingItems, incomingItems, unexpected = !incomingInfo.recognized)
        }
        if (incomingItems == 0 && existingItems > 0) {
            return OverwriteDecision(
                OverwriteKind.NEEDS_CONFIRM,
                "An empty save would replace a save with $existingItems items.",
                existingItems,
                incomingItems,
                unexpected = false
            )
        }
        if (existingItems > 0 && incomingItems * 2 < existingItems) {
            return OverwriteDecision(
                OverwriteKind.NEEDS_CONFIRM,
                "A save with $incomingItems items would replace a fuller save with $existingItems items.",
                existingItems,
                incomingItems,
                unexpected = false
            )
        }
        if (existing.size > 64 && incoming.size * 2 < existing.size && incomingItems <= existingItems) {
            return OverwriteDecision(
                OverwriteKind.NEEDS_CONFIRM,
                "A much smaller save ($incomingItems items) would replace a save with $existingItems items.",
                existingItems,
                incomingItems,
                unexpected = false
            )
        }
        return OverwriteDecision(
            OverwriteKind.ALLOW,
            "Save can be updated.",
            existingItems,
            incomingItems,
            unexpected = false
        )
    }

    fun commit(target: File, incoming: ByteArray, confirmed: Boolean): OverwriteDecision {
        val existing = if (target.isFile) target.readBytes() else null
        val decision = decide(existing, incoming)
        if (decision.kind == OverwriteKind.NEEDS_CONFIRM && !confirmed) return decision
        writeAtomic(target, incoming, backups = true)
        val written = SaveDocuments.inspect(incoming)
        return OverwriteDecision(
            OverwriteKind.ALLOW,
            "Saved ${target.name} (${written.itemCount} items).",
            decision.existingItems,
            written.itemCount,
            unexpected = false
        )
    }

    fun backups(target: File): List<SaveBackup> {
        val found = mutableListOf<SaveBackup>()
        for (index in 1..BACKUPS) {
            val file = backupFile(target, index)
            if (!file.isFile) continue
            found += SaveBackup(index, SaveDocuments.summary(file))
        }
        return found
    }

    fun restore(target: File, index: Int, confirmed: Boolean): OverwriteDecision {
        require(index in 1..BACKUPS) { "Backup $index is not one of bak1 through bak5." }
        val backup = backupFile(target, index)
        if (!backup.isFile) throw IOException("Backup $index was not found.")
        return commit(target, backup.readBytes(), confirmed)
    }

    fun writeAtomic(target: File, bytes: ByteArray, backups: Boolean) {
        val parent = target.absoluteFile.parentFile ?: throw IOException("Save folder is missing.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create the save folder.")
        val root = parent.canonicalFile
        val canonical = File(root, target.name).canonicalFile
        val prefix = root.path + File.separator
        if (!canonical.path.startsWith(prefix)) {
            throw IOException("Refusing to write outside the save folder.")
        }
        val temp = File(root, canonical.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (backups) {
            rotate(canonical)
        }
        if (!temp.renameTo(canonical)) {
            if (canonical.exists() && !backups) {
                val holding = File(root, canonical.name + ".old")
                if (!canonical.renameTo(holding)) {
                    temp.delete()
                    throw IOException("Could not replace the save file.")
                }
                if (!temp.renameTo(canonical)) {
                    holding.renameTo(canonical)
                    temp.delete()
                    throw IOException("Could not replace the save file.")
                }
                holding.delete()
                return
            }
            temp.copyTo(canonical, overwrite = true)
            if (canonical.length() != bytes.size.toLong()) {
                throw IOException("Could not finish writing the save file.")
            }
            temp.delete()
        }
    }

    private fun rotate(target: File) {
        if (!target.exists()) return
        val oldest = backupFile(target, BACKUPS)
        if (oldest.exists() && !oldest.delete()) {
            throw IOException("Could not rotate the oldest save backup.")
        }
        for (index in (BACKUPS - 1) downTo 1) {
            val source = backupFile(target, index)
            if (!source.exists()) continue
            val dest = backupFile(target, index + 1)
            moveReplacing(source, dest)
        }
        moveReplacing(target, backupFile(target, 1))
    }

    private fun moveReplacing(source: File, dest: File) {
        if (dest.exists() && !dest.delete()) {
            throw IOException("Could not rotate a save backup.")
        }
        if (source.renameTo(dest)) return
        source.copyTo(dest, overwrite = true)
        if (dest.length() != source.length()) {
            throw IOException("Could not rotate a save backup.")
        }
        if (!source.delete()) {
            throw IOException("Could not rotate a save backup.")
        }
    }

    private fun backupFile(target: File, index: Int) = File(target.path + ".bak$index")
}

data class UserPreferences(val manualHost: String?, val deviceName: String?)

object UserPreferencesCodec {
    fun read(bytes: ByteArray): UserPreferences {
        val body = SaveDocuments.text(bytes)
        if (body.isEmpty()) return UserPreferences(null, null)
        val parsed = try {
            JSONTokener(body).nextValue()
        } catch (_: Exception) {
            return UserPreferences(null, null)
        }
        return try {
            when (parsed) {
                is JSONArray -> fromItems(parsed)
                is JSONObject -> {
                    val items = parsed.opt("items")
                    if (items is JSONArray) fromItems(items)
                    else UserPreferences(
                        stringField(parsed, "manualHost", "manual_host"),
                        stringField(parsed, "deviceName", "device_name")
                    )
                }
                else -> UserPreferences(null, null)
            }
        } catch (_: Exception) {
            UserPreferences(null, null)
        }
    }

    fun write(prefs: UserPreferences): ByteArray {
        val items = JSONArray()
        prefs.manualHost?.trim()?.takeIf { it.isNotEmpty() }?.let { host ->
            items.put(JSONObject().put("k", "manualHost").put("v", host))
        }
        prefs.deviceName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            items.put(JSONObject().put("k", "deviceName").put("v", name))
        }
        return JSONObject().put("v", 1).put("items", items).toString().toByteArray(Charsets.UTF_8)
    }

    private fun fromItems(items: JSONArray): UserPreferences {
        var host: String? = null
        var name: String? = null
        for (index in 0 until items.length()) {
            val entry = items.opt(index)
            if (entry !is JSONObject) continue
            when (entry.optString("k")) {
                "manualHost", "manual_host" -> host = entry.optString("v").trim().ifBlank { null }
                "deviceName", "device_name" -> name = entry.optString("v").trim().ifBlank { null }
            }
        }
        return UserPreferences(host, name)
    }

    private fun stringField(json: JSONObject, vararg names: String): String? {
        for (name in names) {
            if (name.equals("password", ignoreCase = true)) continue
            val value = json.optString(name).trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return null
    }
}
