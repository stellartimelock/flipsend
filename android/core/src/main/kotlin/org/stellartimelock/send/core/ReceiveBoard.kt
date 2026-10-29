package org.stellartimelock.send.core

data class ReceiveFile(
    val id: String,
    val name: String,
    val kind: String,
    val path: String,
    val appName: String = "",
    val packageName: String = "",
    val incomingVersion: String = "",
    val incomingCode: Long = -1,
    val installedVersion: String = "",
    val installedCode: Long = -1,
    val hasInstalled: Boolean = false,
    val installMark: String = "",
    val installDetail: String = "",
    val signatureMatch: String = "",
    val targetSdk: Int = -1,
    val installedBySelf: Boolean = false
)

data class ReceiveSnap(
    val sender: String,
    val receiving: Boolean,
    val index: Int,
    val total: Int,
    val name: String,
    val bytes: Long,
    val size: Long,
    val files: List<ReceiveFile>,
    val installName: String = "",
    val installBytes: Long = 0,
    val installTotal: Long = 0
) {
    val status: String get() = ReceiveStatus.line(receiving, index, total, name)
    val senderLine: String get() = ReceiveStatus.senderLine(sender)
    val percent: Int
        get() = if (size <= 0) 0 else ((bytes * 100) / size).toInt().coerceIn(0, 100)
    val installing: Boolean
        get() = files.any { it.installMark == "installing" || it.installMark == "pending" || it.installMark == "replacing" } ||
            installName.isNotBlank() ||
            installTotal > 0
    val installPercent: Int
        get() = if (installTotal <= 0) 0 else ((installBytes * 100) / installTotal).toInt().coerceIn(0, 100)
    val barPercent: Int
        get() = if (receiving) percent else if (installing) installPercent else percent
    val barStatus: String
        get() = ReceiveStatus.pageLine(this)
    val showBar: Boolean
        get() = receiving || installing
}

/** In-memory picture of the batch currently arriving on this phone. */
class ReceiveBoard {
    private var sender: String = ""
    private var receiving = false
    private var index = 0
    private var total = 0
    private var name = ""
    private var bytes = 0L
    private var size = 0L
    private var transferId = ""
    private var installName = ""
    private var installBytes = 0L
    private var installTotal = 0L
    private val files = mutableListOf<ReceiveFile>()

    fun noteSender(name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) sender = clean
    }

    fun noteProgress(
        id: String,
        fileName: String,
        done: Long,
        expected: Long,
        batchIndex: Int,
        batchCount: Int
    ) {
        val started = id != transferId
        if (started && batchIndex == 1) {
            files.clear()
            total = 0
        }
        if (started) {
            transferId = id
            index = if (batchIndex > 0) batchIndex else files.size + 1
            if (batchCount > 1) total = batchCount
        }
        receiving = true
        name = fileName.trim().ifEmpty { "a file" }
        bytes = done.coerceAtLeast(0)
        size = expected.coerceAtLeast(0)
    }

    fun noteFile(
        id: String,
        fileName: String,
        kind: String,
        path: String,
        appName: String = "",
        packageName: String = "",
        incomingVersion: String = "",
        incomingCode: Long = -1,
        installedVersion: String = "",
        installedCode: Long = -1,
        hasInstalled: Boolean = false
    ) {
        if (files.any { it.id == id || (path.isNotEmpty() && it.path == path) }) return
        files += ReceiveFile(
            id, fileName, kind, path, appName, packageName,
            incomingVersion, incomingCode, installedVersion, installedCode, hasInstalled
        )
    }

    fun notePackage(
        id: String,
        appName: String,
        packageName: String,
        incomingVersion: String,
        incomingCode: Long,
        installedVersion: String,
        installedCode: Long,
        hasInstalled: Boolean,
        signatureMatch: String = "",
        targetSdk: Int = -1,
        installedBySelf: Boolean = false
    ) {
        val index = files.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = files[index]
        files[index] = current.copy(
            appName = appName,
            packageName = packageName,
            incomingVersion = incomingVersion,
            incomingCode = incomingCode,
            installedVersion = installedVersion,
            installedCode = installedCode,
            hasInstalled = hasInstalled,
            signatureMatch = signatureMatch.ifBlank { current.signatureMatch },
            targetSdk = if (targetSdk >= 0) targetSdk else current.targetSdk,
            installedBySelf = installedBySelf || current.installedBySelf
        )
    }

    /**
     * Records an install result. A blank [detail] keeps the previous reason, so a later
     * status callback does not wipe a reason that was already written. An empty mark,
     * a success, or a fresh attempt clears it. Does not uninstall or clear app data.
     */
    fun noteInstall(id: String, mark: String, detail: String = "") {
        val index = files.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = files[index]
        val success = mark == "success"
        val fresh = mark.isEmpty() || success || mark == "installing" || mark == "pending" || mark == "replacing"
        files[index] = current.copy(
            installMark = mark,
            installDetail = when {
                fresh -> ""
                detail.isNotBlank() -> detail
                else -> current.installDetail
            },
            hasInstalled = current.hasInstalled || success,
            installedVersion = if (success) current.incomingVersion.ifBlank { current.installedVersion } else current.installedVersion,
            installedCode = if (success && current.incomingCode >= 0) current.incomingCode else current.installedCode
        )
    }

    fun noteIdle() {
        receiving = false
        transferId = ""
        name = ""
        index = files.size
        if (files.isNotEmpty()) {
            bytes = 1
            size = 1
        } else {
            bytes = 0
            size = 0
        }
    }

    /** Bytes written into the current PackageInstaller session. A moving bar, not a spinner. */
    fun noteInstallProgress(appName: String, done: Long, expected: Long) {
        installName = appName.trim()
        installBytes = done.coerceAtLeast(0)
        installTotal = expected.coerceAtLeast(0)
    }

    fun clearInstallProgress() {
        installName = ""
        installBytes = 0
        installTotal = 0
    }

    fun clear() {
        sender = ""
        receiving = false
        index = 0
        total = 0
        name = ""
        bytes = 0
        size = 0
        transferId = ""
        installName = ""
        installBytes = 0
        installTotal = 0
        files.clear()
    }

    fun snap(): ReceiveSnap = ReceiveSnap(
        sender, receiving, index, total, name, bytes, size, files.toList(),
        installName, installBytes, installTotal
    )
}

