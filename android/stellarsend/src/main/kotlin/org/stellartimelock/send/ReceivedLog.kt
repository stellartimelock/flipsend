package org.stellartimelock.send

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.stellartimelock.send.core.ApkBatch
import org.stellartimelock.send.core.ReceivedEntry
import org.stellartimelock.send.core.ReceivedHistory
import java.io.File
import java.util.UUID

/** Remembers every file that landed in the inbox, newest first. */
class ReceivedLog(context: Context) {
    private val app = context.applicationContext
    private val dir: File = PhoneInbox.directory(app)
    private val index = File(app.filesDir, "received.json")
    private val gate = Any()

    fun list(): List<ReceivedEntry> = synchronized(gate) { ReceivedHistory.sorted(read()) }

    /** Inbox path for list rows. Does not walk storage or call exists(). */
    fun displayPath(entry: ReceivedEntry): String = File(dir, entry.name).path

    fun add(file: File, kind: String, at: Long = System.currentTimeMillis()): ReceivedEntry = synchronized(gate) {
        val entry = ReceivedEntry(
            id = UUID.randomUUID().toString(),
            name = file.name,
            bytes = bytesOf(file),
            receivedAtMillis = at,
            kind = kind
        )
        write(read() + entry)
        entry
    }

    fun find(id: String): ReceivedEntry? = synchronized(gate) { read().find { it.id == id } }

    fun findBySession(sessionId: Int): ReceivedEntry? = synchronized(gate) {
        if (sessionId < 0) return null
        read().find { it.sessionId == sessionId }
    }

    fun mark(id: String, status: String, detail: String, sessionId: Int = -1) = synchronized(gate) {
        val next = read().map { item ->
            if (item.id != id) item
            else item.copy(
                installStatus = status,
                installDetail = detail,
                sessionId = if (sessionId >= 0) sessionId else item.sessionId
            )
        }
        write(next)
    }

    /** Package identity for the Apps list. Older JSON without these fields still loads. */
    fun noteApp(
        id: String,
        appName: String,
        packageName: String,
        incomingVersion: String,
        incomingCode: Long,
        installedVersion: String,
        installedCode: Long,
        hasInstalled: Boolean,
        signatureMatch: String
    ) = synchronized(gate) {
        val next = read().map { item ->
            if (item.id != id) item
            else item.copy(
                appName = appName,
                packageName = packageName,
                incomingVersion = incomingVersion,
                incomingCode = incomingCode,
                installedVersion = installedVersion,
                installedCode = installedCode,
                hasInstalled = hasInstalled,
                signatureMatch = signatureMatch.ifBlank { item.signatureMatch }
            )
        }
        write(next)
    }

    /**
     * After a new APK is registered for [packageName], keep only the entry with the highest
     * incoming version code (breaking ties by most recently received). Returns the entries
     * whose files should be deleted by the caller.
     */
    fun pruneOlderApks(packageName: String): List<ReceivedEntry> = synchronized(gate) {
        if (packageName.isBlank()) return@synchronized emptyList()
        val entries = read()
        val apkEntries = entries.filter {
            it.packageName == packageName && ApkBatch.isApk(it.kind, it.name)
        }
        if (apkEntries.size <= 1) return@synchronized emptyList()
        val best = apkEntries.maxWithOrNull(
            compareBy<ReceivedEntry> { it.incomingCode }.thenBy { it.receivedAtMillis }
        ) ?: return@synchronized emptyList()
        val toRemove = apkEntries.filter { it.id != best.id }
        val toRemoveIds = toRemove.map { it.id }.toSet()
        write(entries.filter { it.id !in toRemoveIds })
        toRemove
    }

    /** Every package: keep the newest APK, drop older log rows. Caller deletes the files. */
    fun sweepOlderCopies(): List<ReceivedEntry> {
        val names = list()
            .filter { ApkBatch.isApk(it.kind, it.name) && it.packageName.isNotBlank() }
            .map { it.packageName }
            .distinct()
        val removed = ArrayList<ReceivedEntry>()
        for (pkg in names) removed += pruneOlderApks(pkg)
        return removed
    }

    /** Delete leftover APK files on disk that are older copies of the same package. */
    fun sweepInboxFiles(identityOf: (File) -> Pair<String, Long>?): List<File> {
        val files = PhoneInbox.sweepDirs(app).flatMap { it.listFiles()?.toList().orEmpty() }
        val drop = org.stellartimelock.send.core.ApkLeftovers.orphanFiles(files, list(), identityOf)
        for (file in drop) runCatching { file.delete() }
        return drop
    }

    /**
     * Remove one log entry by id. Returns the entry so the caller can delete its file.
     * Does nothing if the id is not found.
     */
    fun clearApkEntry(id: String): ReceivedEntry? = synchronized(gate) {
        val entries = read()
        val entry = entries.find { it.id == id } ?: return@synchronized null
        write(entries.filter { it.id != id })
        entry
    }

    fun file(entry: ReceivedEntry): File {
        for (root in PhoneInbox.lookupDirs(app)) {
            val child = safeChild(root, entry.name) ?: continue
            if (child.exists()) return child
        }
        return safeChild(dir, entry.name) ?: File(dir, "missing")
    }

