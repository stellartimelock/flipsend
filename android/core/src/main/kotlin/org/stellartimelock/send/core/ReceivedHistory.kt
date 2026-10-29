package org.stellartimelock.send.core

import java.util.TimeZone

data class ReceivedEntry(
    val id: String,
    val name: String,
    val bytes: Long,
    val receivedAtMillis: Long,
    val kind: String,
    val installStatus: String = "",
    val installDetail: String = "",
    val sessionId: Int = -1,
    val packageName: String = "",
    val appName: String = "",
    val incomingVersion: String = "",
    val incomingCode: Long = -1,
    val installedVersion: String = "",
    val installedCode: Long = -1,
    val hasInstalled: Boolean = false,
    val signatureMatch: String = ""
)

object ReceivedHistory {
    const val EXPLANATION =
        "Apps sent from your paired PC install on their own. An update of an app already on this phone replaces the old copy. Android may still ask once for a first install. Photos and other files still ask. Wallet, OS, Fortress, Text Manager, and Delivery Driver are never uninstalled."

    const val TAG_UPDATE = "Update"
    const val TAG_INSTALLED = "Installed"
    const val TAG_NOT_INSTALLED = "Not installed"
    const val TAG_OLDER = "Older"
    const val TAG_INSTALLING = "Installing"

    private val textExtensions = setOf("txt", "text", "md", "json", "csv", "log", "xml", "html", "htm")

    fun sorted(entries: List<ReceivedEntry>): List<ReceivedEntry> =
        entries.sortedWith(compareByDescending<ReceivedEntry> { it.receivedAtMillis }.thenByDescending { it.id })

    fun isText(name: String): Boolean {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        val ext = leaf.substringAfterLast('.', "").lowercase()
        return ext in textExtensions
    }

    fun sizeLabel(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    fun summary(entry: ReceivedEntry, zone: TimeZone = TimeZone.getDefault()): String =
        "${entry.name} · ${sizeLabel(entry.bytes)} · ${UsDates.format(entry.receivedAtMillis, zone)}"

    /** Auto-install skips the extra prompt. The system still asks the first time. */
    fun userActionRequired(autoInstall: Boolean): Boolean = !autoInstall

    /** Received APKs are installed in place. Uninstall is what would wipe app data. */
    fun keepsAppData(): Boolean = true

    /**
     * Status tag for a received APK in the history list. Distinct from the live-batch labels
     * ("Needs your tap", etc.) so the history view can show actionable intent clearly.
     */
    fun apkTag(entry: ReceivedEntry): String {
        val incoming = entry.incomingCode
        val installed = entry.installedCode
        // A finished update wins over a session mark that never cleared.
        if (entry.hasInstalled && incoming >= 0 && installed >= incoming) {
            return if (incoming < installed) TAG_OLDER else TAG_INSTALLED
        }
        if (entry.installStatus == "installing" || entry.installStatus == "pending" || entry.installStatus == "replacing") {
            return TAG_INSTALLING
        }
        if (entry.installStatus == "success") return TAG_INSTALLED
        if (!entry.hasInstalled) return TAG_NOT_INSTALLED
        if (incoming < 0 || installed < 0) return TAG_UPDATE
        return TAG_UPDATE
    }

    /** Line shown on a received row while that app is still installing. Percent is 0?100. */
    fun installProgressLine(entry: ReceivedEntry, percent: Int): String {
        val name = entry.appName.trim().ifBlank { entry.packageName.trim() }.ifBlank { "app" }
        return if (entry.installStatus == "replacing") {
            ReceiveStatus.replacingLine(name)
        } else {
            ReceiveStatus.installingLine(name, percent)
        }
    }

    /** Lower value = shown first in the received list. */
    fun apkTagPriority(tag: String): Int = when (tag) {
        TAG_INSTALLING -> 0
        TAG_UPDATE -> 1
        TAG_NOT_INSTALLED -> 2
        TAG_INSTALLED -> 3
        TAG_OLDER -> 4
        else -> 5
    }

    /**
     * Sort for the received-history APK list:
     * Updates first, then not installed, then installed, then older.
     * Within the same tag, newer received first.
     */
    fun sortedApks(entries: List<ReceivedEntry>): List<ReceivedEntry> =
        entries.sortedWith(
            compareBy<ReceivedEntry> { apkTagPriority(apkTag(it)) }
                .thenByDescending { it.receivedAtMillis }
        )

    /** A history row as a receive-screen file. "saved" is not an install result. */
    fun asReceiveFile(entry: ReceivedEntry, path: String): ReceiveFile {
        val mark = when (entry.installStatus) {
            "success", "failed", "installing", "pending", "needs-tap" -> entry.installStatus
            else -> ""
        }
        return ReceiveFile(
            id = entry.id,
            name = entry.name,
            kind = entry.kind,
            path = path,
            appName = entry.appName,
            packageName = entry.packageName,
            incomingVersion = entry.incomingVersion,
            incomingCode = entry.incomingCode,
            installedVersion = entry.installedVersion,
            installedCode = entry.installedCode,
            hasInstalled = entry.hasInstalled || entry.installStatus == "success",
            installMark = mark,
            installDetail = entry.installDetail,
            signatureMatch = entry.signatureMatch
        )
    }
}

/** History plus the batch still on screen. Arrival order decides which copy of an app is newest. */
object ReceivedApps {
    fun merge(entries: List<ReceivedEntry>, live: List<ReceiveFile>, pathOf: (ReceivedEntry) -> String): List<ReceiveFile> {
        val liveById = live.associateBy { it.id }
        val known = HashSet<String>()
        val fromLog = entries
            .sortedWith(compareBy<ReceivedEntry> { it.receivedAtMillis }.thenBy { it.id })
            .map { entry ->
                known += entry.id
                liveById[entry.id] ?: ReceivedHistory.asReceiveFile(entry, pathOf(entry))
            }
        val extra = live.filter { ApkBatch.isApk(it) && it.id !in known }
        return fromLog + extra
    }

    fun attentionCount(entries: List<ReceivedEntry>, live: List<ReceiveFile> = emptyList()): Int =
        ApkBatch.attentionCount(merge(entries, live) { "" })
}
