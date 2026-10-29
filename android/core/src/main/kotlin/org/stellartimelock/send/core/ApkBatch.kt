package org.stellartimelock.send.core

/** Version comparison, dedupe, and install-all summary for a batch of received APKs. */
object ApkBatch {
    const val INSTALLED = "Installed"
    const val UPDATE = "Update"
    const val NOT_INSTALLED = "Not installed"
    const val NEEDS_TAP = "Needs your tap"
    /** Incoming apps that still need an install session. Not a FlipSend tap list. */
    const val NEEDS_INSTALL = UPDATE
    const val INSTALLING = "Installing"
    const val REPLACING = "Replacing"
    const val OLDER = "Older than installed"
    const val FAILED = "Failed"

    const val REASON_SIGNATURE = "signature mismatch"
    const val REASON_OLDER = "older version than installed"
    const val REASON_UNKNOWN_APPS = "Install unknown apps is off"
    const val REASON_DUPLICATE = "duplicate older APK"
    const val REASON_STORAGE = "not enough storage"
    const val REASON_INCOMPATIBLE = "this APK does not work on this phone"
    const val REASON_BLOCKED = "the phone blocked the install"
    const val REASON_INVALID = "the APK is not valid"
    const val REASON_CANCELLED = "install was cancelled"
    const val REASON_INCOMPLETE = "the install did not finish"

    fun isApk(kind: String, name: String): Boolean =
        kind == "apk" || name.endsWith(".apk", ignoreCase = true)

    fun isApk(file: ReceiveFile): Boolean = isApk(file.kind, file.name)

    fun appName(file: ReceiveFile): String =
        file.appName.trim().ifEmpty { file.packageName.trim().ifEmpty { file.name.trim().ifEmpty { "file" } } }

    /** A person's name for the app. Raw APK filenames are not shown as the title. */
    fun displayName(file: ReceiveFile): String {
        val given = file.appName.trim()
        val fileName = file.name.substringAfterLast('/').substringAfterLast('\\').trim()
        if (given.isNotEmpty() && !looksLikeFileName(given, fileName)) return given
        val source = when {
            given.isNotEmpty() -> given
            fileName.isNotEmpty() -> fileName
            else -> return "App"
        }
        val leaf = source.substringAfterLast('/').substringAfterLast('\\')
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
        return leaf.ifEmpty { "App" }
    }

    fun status(file: ReceiveFile): String {
        if (!isApk(file)) return ""
        // The installed version wins when the session mark is still "installing".
        if (file.hasInstalled && file.incomingCode >= 0 && file.installedCode >= file.incomingCode) {
            return if (file.incomingCode < file.installedCode) OLDER else INSTALLED
        }
        when (file.installMark) {
            "installing", "pending" -> return INSTALLING
            "replacing" -> return REPLACING
            "success" -> return INSTALLED
        }
        if (isOlder(file)) return OLDER
        when (file.installMark) {
            "needs-tap" -> return NEEDS_TAP
            "failed" -> return FAILED
        }
        if (!file.hasInstalled) return NOT_INSTALLED
        if (file.incomingCode < 0 || file.installedCode < 0) return UPDATE
        return if (file.incomingCode > file.installedCode) UPDATE else INSTALLED
    }

    /** The row label, with a short reason when this app failed or still needs a tap. */
    fun statusLine(file: ReceiveFile, duplicate: Boolean = false): String {
        if (duplicate) return "$FAILED: $REASON_DUPLICATE"
        val label = status(file)
        val why = explanation(file)
        return if ((label == FAILED || label == NEEDS_TAP) && why.isNotBlank()) "$label: $why" else label
    }

    /**
     * Plain reason for the attention list. Blank when the status line already says everything
     * (Installed, Installing) or when no specific reason is known yet.
     */
    fun explanation(file: ReceiveFile, duplicate: Boolean = false): String {
        if (duplicate) return REASON_DUPLICATE
        if (status(file) == OLDER) return REASON_OLDER
        val label = status(file)
        if (label != FAILED && label != NEEDS_TAP) return ""
        val detail = file.installDetail.trim()
        if (detail.isNotEmpty() && !isProgressNote(detail)) return detail
        if (file.signatureMatch == ApkSignatures.DIFFER) return REASON_SIGNATURE
        return ""
    }

