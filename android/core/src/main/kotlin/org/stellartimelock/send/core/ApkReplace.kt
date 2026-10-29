package org.stellartimelock.send.core

data class ApkPackageInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val label: String = "",
    val certificates: List<String> = emptyList(),
    val targetSdk: Int = -1
)

data class ReplacePreview(
    val title: String,
    val body: String,
    val uninstallFirst: Boolean,
    val saveMissing: Boolean,
    val offerCleanReplace: Boolean = false
)

enum class InstallOutcome {
    SUCCESS,
    PENDING,
    ABORTED,
    BLOCKED,
    INVALID,
    CONFLICT,
    STORAGE,
    INCOMPATIBLE,
    FAILED,
    /** A silent update still needs a person. The session was not left waiting on a system dialog. */
    NEEDS_TAP
}

/** SHA-256 fingerprints of signing certificates. Blank means the certificates could not be read. */
object ApkSignatures {
    const val MATCH = "match"
    const val DIFFER = "differ"

    fun compare(installedCerts: Collection<String>, incomingCerts: Collection<String>): String {
        val installed = normalize(installedCerts)
        val incoming = normalize(incomingCerts)
        if (installed.isEmpty() || incoming.isEmpty()) return ""
        return if (installed == incoming) MATCH else DIFFER
    }

    private fun normalize(certs: Collection<String>): Set<String> =
        certs.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}

object ApkReplacePreview {
    /** Clean replace uninstalls the app. It never deletes the Documents/Stellar save. */
    fun deletesSaveFiles(): Boolean = false

    /**
     * After an in-place update fails, offer Clean replace once.
     * A failure that was already a clean replace does not ask again.
     */
    fun offerAfterFailure(alreadyInstalled: Boolean, self: Boolean, wasCleanReplace: Boolean): Boolean =
        alreadyInstalled && !self && !wasCleanReplace

    fun isApk(name: String): Boolean {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        return leaf.endsWith(".apk", ignoreCase = true)
    }

    fun saveFileName(packageName: String): String {
        val cleaned = packageName.trim().map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
        }.joinToString("").trim('.', '_', '-')
        return (cleaned.ifBlank { "app" }) + ".json"
    }

    fun preview(
        apkName: String,
        incoming: ApkPackageInfo,
        installed: ApkPackageInfo?,
        save: SaveSummary,
        userFolderLabel: String,
        selfPackage: String?,
        signatureMatch: String = "",
        preferClean: Boolean = false
    ): ReplacePreview {
        val saveMissing = !save.exists
        val saveLine = if (save.exists) {
            save.describe()
        } else {
            "Warning: save file ${save.name} was not found in $userFolderLabel. Uninstalling removes the app's private data. Continue only if that save is already in your folder."
        }
        val self = selfPackage != null && incoming.packageName == selfPackage
        val installedApp = installed != null && !self
        val differ = signatureMatch == ApkSignatures.DIFFER
        val uninstallFirst = installedApp && (preferClean || differ)
        val offerCleanReplace = installedApp && !uninstallFirst
        val kept = "The save file in $userFolderLabel is not deleted. Opening the app again restores its data from that save."
        val installedLine = if (installed == null) {
            "${incoming.packageName} is not installed on this phone. The APK is ${incoming.versionName ?: "an unknown version"} (code ${incoming.versionCode})."
        } else {
            "${incoming.packageName} is installed (version ${installed.versionName ?: "unknown"}, code ${installed.versionCode}). The new APK is version ${incoming.versionName ?: "unknown"}, code ${incoming.versionCode}."
        }
        val action = when {
            self -> "FlipSend will replace itself in one install step. Android removes the old copy as part of that install, because this app has to stay running long enough to finish it. $kept"
            uninstallFirst -> "Clean replace asks you to uninstall ${incoming.packageName}, then installs $apkName. The old app is removed only after you confirm the system screen. $kept"
            installed != null -> "FlipSend will update ${incoming.packageName} in place and keep the app's data. $kept"
            else -> "FlipSend will install $apkName. No uninstall is needed. The save file in $userFolderLabel is not deleted."
        }
        val title = if (installed != null) "Replace ${incoming.packageName}" else "Install ${incoming.packageName}"
        return ReplacePreview(
            title = title,
            body = listOf(installedLine, saveLine, action).joinToString("\n\n"),
            uninstallFirst = uninstallFirst,
            saveMissing = saveMissing,
            offerCleanReplace = offerCleanReplace
        )
    }
}

