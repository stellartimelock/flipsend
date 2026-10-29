package org.stellartimelock.send.core

import java.util.TimeZone

data class HomeAppRow(
    val id: String,
    val name: String,
    val version: String,
    val received: String,
    val action: String,
    val receivedAtMillis: Long
)

data class HomeAppList(
    val shown: List<HomeAppRow>,
    val more: Int
)

/**
 * Received APKs for the home Apps card. Newest first.
 * Older rows stay behind Show more until the list is opened.
 */
object HomeApps {
    const val PREVIEW = 1
    const val PAGE = ListWindow.HOME_PAGE
    const val INSTALL = "Install"
    const val UPDATE = "Update"
    const val INSTALLED = "Installed"
    const val INSTALLING = "Installing"
    const val SHOW_MORE = "Show more"
    const val SHOW_LESS = "Show less"
    const val JUST_NOW = "Just now"
    const val EMPTY = "No apps yet."

    fun list(
        files: List<ReceiveFile>,
        receivedAt: Map<String, Long>,
        expanded: Boolean,
        zone: TimeZone = TimeZone.getDefault(),
        limit: Int = if (expanded) PAGE else PREVIEW
    ): HomeAppList {
        val rows = files
            .filter { ApkBatch.isApk(it) }
            .map { file ->
                val at = receivedAt[file.id] ?: 0L
                file to at
            }
            .sortedWith(compareByDescending<Pair<ReceiveFile, Long>> { if (it.second > 0L) it.second else Long.MAX_VALUE }
                .thenByDescending { it.first.id })
            .map { (file, at) ->
                HomeAppRow(
                    id = file.id,
                    name = ApkBatch.displayName(file),
                    version = versionText(file),
                    received = if (at > 0L) UsDates.format(at, zone) else JUST_NOW,
                    action = action(file),
                    receivedAtMillis = at
                )
            }
        val cap = limit.coerceAtLeast(0)
        val shown = if (rows.size <= cap) rows else rows.take(cap)
        return HomeAppList(shown, rows.size - shown.size)
    }

    fun action(file: ReceiveFile): String = when (ApkBatch.status(file)) {
        ApkBatch.UPDATE -> UPDATE
        ApkBatch.NOT_INSTALLED, ApkBatch.NEEDS_TAP, ApkBatch.FAILED -> INSTALL
        ApkBatch.INSTALLED -> INSTALLED
        ApkBatch.INSTALLING, ApkBatch.REPLACING -> INSTALLING
        else -> ""
    }

    fun isButton(action: String): Boolean = action == INSTALL || action == UPDATE

    fun versionText(file: ReceiveFile): String {
        val name = file.incomingVersion.trim()
        val code = if (file.incomingCode >= 0) file.incomingCode.toString() else ""
        return when {
            name.isNotEmpty() && code.isNotEmpty() -> "$name ($code)"
            name.isNotEmpty() -> name
            code.isNotEmpty() -> code
            else -> "Unknown version"
        }
    }
}
