package org.stellartimelock.send

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.stellartimelock.send.core.ApkAuto
import org.stellartimelock.send.core.ApkBatch
import org.stellartimelock.send.core.ApkInstallDelivery
import org.stellartimelock.send.core.ApkPackageInfo
import org.stellartimelock.send.core.ApkReplacePreview
import org.stellartimelock.send.core.ApkSignatures
import org.stellartimelock.send.core.sha256Hex
import org.stellartimelock.send.core.FileNames
import org.stellartimelock.send.core.InstallOutcome
import org.stellartimelock.send.core.InstallStatuses
import org.stellartimelock.send.core.LogRedaction
import org.stellartimelock.send.core.OverwriteKind
import org.stellartimelock.send.core.ReplacePreview
import org.stellartimelock.send.core.ReplaceProgressText
import org.stellartimelock.send.core.SaveDocuments
import org.stellartimelock.send.core.SaveStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the system-provided confirm [Intent] from STATUS_PENDING_USER_ACTION keyed by history id.
 * The intent is stored when the status arrives and cleared when the session finishes (success or
 * failure). [ApkConfirmActivity] retrieves it when the heads-up notification is tapped.
 */
internal object ApkInstallPending {
    private val intents = java.util.concurrent.ConcurrentHashMap<String, Intent>()

    fun store(historyId: String, intent: Intent) {
        intents[historyId] = intent
    }

    fun retrieve(historyId: String): Intent? = intents.remove(historyId)

    fun clear(historyId: String) {
        intents.remove(historyId)
    }
}

internal object ApkInstallBus {
    private val listeners = CopyOnWriteArrayList<(InstallOutcome, String) -> Unit>()
    private val detailed = CopyOnWriteArrayList<(InstallOutcome, String, String?) -> Unit>()

    fun add(listener: (InstallOutcome, String) -> Unit) {
        listeners += listener
    }

    fun remove(listener: (InstallOutcome, String) -> Unit) {
        listeners -= listener
    }

    fun addDetailed(listener: (InstallOutcome, String, String?) -> Unit) {
        detailed += listener
    }

    fun emit(outcome: InstallOutcome, message: String, historyId: String? = null): Boolean {
        if (listeners.isEmpty() && detailed.isEmpty()) return false
        for (listener in listeners) {
            runCatching { listener(outcome, message) }
        }
        for (listener in detailed) {
            runCatching { listener(outcome, message, historyId) }
        }
        return true
    }
}

/** Handles PackageInstaller status from [ApkInstallReceiver] or [ApkInstallStatusActivity]. */
internal object ApkInstallStatus {
    fun apply(context: Context, intent: Intent, startConfirm: Boolean): Intent? {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        var outcome = ApkInstallReceiver.outcomeOf(status)
        var message = InstallStatuses.plain(outcome, raw)
        val defer = intent.getBooleanExtra("deferUserAction", false)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (defer && outcome != InstallOutcome.PENDING && outcome != InstallOutcome.SUCCESS &&
            ApkInstallReceiver.wasAbandoned(sessionId)
        ) {
            return null
        }
        var confirm: Intent? = null
        if (outcome == InstallOutcome.PENDING && defer) {
            if (sessionId >= 0) {
                ApkInstallReceiver.noteAbandoned(sessionId)
                runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            }
            outcome = InstallOutcome.NEEDS_TAP
            message = InstallStatuses.plain(InstallOutcome.NEEDS_TAP, null)
        } else if (outcome == InstallOutcome.PENDING) {
            confirm = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm != null && startConfirm) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
        }
        val historyId = remember(context, intent, outcome, message)

        // Store the confirm intent so the notification tap can re-launch it.
        // Clear it (and the notification) when the session reaches a terminal state.
        if (historyId != null) {
            when (outcome) {
                InstallOutcome.PENDING -> if (confirm != null) ApkInstallPending.store(historyId, confirm)
                else -> {
                    ApkInstallPending.clear(historyId)
                    Notifier.clearInstallConfirm(context, historyId)
                }
            }
        }

        if (!ApkInstallBus.emit(outcome, message, historyId)) {
            if (outcome != InstallOutcome.PENDING) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }

        if (outcome == InstallOutcome.PENDING && historyId != null &&
            ApkInstallDelivery.notifyConfirm(true, BackgroundListen.hasVisibleWindow())
        ) {
            val appName = resolveAppName(context, historyId)
            Notifier.installConfirmNeeded(context, historyId, appName)
        }

