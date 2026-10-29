package org.stellartimelock.send.core

/**
 * Which received APKs can update in place with no extra tap.
 *
 * Incoming files from the paired PC are accepted without a FlipSend
 * notification or a FlipSend tap. Incoming APKs are installed without a
 * FlipSend tap. FlipSend always starts a PackageInstaller session. A silent
 * update uses USER_ACTION_NOT_REQUIRED and replaces the installed app in
 * place, keeping its data. If Android still asks, its own confirm sheet is
 * shown.
 *
 * FlipSend updating FlipSend is the same path (Wi-Fi first, hosted relay
 * fallback). A background silent self-update is not requested — Android 14+
 * often returns STATUS_FAILURE_BLOCKED for that, the same 1.5.20 bug. See
 * [SelfUpdate]. FlipSend is never uninstalled.
 *
 * An incoming update of an app already on the phone replaces the old copy and
 * installs the new one on its own. No FlipSend confirm and no Clean replace tap.
 * Android may still show its own sheet for a first install.
 *
 * Data apps (wallet, OS, Fortress, Text Manager, Delivery Driver) are never
 * uninstalled. They update in place when the signer matches. They are skipped
 * when already installed and an in-place update is not possible.
 */
object ApkAuto {
    const val SKIP_PROTECTED = "Skipped: cannot update without wiping data"

    data class Plan(
        val silent: List<ReceiveFile>,
        val ready: List<ReceiveFile>,
        val skipped: List<ReceiveFile> = emptyList(),
        val replace: List<ReceiveFile> = emptyList()
    ) {
        val uninstalls: Boolean get() = replace.isNotEmpty()

        /** Replaces first, then silent and first-installs. FlipSend last of all. */
        fun enqueueOrder(selfPackage: String): List<ReceiveFile> =
            selfLast(replace + silent + ready, selfPackage)
    }

    /**
     * Lowest targetSdk of the APK being installed for which Android will honor
     * USER_ACTION_NOT_REQUIRED. The floor moves up with the device:
     * 29 on Android 12 and 12L, 30 on 13, 31 on 14, 33 on 15, 34 on 16, 35 on 17.
     * Below Android 12 there is no silent update.
     */
    fun minTargetSdk(deviceSdk: Int): Int = when {
        deviceSdk >= 37 -> 35
        deviceSdk >= 36 -> 34
        deviceSdk >= 35 -> 33
        deviceSdk >= 34 -> 31
        deviceSdk >= 33 -> 30
        deviceSdk >= 31 -> 29
        else -> Int.MAX_VALUE
    }

    fun targetSdkAllowsSilent(deviceSdk: Int, targetSdk: Int): Boolean {
        if (deviceSdk < 31 || targetSdk < 1) return false
        return targetSdk >= minTargetSdk(deviceSdk)
    }

    /**
     * True only for an in-place update Android can apply without a confirmation.
     * [updatingSelf] is the platform's extra allowance when this app is replacing itself.
     */
    fun canUpdateSilently(
        deviceSdk: Int,
        alreadyInstalled: Boolean,
        installedBySelf: Boolean,
        signatureMatch: String,
        targetSdk: Int,
        updatingSelf: Boolean = false
    ): Boolean {
        if (!alreadyInstalled) return false
        if (signatureMatch != ApkSignatures.MATCH) return false
        if (!targetSdkAllowsSilent(deviceSdk, targetSdk)) return false
        if (updatingSelf) return true
        return installedBySelf
    }

    /**
     * Every file from the paired computer is accepted with no Allow tap and no
     * consent notification. Unpaired or pairing senders still ask. Pairing is
     * unchanged.
     */
    fun autoAcceptOffer(name: String, paired: Boolean): Boolean = paired

    /**
     * True when this package is a data app that is already installed and cannot
     * be updated in place. A different signer would need an uninstall, which
     * wipes data. First installs, and same-signer in-place updates, are allowed.
     */
    fun skipProtected(
        packageName: String,
        alreadyInstalled: Boolean,
        signatureMatch: String
    ): Boolean {
        if (packageName !in PROTECTED_DATA_PACKAGES) return false
        if (!alreadyInstalled) return false
        return signatureMatch != ApkSignatures.MATCH
    }

    /**
     * These packages are never uninstalled, even for an incoming update.
     * FlipSend itself is included when [selfPackage] matches so a self-update
     * cannot take the uninstall-then-install path.
     */
    fun neverUninstall(packageName: String, selfPackage: String = ""): Boolean {
        if (packageName.isBlank()) return false
        if (packageName in PROTECTED_DATA_PACKAGES) return true
        return selfPackage.isNotBlank() && packageName == selfPackage
    }

