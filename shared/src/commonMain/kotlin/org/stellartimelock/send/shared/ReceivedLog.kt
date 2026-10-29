package org.stellartimelock.send.shared

/** Pure data model for a received file entry. Shared between platforms. */
data class ReceivedEntry(
    val id: String,
    val name: String,
    val bytes: Long,
    val receivedAtMillis: Long,
    val kind: String   // "file" or "text"
)

/** Minimal JSON log codec for received-file history. */
object ReceivedLog {
    fun encode(entries: List<ReceivedEntry>): String {
        val items = entries.joinToString(",") { e ->
            "{\"id\":${jsonString(e.id)},\"name\":${jsonString(e.name)}" +
            ",\"bytes\":${e.bytes},\"at\":${e.receivedAtMillis},\"kind\":${jsonString(e.kind)}}"
        }
        return "{\"v\":1,\"items\":[$items]}"
    }

    fun decode(json: String): List<ReceivedEntry> {
        if (json.isBlank()) return emptyList()
        return try { parseEntries(json) } catch (_: Exception) { emptyList() }
    }

    private fun parseEntries(json: String): List<ReceivedEntry> {
        val items = mutableListOf<ReceivedEntry>()
        val itemsStart = json.indexOf("\"items\"")
        if (itemsStart < 0) return items
        val arrayStart = json.indexOf('[', itemsStart)
        if (arrayStart < 0) return items
        var depth = 0
        var objectStart = -1
        var i = arrayStart
        while (i < json.length) {
            when (json[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0 && objectStart >= 0) {
                        parseEntry(json.substring(objectStart, i + 1))?.let { items += it }
                        break
                    }
                }
                '{' -> {
                    if (depth == 1) objectStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 1 && objectStart >= 0) {
                        parseEntry(json.substring(objectStart, i + 1))?.let { items += it }
                        objectStart = -1
                    }
                }
            }
            i++
        }
        return items
    }

    private fun parseEntry(obj: String): ReceivedEntry? {
        val id = SimpleJson.getString(obj, "id") ?: return null
        val name = SimpleJson.getString(obj, "name") ?: return null
        val bytes = SimpleJson.getLong(obj, "bytes") ?: 0L
        val at = SimpleJson.getLong(obj, "at") ?: 0L
        val kind = SimpleJson.getString(obj, "kind") ?: "file"
        return ReceivedEntry(id, name, bytes, at, kind)
    }
}