    private fun safeChild(root: File, name: String): File? {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val child = runCatching { File(canonicalRoot, name).canonicalFile }.getOrNull() ?: return null
        val prefix = canonicalRoot.path + File.separator
        if (child.path != canonicalRoot.path && !child.path.startsWith(prefix)) return null
        return child
    }

    fun readText(entry: ReceivedEntry): String {
        val file = file(entry)
        if (!file.exists()) return "That file is no longer on this phone."
        if (file.isDirectory) {
            val names = file.listFiles()?.sortedBy { it.name.lowercase() }?.joinToString("\n") { child ->
                if (child.isDirectory) child.name + "/" else child.name
            }.orEmpty()
            return names.ifBlank { "This folder is empty." }
        }
        if (entry.kind != "text" && !ReceivedHistory.isText(entry.name)) {
            return "This file is not text."
        }
        if (file.length() > MAX_TEXT) return "This text file is too large to show here."
        val text = runCatching { file.readText() }.getOrElse { return "Could not read that file." }
        return text.ifBlank { "(This file is empty.)" }
    }

    private fun bytesOf(file: File): Long {
        if (file.isFile) return file.length()
        if (!file.isDirectory) return 0
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun read(): List<ReceivedEntry> {
        if (!index.isFile || index.length() == 0L) {
            remember(emptyList(), 0L, 0L)
            return emptyList()
        }
        val modified = index.lastModified()
        val length = index.length()
        cached?.takeIf { it.path == index.absolutePath && it.modified == modified && it.length == length }
            ?.let { return it.items }
        return try {
            val array = JSONObject(index.readText()).optJSONArray("items") ?: JSONArray()
            val items = (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::decode) }
            remember(items, modified, length)
            items
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun remember(items: List<ReceivedEntry>, modified: Long, length: Long) {
        cached = Cache(index.absolutePath, modified, length, items)
    }

    private fun write(entries: List<ReceivedEntry>) {
        val array = JSONArray()
        entries.forEach { entry -> array.put(encode(entry)) }
        val body = JSONObject().put("items", array).toString().toByteArray()
        val tmp = File(index.parentFile, index.name + ".tmp")
        tmp.writeBytes(body)
        if (index.exists() && !index.delete()) {
            tmp.delete()
            return
        }
        if (!tmp.renameTo(index)) {
            index.writeBytes(body)
            tmp.delete()
        }
        remember(entries.toList(), index.lastModified(), index.length())
    }

    private fun encode(entry: ReceivedEntry) = JSONObject()
        .put("id", entry.id)
        .put("name", entry.name)
        .put("bytes", entry.bytes)
        .put("at", entry.receivedAtMillis)
        .put("kind", entry.kind)
        .put("status", entry.installStatus)
        .put("detail", entry.installDetail)
        .put("session", entry.sessionId)
        .put("package", entry.packageName)
        .put("app", entry.appName)
        .put("inVer", entry.incomingVersion)
        .put("inCode", entry.incomingCode)
        .put("instVer", entry.installedVersion)
        .put("instCode", entry.installedCode)
        .put("hasInst", entry.hasInstalled)
        .put("signature", entry.signatureMatch)

    private fun decode(json: JSONObject): ReceivedEntry? {
        val id = json.optString("id").ifBlank { return null }
        val name = json.optString("name").ifBlank { return null }
        return ReceivedEntry(
            id = id,
            name = name,
            bytes = json.optLong("bytes"),
            receivedAtMillis = json.optLong("at"),
            kind = json.optString("kind").ifBlank { "file" },
            installStatus = json.optString("status"),
            installDetail = json.optString("detail"),
            sessionId = json.optInt("session", -1),
            packageName = json.optString("package"),
            appName = json.optString("app"),
            incomingVersion = json.optString("inVer"),
            incomingCode = json.optLong("inCode", -1),
            installedVersion = json.optString("instVer"),
            installedCode = json.optLong("instCode", -1),
            hasInstalled = json.optBoolean("hasInst"),
            signatureMatch = json.optString("signature")
        )
    }

    companion object {
        private const val MAX_TEXT = 1_000_000L
        private data class Cache(
            val path: String,
            val modified: Long,
            val length: Long,
            val items: List<ReceivedEntry>
        )
        @Volatile private var cached: Cache? = null
    }
}

object InstallPrefs {
    private const val PREFS = "stellarsend"
    private const val LEGACY_KEY = "auto_install_updates"
    private const val FILE_NAME = "auto-install-from-pc.json"

    fun auto(context: Context): Boolean {
        val app = context.applicationContext
        val file = file(app)
        if (file.isFile) {
            val decoded = runCatching { org.stellartimelock.send.core.PcInstallPreference.decode(file.readBytes()) }.getOrNull()
            if (decoded != null) return decoded
        }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(LEGACY_KEY)) return prefs.getBoolean(LEGACY_KEY, true)
        return org.stellartimelock.send.core.PcInstallPreference.DEFAULT
    }

    fun setAuto(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        val body = org.stellartimelock.send.core.PcInstallPreference.encode(enabled)
        val wrote = org.stellartimelock.send.core.DurableFile.write(file(app), body, allowShrink = true)
        if (!wrote) return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(LEGACY_KEY, enabled).commit()
    }

    private fun file(context: Context) = java.io.File(context.filesDir, FILE_NAME)
}