        return if (outcome == InstallOutcome.PENDING) confirm else null
    }

    private fun resolveAppName(context: Context, historyId: String): String {
        val entry = runCatching { ReceivedLog(context).find(historyId) }.getOrNull()
        return entry?.appName?.trim()?.ifBlank { entry.packageName.trim() }
            ?.ifBlank { entry.name } ?: ""
    }

    private fun remember(context: Context, intent: Intent, outcome: InstallOutcome, message: String): String? {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val historyId = intent.getStringExtra("historyId")
        val log = ReceivedLog(context)
        val entry = (if (sessionId >= 0) log.findBySession(sessionId) else null)
            ?: historyId?.let { log.find(it) }
        if (entry != null) {
            val detail = ApkBatch.detailFor(outcome)
            val stored = if (outcome == InstallOutcome.SUCCESS || outcome == InstallOutcome.PENDING) "" else detail
            log.mark(entry.id, ApkInstallReceiver.statusKey(outcome), stored, sessionId)
            if (outcome == InstallOutcome.SUCCESS && ApkBatch.isApk(entry.kind, entry.name)) {
                runCatching { log.file(entry).delete() }
            }
            SessionHub.emit(SessionEvent.ReceivedChanged)
            if (outcome != InstallOutcome.PENDING) {
                val mark = when (outcome) {
                    InstallOutcome.SUCCESS -> "success"
                    InstallOutcome.NEEDS_TAP -> "needs-tap"
                    else -> "failed"
                }
                val listed = ReceiveLive.snap().files.any { it.id == entry.id }
                if (listed) {
                    SessionHub.emit(SessionEvent.Receive(ReceiveLive.edit { noteInstall(entry.id, mark, stored) }))
                }
            }
        }
        return entry?.id ?: historyId
    }
}

class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ApkInstallStatus.apply(
            context,
            intent,
            startConfirm = ApkInstallDelivery.openConfirmNow(BackgroundListen.hasVisibleWindow())
        )
    }

    companion object {
        const val ACTION = "org.stellartimelock.send.action.INSTALL_STATUS"

        private val abandonedSessions = HashSet<Int>()

        fun noteAbandoned(sessionId: Int) {
            synchronized(abandonedSessions) { abandonedSessions += sessionId }
        }

        fun wasAbandoned(sessionId: Int): Boolean = synchronized(abandonedSessions) { sessionId in abandonedSessions }

        fun statusKey(outcome: InstallOutcome): String = when (outcome) {
            InstallOutcome.SUCCESS -> "success"
            InstallOutcome.PENDING -> "pending"
            InstallOutcome.NEEDS_TAP -> "needs-tap"
            else -> "failed"
        }

        fun outcomeOf(status: Int): InstallOutcome = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallOutcome.SUCCESS
            PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallOutcome.PENDING
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.ABORTED
            PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallOutcome.BLOCKED
            PackageInstaller.STATUS_FAILURE_INVALID -> InstallOutcome.INVALID
            PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallOutcome.CONFLICT
            PackageInstaller.STATUS_FAILURE_STORAGE -> InstallOutcome.STORAGE
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallOutcome.INCOMPATIBLE
            else -> InstallOutcome.FAILED
        }
    }
}

/**
 * Legacy PackageInstaller callback activity. New sessions use [ApkInstallReceiver]
 * so Android 15 cannot BAL-block the status. Kept for any session already committed
 * with an Activity PendingIntent.
 */
class ApkInstallStatusActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        ApkInstallStatus.apply(this, intent, startConfirm = true)
        finish()
    }
}

/**
 * Launched by the "Tap to install" heads-up notification. Retrieves the stored system-confirm
 * [Intent] and starts it so the user sees the install dialog even if the direct launch inside
 * [ApkInstallStatusActivity] was blocked by the OEM or a locked screen. Falls back to the
 * receiving screen if the stored intent has already been consumed.
 */
class ApkConfirmActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val historyId = intent.getStringExtra("historyId")
        val confirm = if (historyId != null) ApkInstallPending.retrieve(historyId) else null
        if (confirm != null) {
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { startActivity(confirm) }
            if (opened.isFailure) openReceiving()
        } else {
            openReceiving()
        }
        finish()
    }

    private fun openReceiving() {
        runCatching {
            startActivity(
                Intent(this, ReceivingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }
}

internal object ApkArchives {
    fun read(context: Context, file: File): ApkPackageInfo? {
        if (!file.isFile || !ApkReplacePreview.isApk(file.name)) return null
        val info = archive(context.packageManager, file.absolutePath) ?: return null
        val packageName = info.packageName?.takeIf { it.isNotBlank() } ?: return null
        val appInfo = info.applicationInfo
        var label = ""
        if (appInfo != null) {
            appInfo.sourceDir = file.absolutePath
            appInfo.publicSourceDir = file.absolutePath
            label = runCatching { context.packageManager.getApplicationLabel(appInfo).toString() }.getOrDefault("")
        }
        val targetSdk = appInfo?.targetSdkVersion ?: -1
        return ApkPackageInfo(packageName, info.versionName, versionCode(info), label, certificates(info), targetSdk)
    }

    /** The installed app's icon, or the icon inside the APK file. */
    fun icon(context: Context, packageName: String, apk: File?): android.graphics.drawable.Drawable? {
        if (packageName.isNotBlank()) {
            val installed = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
            if (installed != null) return installed
        }
        if (apk == null || !apk.isFile) return null
        val info = archive(context.packageManager, apk.absolutePath) ?: return null
        val appInfo = info.applicationInfo ?: return null
        appInfo.sourceDir = apk.absolutePath
        appInfo.publicSourceDir = apk.absolutePath
        return runCatching { appInfo.loadIcon(context.packageManager) }.getOrNull()
    }

    /** True when FlipSend is the installer of record for this package. */
    fun installedBySelf(context: Context, packageName: String): Boolean {
        val installer = try {
            if (Build.VERSION.SDK_INT >= 30) {
                val source = context.packageManager.getInstallSourceInfo(packageName)
                if (Build.VERSION.SDK_INT >= 33) {
                    val owner = source.updateOwnerPackageName
                    if (!owner.isNullOrBlank()) return owner == context.packageName
                }
                source.installingPackageName ?: source.initiatingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(packageName)
            }
        } catch (_: Exception) {
            null
        }
        return !installer.isNullOrBlank() && installer == context.packageName
    }

    fun installed(context: Context, packageName: String): ApkPackageInfo? {
        val flags = signingFlags()
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, flags)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: return null
        return ApkPackageInfo(info.packageName, info.versionName, versionCode(info), certificates = certificates(info))
    }

    fun certificates(info: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return emptyList()
        return signatures.map { sha256Hex(it.toByteArray()) }
    }

    fun copyToCache(context: Context, uri: Uri, displayName: String): File {
        val safe = FileNames.sanitize(displayName).let { name ->
            if (name.endsWith(".apk", ignoreCase = true)) name else "$name.apk"
        }
        val dir = File(context.cacheDir, "apk-replace").apply { mkdirs() }
        val dest = File(dir, safe)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the APK.")
        input.use { source ->
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(65536)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.flush()
                runCatching { out.fd.sync() }
            }
        }
        return dest
    }

    private fun archive(manager: PackageManager, path: String): PackageInfo? {
        val flags = signingFlags()
        return if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageArchiveInfo(path, flags)
        }
    }

    private fun signingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    private fun versionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }
}

