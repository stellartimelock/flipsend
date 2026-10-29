package org.stellartimelock.send.core

data class LiveSend(val name: String, val percent: Int, val active: Boolean)

data class QueueRow(
    val section: String,
    val title: String,
    val detail: String,
    val percent: Int,
    val showBar: Boolean,
    val apk: Boolean = false
)

/** In progress, pending, and finished rows for the main-page queue. */
object TransferQueue {
    const val IN_PROGRESS = "In progress"
    const val PENDING = "Pending"
    const val FINISHED = "Finished"
    const val INSTALLED = "Installed"
    const val TAP = "Tap to install"
    const val SAVED = "Saved"
    const val EMPTY = "Nothing sending right now."

    fun rows(snap: ReceiveSnap, live: LiveSend? = null): List<QueueRow> {
        val rows = mutableListOf<QueueRow>()
        if (live != null && live.active) {
            rows += QueueRow(
                IN_PROGRESS,
                live.name.ifBlank { "File" },
                "${live.percent}%",
                live.percent,
                true,
                apk = live.name.endsWith(".apk", ignoreCase = true)
            )
        }
        if (snap.receiving && rows.none { it.title == snap.name }) {
            rows += QueueRow(
                IN_PROGRESS,
                snap.name.ifBlank { "File" },
                "${snap.percent}%",
                snap.percent,
                true,
                apk = snap.name.endsWith(".apk", ignoreCase = true)
            )
        }
        val seen = mutableSetOf<String>()
        for (file in snap.files) {
            val key = file.packageName.ifBlank { file.id.ifBlank { file.name } }
            if (!seen.add(key)) continue
            if (snap.receiving && file.name == snap.name) continue
            val apk = ApkBatch.isApk(file)
            val title = if (apk) ApkBatch.displayName(file) else file.name.ifBlank { "File" }
            when (file.installMark) {
                "installing", "replacing" -> rows += QueueRow(IN_PROGRESS, title, "${snap.installPercent}%", snap.installPercent, true, apk)
                "pending" -> rows += QueueRow(PENDING, title, PENDING, 0, false, apk)
                "success" -> rows += QueueRow(FINISHED, title, INSTALLED, 100, false, apk)
                "needs-tap", "failed" -> rows += QueueRow(FINISHED, title, TAP, 100, false, apk)
                else -> {
                    val installed = apk && ApkBatch.status(file) == ApkBatch.INSTALLED
                    val detail = when {
                        installed -> INSTALLED
                        apk -> TAP
                        else -> SAVED
                    }
                    rows += QueueRow(FINISHED, title, detail, 100, false, apk)
                }
            }
        }
        return rows.sortedBy { sectionRank(it.section) }
    }

    private fun sectionRank(section: String): Int = when (section) {
        IN_PROGRESS -> 0
        PENDING -> 1
        else -> 2
    }
}