    /** Short text stored on the row for a PackageInstaller outcome. */
    fun detailFor(outcome: InstallOutcome): String = when (outcome) {
        InstallOutcome.SUCCESS, InstallOutcome.PENDING, InstallOutcome.NEEDS_TAP -> ""
        InstallOutcome.ABORTED -> REASON_CANCELLED
        InstallOutcome.BLOCKED -> REASON_BLOCKED
        InstallOutcome.INVALID -> REASON_INVALID
        InstallOutcome.CONFLICT -> REASON_SIGNATURE
        InstallOutcome.STORAGE -> REASON_STORAGE
        InstallOutcome.INCOMPATIBLE -> REASON_INCOMPATIBLE
        InstallOutcome.FAILED -> REASON_INCOMPLETE
    }

    /** Incoming version compared with what is already on the phone. */
    fun versionLine(file: ReceiveFile): String {
        val incoming = version(file.incomingVersion, file.incomingCode)
        val installed = if (!file.hasInstalled) {
            "not installed"
        } else {
            "installed ${version(file.installedVersion, file.installedCode)}"
        }
        return "$incoming vs $installed"
    }

    /**
     * An install session is useful only when the offered APK is newer or missing, or the last try failed.
     * An older APK is left alone so the installed app and its data stay as they are.
     */
    fun needsSession(file: ReceiveFile): Boolean {
        if (file.packageName.isBlank() || file.path.isBlank()) return false
        if (isOlder(file)) return false
        val label = status(file)
        return label == UPDATE || label == NOT_INSTALLED || label == NEEDS_TAP || label == FAILED
    }

    /**
     * One row per package: the highest incoming version, and the later file when versions tie.
     * Files whose package name is still unknown stay visible and are not grouped together.
     */
    fun offered(files: List<ReceiveFile>): List<ReceiveFile> {
        val apks = files.filter { isApk(it) }
        val best = HashMap<String, Int>()
        apks.forEachIndexed { index, file ->
            val pkg = file.packageName.trim()
            if (pkg.isEmpty()) return@forEachIndexed
            val current = best[pkg]
            if (current == null || newerThan(file, apks[current], index, current)) best[pkg] = index
        }
        return apks.filterIndexed { index, file ->
            val pkg = file.packageName.trim()
            pkg.isEmpty() || best[pkg] == index
        }
    }

    /** Older copies of an app that already has a newer APK in this list. */
    fun hidden(files: List<ReceiveFile>): List<ReceiveFile> {
        val keep = offered(files).map { it.id }.toSet()
        return files.filter { isApk(it) && it.id !in keep }
    }

    fun actionable(files: List<ReceiveFile>): List<ReceiveFile> = offered(files).filter { needsSession(it) }

    /** Offered APKs that are not installed yet. A hidden older copy is not included. */
    fun attentionCount(files: List<ReceiveFile>): Int = offered(files).count { status(it) != INSTALLED }

    fun summary(files: List<ReceiveFile>): String {
        val apks = offered(files)
        if (apks.isEmpty()) return ""
        val body = summaryBody(apks)
        return if (apks.any { status(it) == INSTALLING }) "Installing. $body" else body
    }

    /**
     * Android 12+ can apply an update with no extra prompt only when the signature matches,
     * this app is the installer of record (or is updating itself), and targetSdk is high enough.
     * A first install, a different installer, or a different signature still needs one confirmation.
     */
    fun silentUpdate(
        sdk: Int,
        alreadyInstalled: Boolean,
        installedBySelf: Boolean,
        signatureMatch: String,
        targetSdk: Int,
        updatingSelf: Boolean = false
    ): Boolean = ApkAuto.canUpdateSilently(sdk, alreadyInstalled, installedBySelf, signatureMatch, targetSdk, updatingSelf)

    private fun summaryBody(apks: List<ReceiveFile>): String {
        val installed = apks.count { status(it) == INSTALLED }
        val total = apks.size
        return "$installed of $total installed"
    }

    private fun isOlder(file: ReceiveFile): Boolean =
        file.hasInstalled && file.incomingCode >= 0 && file.installedCode >= 0 && file.incomingCode < file.installedCode

    private fun newerThan(file: ReceiveFile, chosen: ReceiveFile, index: Int, chosenIndex: Int): Boolean {
        if (file.incomingCode != chosen.incomingCode) return file.incomingCode > chosen.incomingCode
        return index > chosenIndex
    }

    private fun isProgressNote(detail: String): Boolean =
        detail == "Starting the install." ||
            detail == "Installing the update." ||
            detail == "Ready to install from the notification." ||
            detail.startsWith("Saved.")

    private fun looksLikeFileName(given: String, fileName: String): Boolean =
        given.equals(fileName, ignoreCase = true) || given.endsWith(".apk", ignoreCase = true)

    private fun version(name: String, code: Long): String {
        val label = name.trim().ifEmpty { "unknown" }
        return if (code >= 0) "$label ($code)" else label
    }
}