/** Buttons on a received-file row. Install all stays an in-place update and is not decided here. */
object ApkRowActions {
    const val INSTALL = "Install"
    const val CLEAN_REPLACE = "Clean replace"
    const val ALLOW = "Allow installs"

    fun labels(file: ReceiveFile, selfPackage: String = "", duplicate: Boolean = false): List<String> {
        if (duplicate) return emptyList()
        if (!ApkBatch.isApk(file)) return listOf(ReceiveStatus.action(file.kind, file.name))
        val status = ApkBatch.status(file)
        if (status == ApkBatch.INSTALLED || status == ApkBatch.OLDER || status == ApkBatch.INSTALLING || status == ApkBatch.REPLACING) return emptyList()
        if (ApkBatch.explanation(file) == ApkBatch.REASON_UNKNOWN_APPS) return listOf(ALLOW)
        val self = selfPackage.isNotBlank() && file.packageName == selfPackage
        val signature = file.signatureMatch == ApkSignatures.DIFFER ||
            ApkBatch.explanation(file) == ApkBatch.REASON_SIGNATURE
        if (!file.hasInstalled || self || status == ApkBatch.UPDATE || status == ApkBatch.NOT_INSTALLED || status == ApkBatch.FAILED || status == ApkBatch.NEEDS_TAP) {
            return listOf(INSTALL)
        }
        return emptyList()
    }
}

object InstallStatuses {
    fun plain(outcome: InstallOutcome, raw: String?): String {
        val fallback = when (outcome) {
            InstallOutcome.SUCCESS -> "Installed."
            InstallOutcome.PENDING -> "Installing."
            InstallOutcome.ABORTED -> "Install was cancelled."
            InstallOutcome.BLOCKED -> "The phone blocked the install."
            InstallOutcome.INVALID -> "The APK is not valid."
            InstallOutcome.CONFLICT -> "This APK conflicts with an app already on the phone."
            InstallOutcome.STORAGE -> "There is not enough storage to install."
            InstallOutcome.INCOMPATIBLE -> "This APK does not work on this phone."
            InstallOutcome.FAILED -> "The install did not finish."
            InstallOutcome.NEEDS_TAP -> "Android still needs a tap before this app can be installed."
        }
        if (outcome == InstallOutcome.SUCCESS || outcome == InstallOutcome.PENDING || outcome == InstallOutcome.ABORTED || outcome == InstallOutcome.NEEDS_TAP) {
            return fallback
        }
        val extra = raw?.trim()?.lineSequence()?.firstOrNull()?.take(180)?.let { LogRedaction.redact(it) }
        if (extra.isNullOrBlank() || extra == fallback) return fallback
        return "$fallback $extra"
    }
}

object ReplaceProgressText {
    fun reading() = "Reading the APK."
    fun uninstallWait(packageName: String) = "Replacing $packageName."
    fun uninstallCancelled() = "Uninstall was cancelled. The old app is still installed, so the new APK was not installed."
    fun removedInstalling() = "Old app removed. Installing the new APK."
    fun permission() = "Allow FlipSend to install apps, then try the replace again."
    fun notAnApk() = "That file is not an APK."
    fun unreadable() = "Could not read the package name from this APK."
    fun sessionError(message: String) = "Could not start the install: $message"
    fun installed(packageName: String) = "Installed $packageName."
    fun installFailed(reason: String) = "Install failed: $reason The APK file is still on this phone."
    fun sentApk() = "Sent. On the phone, a matching signature updates the app in place. Clean replace is there if you want it, and the save file is not deleted."
}