object ReceiveStatus {
    const val READY = "Ready to receive"
    const val WAITING = READY
    const val MIN_SP = 20f
    const val META_SP = 16f
    const val SECTION_SP = 13f
    const val STATUS_SP = 22f
    const val TITLE_SP = 26f

    fun line(receiving: Boolean, index: Int, total: Int, name: String): String {
        val file = name.trim().ifEmpty { "a file" }
        if (receiving) {
            if (total > 1 && index in 1..total) return "Receiving $index of $total: $file"
            if (index > 1) return "Receiving $index: $file"
            return "Receiving $file"
        }
        if (index > 0 && total > index) return "Received $index of $total"
        if (index > 0) {
            val noun = if (index == 1) "file" else "files"
            return "Received $index $noun"
        }
        return READY
    }

    fun pageLine(snap: ReceiveSnap): String {
        if (snap.receiving) return snap.status
        if (snap.installing) {
            val replacing = snap.files.firstOrNull { it.installMark == "replacing" }
            if (replacing != null && snap.files.none { it.installMark == "installing" || it.installMark == "pending" }) {
                return replacingLine(snap.installName.ifBlank { ApkBatch.displayName(replacing) })
            }
            val name = snap.installName.ifBlank {
                snap.files.firstOrNull { it.installMark == "installing" || it.installMark == "pending" }
                    ?.let { ApkBatch.displayName(it) }
                    .orEmpty()
            }
            return installingLine(name, snap.installPercent)
        }
        val asking = snap.files.firstOrNull { it.installMark == "needs-tap" && ApkBatch.isApk(it) }
        if (asking != null && snap.files.none { it.installMark == "installing" || it.installMark == "pending" }) {
            return "Android is asking about ${ApkBatch.displayName(asking)}"
        }
        return idleLine(snap)
    }

    fun idleLine(snap: ReceiveSnap): String {
        val files = snap.files
        if (files.isEmpty()) return READY
        val apks = ApkBatch.offered(files)
        val extras = files.count { !ApkBatch.isApk(it) }
        if (apks.isNotEmpty() && extras == 0) {
            val installed = apks.count { ApkBatch.status(it) == ApkBatch.INSTALLED }
            if (installed == apks.size) {
                return if (installed == 1) "1 app installed" else "$installed apps installed"
            }
            if (installed > 0) return "$installed of ${apks.size} apps installed"
            return if (apks.size == 1) "Received 1 app" else "Received ${apks.size} apps"
        }
        val n = files.size
        val noun = if (n == 1) "file" else "files"
        return "Received $n $noun"
    }

    fun senderLine(name: String): String = "From ${name.trim().ifEmpty { "your computer" }}"

    fun action(kind: String, name: String): String =
        if (kind == "apk" || name.endsWith(".apk", ignoreCase = true)) "Install" else "Open"

    fun installingLine(appName: String, percent: Int): String {
        val name = appName.trim().ifEmpty { "app" }
        val shown = percent.coerceIn(0, 100)
        return "Installing $name · $shown%"
    }

    fun replacingLine(appName: String): String = "Replacing ${appName.trim().ifEmpty { "app" }}"
}