internal object ApkInstaller {
    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) context.packageManager.canRequestPackageInstalls() else true
    }

    fun uninstallIntent(packageName: String): Intent {
        return Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }

    fun commit(
        context: Context,
        apk: File,
        packageName: String,
        historyId: String? = null,
        autoUpdate: Boolean = false,
        silentUpdate: Boolean? = null,
        deferUserAction: Boolean = false,
        requestUpdateOwnership: Boolean = false,
        dontKillApp: Boolean = false,
        appName: String = "",
        onProgress: ((Long, Long) -> Unit)? = null
    ): Int {
        if (!apk.isFile) throw IOException("The APK file is missing.")
        // MODE_FULL_INSTALL updates an app in place and keeps its data. Clean replace is a separate
        // system uninstall the user confirms first. The Documents/Stellar save is never deleted.
        val installer = context.packageManager.packageInstaller
        InstallProgress.watch(context, installer, appName.ifBlank { packageName })
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            val silent = silentUpdate ?: autoUpdate
            params.setRequireUserAction(ApkSessions.requireUserActionMode(silent))
        }
        if (requestUpdateOwnership && Build.VERSION.SDK_INT >= 34) {
            params.setRequestUpdateOwnership(true)
        }
        if (dontKillApp && Build.VERSION.SDK_INT >= 34) {
            params.setDontKillApp(true)
        }
        val sessionId = installer.createSession(params)
        if (historyId != null) ReceivedLog(context).mark(historyId, "pending", "", sessionId)
        val session = installer.openSession(sessionId)
        try {
            val total = apk.length()
            var written = 0L
            session.openWrite("base.apk", 0, total).use { out ->
                apk.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            runCatching { session.setStagingProgress((written.toFloat() / total.toFloat()).coerceIn(0f, 1f)) }
                        }
                        onProgress?.invoke(written, total)
                    }
                }
                session.fsync(out)
            }
            // Broadcast, not an Activity. On Android 14/15 an Activity PendingIntent
            // from PackageInstaller is BAL-blocked when FlipSend has no visible window,
            // so STATUS_PENDING_USER_ACTION never arrives.
            val pending = ApkSessions.statusPendingIntent(context, sessionId, historyId, deferUserAction)
            session.commit(pending.intentSender)
        } catch (ex: Exception) {
            runCatching { session.abandon() }
            throw ex
        } finally {
            runCatching { session.close() }
        }
        return sessionId
    }

    /**
     * Removes an installed app so the incoming APK can take its place.
     * Protected apps are never passed here. Android may still show its own sheet.
     * FlipSend does not wait for a confirm of its own.
     */
    fun uninstall(context: Context, packageName: String, onDone: (Boolean) -> Unit) {
        if (ApkAuto.neverUninstall(packageName, context.packageName)) {
            onDone(false)
            return
        }
        if (!installed(context, packageName)) {
            onDone(true)
            return
        }
        ApkUninstall.wait(packageName, onDone)
        val callback = Intent(context, ApkUninstallStatusActivity::class.java).apply {
            putExtra(ApkUninstallStatusActivity.EXTRA_PACKAGE, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(context, packageName.hashCode() and 0x7fffffff, callback, flags)
        val started = runCatching {
            context.packageManager.packageInstaller.uninstall(packageName, pending.intentSender)
        }
        if (started.isFailure) {
            ApkUninstall.finish(packageName, false)
        }
    }

    fun installed(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
}

internal object ApkUninstall {
    private val gate = Any()
    private val waiters = HashMap<String, ArrayList<(Boolean) -> Unit>>()

    fun wait(packageName: String, done: (Boolean) -> Unit) {
        synchronized(gate) {
            waiters.getOrPut(packageName) { ArrayList() }.add(done)
        }
    }

    fun finish(packageName: String, ok: Boolean) {
        val list = synchronized(gate) { waiters.remove(packageName).orEmpty() }
        list.forEach { runCatching { it(ok) } }
    }
}

/** Opens Android's uninstall sheet if the system asks. No FlipSend confirm. */
class ApkUninstallStatusActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val opened = runCatching { startActivity(confirm) }
                if (opened.isFailure) ApkUninstall.finish(packageName, false)
                finish()
                return
            }
        }
        val ok = status == PackageInstaller.STATUS_SUCCESS || !ApkInstaller.installed(this, packageName)
        ApkUninstall.finish(packageName, ok)
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "packageName"
    }
}

/** Publishes PackageInstaller write and session progress to the receiving screen. */
internal object InstallProgress {
    private val gate = Any()
    private var hooked = false
    @Volatile
    private var name = ""
    @Volatile
    private var host: Context? = null