    /**
     * Incoming update of an app already on the phone. Same-signer updates install
     * in place with PackageInstaller. Uninstall-then-install is only for a
     * different signer on a non-protected app. FlipSend itself stays running.
     * Protected apps stay.
     */
    fun shouldReplace(
        packageName: String,
        alreadyInstalled: Boolean,
        selfPackage: String,
        signatureMatch: String = ""
    ): Boolean {
        if (!alreadyInstalled) return false
        if (neverUninstall(packageName, selfPackage)) return false
        if (packageName.isBlank()) return false
        return signatureMatch == ApkSignatures.DIFFER
    }

    /**
     * Incoming apps from the paired PC never wait for a tap inside FlipSend.
     * The install session is started as soon as the file is saved.
     */
    fun waitsForFlipSendTap(): Boolean = false

    /**
     * If Android still wants a confirm, keep the session and show its sheet.
     * Do not drop the install and wait for a FlipSend tap that never comes.
     */
    fun abandonsIfAndroidAsks(): Boolean = false

    /**
     * Incoming PC apps always ask Android for no extra tap. Android still
     * confirms a first install, or any update it will not apply by itself.
     */
    fun requestNoUserActionForIncoming(): Boolean = true

    /** Incoming PC apps always start an install session. */
    fun startIncomingSession(): Boolean = true

    /** FlipSend's own package always installs last so the session can finish. */
    fun selfLast(files: List<ReceiveFile>, selfPackage: String): List<ReceiveFile> {
        if (selfPackage.isBlank()) return files
        val rest = files.filter { it.packageName != selfPackage }
        val self = files.filter { it.packageName == selfPackage }
        return rest + self
    }

    /** Incoming apps do not post FlipSend notifications, including tap-to-install. */
    fun notifyIncomingApps(): Boolean = false

    /** Split a settled batch. Older and already-current APKs are left out of both lists. */
    fun plan(files: List<ReceiveFile>, deviceSdk: Int, selfPackage: String): Plan {
        val silent = ArrayList<ReceiveFile>()
        val ready = ArrayList<ReceiveFile>()
        val skipped = ArrayList<ReceiveFile>()
        val replace = ArrayList<ReceiveFile>()
        for (file in ApkBatch.offered(files)) {
            if (!ApkBatch.needsSession(file)) continue
            val self = selfPackage.isNotBlank() && file.packageName == selfPackage && file.hasInstalled
            val silentOk = canUpdateSilently(
                deviceSdk,
                file.hasInstalled,
                file.installedBySelf,
                file.signatureMatch,
                file.targetSdk,
                self
            )
            when {
                skipProtected(file.packageName, file.hasInstalled, file.signatureMatch) -> skipped += file
                shouldReplace(file.packageName, file.hasInstalled, selfPackage, file.signatureMatch) -> replace += file
                silentOk -> silent += file
                else -> ready += file
            }
        }
        return Plan(selfLast(silent, selfPackage), selfLast(ready, selfPackage), skipped, selfLast(replace, selfPackage))
    }

    /** A batch can be installed once every offered file has been saved and nothing is in flight. */
    fun batchSettled(snap: ReceiveSnap): Boolean {
        if (snap.receiving || snap.files.isEmpty()) return false
        if (snap.total > 1) return snap.files.size >= snap.total
        return true
    }

    fun updatedLine(names: List<String>): String {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return ""
        return "Updated: " + clean.joinToString(", ")
    }

    fun readyLine(count: Int): String = when {
        count <= 0 -> ""
        count == 1 -> "1 app ready to install"
        else -> "$count apps ready to install"
    }
}

/**
 * Packages whose app data must not be wiped. Auto-install skips any of these that cannot be
 * updated in place (a different signer). A same-signer in-place update that keeps data is
 * always allowed, even if Android still shows its own confirm sheet.
 */
val PROTECTED_DATA_PACKAGES = setOf(
    "com.stellartimelock",
    "com.stellartimelock.stellaros",
    "com.stellartimelock.stellarvault",
    "app.textpipe",
    "app.stellar.deliverydriver"
)

/** The on-phone switch for installs that arrive from the paired PC. Plain JSON, not a device key. */
object PcInstallPreference {
    const val DEFAULT = true

    fun encode(enabled: Boolean): ByteArray =
        """{"autoInstallFromPc":$enabled}""".toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): Boolean? {
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) return null
        val parsed = try {
            org.json.JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        if (!parsed.has("autoInstallFromPc")) return null
        return parsed.optBoolean("autoInstallFromPc", DEFAULT)
    }
}

/**
 * PackageInstaller session status must arrive as a broadcast. An Activity
 * PendingIntent is BAL-blocked on Android 14/15 when FlipSend has no visible
 * window, so STATUS_PENDING_USER_ACTION never arrives and no prompt is shown.
 */
object ApkInstallDelivery {
    const val STATUS_CALLBACK = "broadcast"

    fun openConfirmNow(appVisible: Boolean): Boolean = appVisible

    /** Heads-up only when FlipSend has no visible window; otherwise openConfirmNow already surfaces the prompt. */
    fun notifyConfirm(pendingUserAction: Boolean, appVisible: Boolean = false): Boolean =
        pendingUserAction && !appVisible
}
