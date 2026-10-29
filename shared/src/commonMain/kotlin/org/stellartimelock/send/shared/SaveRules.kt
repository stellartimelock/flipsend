package org.stellartimelock.send.shared

/**
 * Save-file rules: decide whether a new save can replace an existing one,
 * and rotate up to [BACKUPS] backup files.
 *
 * All logic here is pure Kotlin; the actual file I/O is handled by
 * platform-specific implementations.
 *
 * On iOS the "save folder" is StellarTimeLock/FlipSend/ inside a
 * security-scoped bookmark folder chosen via the document picker.
 * On Android it is the existing app-private files directory.
 */

const val SAVE_BACKUP_COUNT = 5
const val MIN_GUARDED_BYTES = 32L
const val MAX_INSPECT_BYTES = 2_000_000

enum class OverwriteKind { ALLOW, NEEDS_CONFIRM }

data class OverwriteDecision(
    val kind: OverwriteKind,
    val reason: String,
    val existingItems: Int,
    val incomingItems: Int,
    val unexpected: Boolean
)

data class SaveInspection(val recognized: Boolean, val itemCount: Int, val version: Int?)

object SaveDocuments {
    fun text(bytes: ByteArray): String =
        bytes.decodeToString().trim().removePrefix("\uFEFF")

    fun inspect(bytes: ByteArray): SaveInspection {
        val body = text(bytes)
        if (body.isEmpty()) return SaveInspection(recognized = true, itemCount = 0, version = null)
        return try {
            inspectJson(body)
        } catch (_: Exception) {
            SaveInspection(recognized = false, itemCount = 0, version = null)
        }
    }

    private fun inspectJson(body: String): SaveInspection {
        val trimmed = body.trim()
        return when {
            trimmed.startsWith('[') -> {
                val count = countJsonArrayElements(trimmed)
                SaveInspection(recognized = true, itemCount = count, version = null)
            }
            trimmed.startsWith('{') -> inspectJsonObject(trimmed)
            else -> SaveInspection(recognized = false, itemCount = 0, version = null)
        }
    }

    private fun inspectJsonObject(json: String): SaveInspection {
        val version = SimpleJson.getInt(json, "v")
        val itemsStart = findJsonArrayValue(json, "items")
        if (itemsStart != null) {
            val count = countJsonArrayElements(itemsStart)
            return SaveInspection(recognized = true, itemCount = count, version = version)
        }
        if (json.contains("\"items\"")) return SaveInspection(recognized = false, itemCount = 0, version = version)
        var count = 0
        var i = 1
        while (i < json.length) {
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != '"') break
            val keyEnd = json.indexOf('"', i + 1)
            if (keyEnd < 0) break
            val key = json.substring(i + 1, keyEnd)
            if (key != "v" && key != "updated") count++
            i = keyEnd + 1
            // skip to next key
            val nextComma = json.indexOf(',', i)
            i = if (nextComma < 0) json.length else nextComma + 1
        }
        return SaveInspection(recognized = true, itemCount = count, version = version)
    }

    private fun findJsonArrayValue(json: String, key: String): String? {
        val prefix = "\"$key\""
        val idx = json.indexOf(prefix)
        if (idx < 0) return null
        var i = idx + prefix.length
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != ':') return null
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '[') return null
        return json.substring(i)
    }

    private fun countJsonArrayElements(arrayText: String): Int {
        if (!arrayText.startsWith('[')) return 0
        var depth = 0; var inString = false; var escape = false; var count = 0; var hasContent = false
        for (ch in arrayText) {
            if (escape) { escape = false; continue }
            if (ch == '\\' && inString) { escape = true; continue }
            if (ch == '"') { inString = !inString; continue }
            if (inString) continue
            when (ch) {
                '[', '{' -> depth++
                ']', '}' -> { if (depth == 1 && ch == ']' && hasContent) count++; depth-- }
                ',' -> if (depth == 1) { count++; hasContent = false }
                else -> if (!ch.isWhitespace() && depth == 1) hasContent = true
            }
        }
        return count
    }
}

object SaveRules {
    /**
     * Returns an [OverwriteDecision] describing whether [incoming] may replace [existing].
     * Does NOT touch the file system.
     */
    fun decide(existing: ByteArray?, incoming: ByteArray): OverwriteDecision {
        val incomingInfo = SaveDocuments.inspect(incoming)
        if (existing == null || existing.isEmpty()) {
            return OverwriteDecision(
                OverwriteKind.ALLOW, "No save is on disk yet.",
                0, incomingInfo.itemCount, unexpected = !incomingInfo.recognized
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
            return OverwriteDecision(OverwriteKind.NEEDS_CONFIRM, reason, existingItems, incomingItems, !incomingInfo.recognized)
        }
        if (incomingItems == 0 && existingItems > 0) {
            return OverwriteDecision(
                OverwriteKind.NEEDS_CONFIRM,
                "An empty save would replace a save with $existingItems items.",
                existingItems, incomingItems, false
            )
        }
        if (existingItems > 0 && incomingItems * 2 < existingItems) {
            return OverwriteDecision(
                OverwriteKind.NEEDS_CONFIRM,
                "A save with $incomingItems items would replace a fuller save with $existingItems items.",
                existingItems, incomingItems, false
            )
        }
        if (existing.size > 64 && incoming.size * 2 < existing.size && incomingItems <= existingItems) {
            return OverwriteDecision(
                OverwriteKind.NEEDS_CONFIRM,
                "A much smaller save ($incomingItems items) would replace a save with $existingItems items.",
                existingItems, incomingItems, false
            )
        }
        return OverwriteDecision(OverwriteKind.ALLOW, "Save can be updated.", existingItems, incomingItems, false)
    }

    fun wouldReplaceWithMuchSmaller(existingBytes: Long, incomingBytes: Long): Boolean {
        if (existingBytes <= 0) return false
        if (incomingBytes <= 0) return true
        if (existingBytes < MIN_GUARDED_BYTES) return false
        return incomingBytes * 2 < existingBytes
    }
}