    fun watch(context: Context, installer: PackageInstaller, appName: String) {
        val app = context.applicationContext
        synchronized(gate) {
            name = appName
            host = app
            if (hooked) return
            hooked = true
            installer.registerSessionCallback(
                object : PackageInstaller.SessionCallback() {
                    override fun onCreated(sessionId: Int) {}
                    override fun onBadgingChanged(sessionId: Int) {}
                    override fun onActiveChanged(sessionId: Int, active: Boolean) {}
                    override fun onProgressChanged(sessionId: Int, progress: Float) {
                        val total = 1000L
                        val done = (progress * total).toLong().coerceIn(0, total)
                        publish(name, done, total)
                    }
                    override fun onFinished(sessionId: Int, success: Boolean) {
                        if (!success) return
                        val ctx = host ?: return
                        Thread {
                            repeat(8) { attempt ->
                                val entry = ReceivedLog(ctx).findBySession(sessionId) ?: return@Thread
                                val active = entry.installStatus == "installing" ||
                                    entry.installStatus == "pending" ||
                                    entry.installStatus == "replacing"
                                if (!active) return@Thread
                                if (ApkInstallMarks.settledByPackage(ctx, entry.id)) return@Thread
                                if (attempt == 7) return@Thread
                                Thread.sleep(400)
                            }
                        }.start()
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }
    }

    fun publish(appName: String, done: Long, total: Long) {
        if (appName.isNotBlank()) synchronized(gate) { name = appName }
        val snap = ReceiveLive.edit { noteInstallProgress(name.ifBlank { appName }, done, total) }
        SessionHub.emit(SessionEvent.Receive(snap))
    }

    fun clear() {
        synchronized(gate) { name = "" }
        val snap = ReceiveLive.edit { clearInstallProgress() }
        SessionHub.emit(SessionEvent.Receive(snap))
    }
}

object ApkSessions {
    fun statusCallbackIntent(
        context: Context,
        sessionId: Int,
        historyId: String?,
        deferUserAction: Boolean
    ): Intent {
        return Intent(context, ApkInstallReceiver::class.java).apply {
            action = ApkInstallReceiver.ACTION
            setPackage(context.packageName)
            putExtra("historyId", historyId)
            putExtra("deferUserAction", deferUserAction)
            putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
        }
    }

    fun statusPendingIntent(
        context: Context,
        sessionId: Int,
        historyId: String?,
        deferUserAction: Boolean
    ): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            statusCallbackIntent(context, sessionId, historyId, deferUserAction),
            flags
        )
    }

    /**
     * Maps the 'silent' flag to the correct PackageInstaller user-action mode.
     *
     * On Android 14+ (API 34), USER_ACTION_NOT_REQUIRED for a fresh install or a package
     * not owned by this installer returns STATUS_FAILURE_BLOCKED instead of showing the
     * confirm dialog. Use USER_ACTION_UNSPECIFIED for those cases so Android decides: it
     * silently installs when it can and shows the confirm sheet when it must.
     * FlipSend self-update from the background uses unspecified for the same reason.
     */
    fun requireUserActionMode(silent: Boolean): Int {
        return if (silent) {
            PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
        } else {
            PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED
        }
    }
}

class ApkReplaceFlow(
    private val activity: AppCompatActivity,
    private val report: (String) -> Unit
) {
    private var apkFile: File? = null
    private var packageName: String? = null
    private var deleteCache = false
    private var preferClean = false
    private var wasInstalled = false
    private var replacingSelf = false
    private var cleanStarted = false
    private var awaitingInstall = false
    private val listener: (InstallOutcome, String) -> Unit = { outcome, message ->
        activity.runOnUiThread { onInstall(outcome, message) }
    }
    private val uninstallLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onUninstallReturned()
    }
    private val settingsLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (ApkInstaller.canInstall(activity)) beginInstall() else fail(ReplaceProgressText.permission())
    }

    fun attach() {
        ApkInstallBus.add(listener)
    }

    fun detach() {
        ApkInstallBus.remove(listener)
    }

    fun offerFile(path: String) {
        preferClean = false
        openFile(path)
    }

    /** User picked Clean replace, or an in-place update of an installed app just failed. */
    fun offerCleanReplace(path: String) {
        preferClean = true
        openFile(path)
    }

    private fun openFile(path: String) {
        val file = File(path)
        if (!ApkReplacePreview.isApk(file.name)) {
            fail(ReplaceProgressText.notAnApk())
            return
        }
        deleteCache = false
        apkFile = file
        prepare()
    }

    fun offerUri(uri: Uri, displayName: String) {
        preferClean = false
        if (!ApkReplacePreview.isApk(displayName)) {
            fail(ReplaceProgressText.notAnApk())
            return
        }
        report(ReplaceProgressText.reading())
        Thread {
            val copied = runCatching { ApkArchives.copyToCache(activity.applicationContext, uri, displayName) }
            activity.runOnUiThread {
                val file = copied.getOrElse {
                    fail(ReplaceProgressText.unreadable() + " " + LogRedaction.redact(it.message ?: "Could not open the APK."))
                    return@runOnUiThread
                }
                deleteCache = true
                apkFile = file
                prepare()
            }
        }.start()
    }

    private fun prepare() {
        val file = apkFile
        if (file == null) {
            fail(ReplaceProgressText.unreadable())
            return
        }
        report(ReplaceProgressText.reading())
        Thread {
            val read = runCatching { ApkArchives.read(activity.applicationContext, file) }
            activity.runOnUiThread {
                val incoming = read.getOrElse {
                    fail(ReplaceProgressText.unreadable())
                    return@runOnUiThread
                }
                if (incoming == null) {
                    fail(ReplaceProgressText.unreadable())
                    return@runOnUiThread
                }
                packageName = incoming.packageName
                val installed = runCatching { ApkArchives.installed(activity, incoming.packageName) }.getOrNull()
                replacingSelf = incoming.packageName == activity.packageName
                wasInstalled = installed != null && !replacingSelf
                val match = ApkSignatures.compare(installed?.certificates.orEmpty(), incoming.certificates)
                val saveFile = UserFolders.locate(activity, incoming.packageName)
                val save = SaveDocuments.summary(saveFile)
                val preview = ApkReplacePreview.preview(
                    file.name,
                    incoming,
                    installed,
                    save,
                    UserFolders.LABEL,
                    activity.packageName,
                    signatureMatch = match,
                    preferClean = preferClean
                )
                showPreview(preview, saveFile)
            }
        }.start()
    }

    private fun showPreview(preview: ReplacePreview, saveFile: File) {
        if (!alive()) return
        val backups = runCatching { SaveStore.backups(saveFile) }.getOrDefault(emptyList())
        val extra = when {
            backups.isEmpty() -> ""
            preview.offerCleanReplace && preview.saveMissing ->
                "\n\nA backup of that save is still in the folder. Restore it from the save folder before you replace the app."
            preview.offerCleanReplace ->
                "\n\nA backup of that save is still in the folder."
            preview.saveMissing ->
                "\n\nA backup of that save is still in the folder. You can restore it before replacing the app."
            else -> ""
        }
        val positive = when {
            preview.uninstallFirst -> "Clean replace"
            preview.offerCleanReplace || preview.title.startsWith("Replace") -> "Update"
            else -> "Install"
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(preview.title)
            .setMessage(preview.body + extra)
            .setPositiveButton(positive) { _, _ -> startChosen(preview.uninstallFirst) }
            .setNegativeButton("Not now", null)
        if (preview.offerCleanReplace) {
            dialog.setNeutralButton("Clean replace") { _, _ -> startChosen(true) }
        } else if (backups.isNotEmpty()) {
            dialog.setNeutralButton("Restore save") { _, _ -> offerRestore(saveFile) }
        }
        dialog.showInSafeArea()
    }

    private fun startChosen(uninstall: Boolean) {
        cleanStarted = uninstall
        if (!uninstall) {
            beginInstall()
            return
        }
        val pkg = packageName
        if (pkg.isNullOrBlank()) {
            cleanStarted = false
            fail(ReplaceProgressText.unreadable())
            return
        }
        report(ReplaceProgressText.uninstallWait(pkg))
        runCatching { uninstallLauncher.launch(ApkInstaller.uninstallIntent(pkg)) }
            .onFailure {
                cleanStarted = false
                fail(ReplaceProgressText.sessionError(LogRedaction.redact(it.message ?: "Could not open uninstall.")))
            }
    }

    private fun offerRestore(saveFile: File) {
        val backups = SaveStore.backups(saveFile)
        if (backups.isEmpty() || !alive()) return
        val labels = backups.map { backup ->
            "Backup ${backup.index} · ${backup.summary.describe()}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Restore save")
            .setItems(labels) { _, which ->
                val index = backups[which].index
                val decision = runCatching { SaveStore.restore(saveFile, index, confirmed = false) }.getOrElse {
                    fail(it.message ?: "Could not restore that backup.")
                    return@setItems
                }
                if (decision.kind == OverwriteKind.NEEDS_CONFIRM) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Replace save?")
                        .setMessage("${decision.reason}\n\nCurrent items: ${decision.existingItems}\nBackup items: ${decision.incomingItems}")
                        .setPositiveButton("Restore") { _, _ ->
                            runCatching { SaveStore.restore(saveFile, index, confirmed = true) }
                                .onSuccess { report(it.reason) }
                                .onFailure { fail(it.message ?: "Could not restore that backup.") }
                        }
                        .setNegativeButton("Cancel", null)
                        .showInSafeArea()
                } else {
                    report(decision.reason)
                    Toast.makeText(activity, decision.reason, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .showInSafeArea()
    }

    private fun onUninstallReturned() {
        val pkg = packageName ?: return
        report("Checking whether $pkg was removed.")
        Thread {
            var gone = false
            for (attempt in 0 until 15) {
                val still = runCatching { ApkArchives.installed(activity.applicationContext, pkg) }.getOrNull()
                if (still == null) {
                    gone = true
                    break
                }
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    break
                }
            }
            activity.runOnUiThread {
                if (!alive()) return@runOnUiThread
                if (!gone) {
                    fail(ReplaceProgressText.uninstallCancelled())
                } else {
                    report(ReplaceProgressText.removedInstalling())
                    beginInstall()
                }
            }
        }.start()
    }

    private fun beginInstall() {
        val file = apkFile
        val pkg = packageName
        if (file == null || pkg == null) {
            fail(ReplaceProgressText.unreadable())
            return
        }
        if (!ApkInstaller.canInstall(activity)) {
            report(ReplaceProgressText.permission())
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            runCatching { settingsLauncher.launch(intent) }
                .onFailure { fail(ReplaceProgressText.permission()) }
            return
        }
        val started = runCatching { ApkInstaller.commit(activity.applicationContext, file, pkg) }
        if (started.isFailure) {
            val message = LogRedaction.redact(started.exceptionOrNull()?.message ?: "Could not start the install.")
            val offer = ApkReplacePreview.offerAfterFailure(wasInstalled, replacingSelf, cleanStarted)
            fail(ReplaceProgressText.sessionError(message), offerClean = offer)
            return
        }
        awaitingInstall = true
        report(InstallStatuses.plain(InstallOutcome.PENDING, null))
    }

    private fun onInstall(outcome: InstallOutcome, message: String) {
        if (!awaitingInstall) return
        when (outcome) {
            InstallOutcome.PENDING -> if (alive()) report(message)
            InstallOutcome.NEEDS_TAP -> Unit
            InstallOutcome.SUCCESS -> {
                awaitingInstall = false
                val name = packageName ?: "the app"
                val text = ReplaceProgressText.installed(name)
                report(text)
                val host = if (alive()) activity else activity.applicationContext
                Toast.makeText(host, text, Toast.LENGTH_LONG).show()
                if (deleteCache) runCatching { apkFile?.delete() }
            }
            else -> {
                awaitingInstall = false
                val offer = ApkReplacePreview.offerAfterFailure(wasInstalled, replacingSelf, cleanStarted)
                fail(ReplaceProgressText.installFailed(message), offerClean = offer)
            }
        }
    }

    private fun fail(text: String, offerClean: Boolean = false) {
        report(text)
        if (!alive()) {
            Toast.makeText(activity.applicationContext, text, Toast.LENGTH_LONG).show()
            return
        }
        val message = if (offerClean) {
            "$text\n\nThe save file was not deleted. Clean replace asks Android to uninstall the old app, then installs this APK. Opening the app again restores its data from that save."
        } else {
            text
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("App update")
            .setMessage(message)
        if (offerClean) {
            dialog.setPositiveButton("Clean replace") { _, _ -> startChosen(true) }
            dialog.setNegativeButton("Not now", null)
        } else {
            dialog.setPositiveButton("OK", null)
        }
        dialog.showInSafeArea()
    }

    private fun alive() = !activity.isFinishing && !activity.isDestroyed
}

class ApkInstallActivity : AppCompatActivity() {
    private val flow = ApkReplaceFlow(this) { text ->
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
    private var offered = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        ReceivePage(this).also { page = it }.install()
    }

    private lateinit var page: ReceivePage

    override fun onStart() {
        super.onStart()
        page.bind()
        page.render(ReceiveLive.snap())
        flow.attach()

        if (offered) return
        offered = true
        val path = intent.getStringExtra("path")
        if (path.isNullOrBlank()) return
        flow.offerFile(path)
    }

    override fun onStop() {
        page.unbind()
        flow.detach()
        super.onStop()
    }
}
