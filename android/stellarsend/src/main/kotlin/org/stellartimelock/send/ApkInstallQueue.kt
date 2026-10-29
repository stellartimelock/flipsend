package org.stellartimelock.send

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.stellartimelock.send.core.ApkAuto
import org.stellartimelock.send.core.ApkBatch
import org.stellartimelock.send.core.ApkSignatures
import org.stellartimelock.send.core.InstallOutcome
import org.stellartimelock.send.core.ReceiveFile
import org.stellartimelock.send.core.SelfUpdate
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class ReadyNote(val id: String, val path: String)

/**
 * Installs received APKs one PackageInstaller session at a time.
 * Incoming updates of apps already on the phone replace the old copy first,
 * except protected apps, which are never uninstalled.
 */
internal object ApkInstallQueue {
    private val gate = Any()
    private val waiting = ArrayDeque<String>()
    private var current: String? = null
    private var app: Context? = null
    private var hooked = false
    @Volatile
    private var attempted = false
    private val autoIds = HashSet<String>()
    private val held = HashMap<String, ReceiveFile>()
    private val autoNames = HashMap<String, String>()
    private val autoPaths = HashMap<String, String>()
    private val updatedNames = ArrayList<String>()
    private val readyApps = ArrayList<ReadyNote>()
    private val failedInstalls = ArrayList<Pair<String, String>>()
    private val forceUnspecified = HashSet<String>()
    private val retriedBlocked = HashSet<String>()
    private var reportWhenIdle = false
    private val main = Handler(Looper.getMainLooper())
    private val listener: (InstallOutcome, String, String?) -> Unit = { outcome, _, historyId ->
        if (outcome == InstallOutcome.PENDING) {
            // Android is showing (or trying to show) the install confirm dialog.
            // Advance the queue so other queued apps can install; the notification keeps
            // this app visible. Treat it as NEEDS_TAP so deliver() reports it to the PC.
            val isAuto = historyId != null && synchronized(gate) { historyId in autoIds }
            if (isAuto) finishCurrent(historyId, InstallOutcome.NEEDS_TAP)
        } else {
            finishCurrent(historyId, outcome)
        }
    }

    val running: Boolean
        get() = synchronized(gate) { current != null || waiting.isNotEmpty() }

    fun start(context: Context, ids: List<String>) {
        enqueue(context, ids)
        advance()
    }

    /**
     * Incoming PC apps. Every id in [orderedIds] starts a PackageInstaller session.
     * Qualifying updates request no extra tap. If Android still asks, its confirm
     * sheet is shown. FlipSend is last. No FlipSend notification.
     */
    fun collectAuto(context: Context, silentIds: List<String>, orderedIds: List<String>) {
        synchronized(gate) {
            reportWhenIdle = true
            autoIds.addAll(silentIds)
            autoIds.addAll(orderedIds)
        }
        if (orderedIds.isEmpty() || !ApkAuto.startIncomingSession()) {
            if (!running) deliver(context.applicationContext)
            return
        }
        start(context, orderedIds)
    }

    private fun enqueue(context: Context, ids: List<String>) {
        val application = context.applicationContext
        synchronized(gate) {
            app = application
            if (!hooked) {
                ApkInstallBus.addDetailed(listener)
                hooked = true
            }
            val known = waiting.toHashSet().apply { current?.let { add(it) } }
            val snap = ReceiveLive.snap().files
            for (id in ids) {
                if (id.isNotBlank() && id !in known) {
                    waiting.addLast(id)
                    known += id
                    snap.firstOrNull { it.id == id }?.let { held[id] = it }
                }
            }
        }
    }

    private fun advance() {
        val context = synchronized(gate) { app } ?: return
        val nextId = synchronized(gate) {
            if (current != null) return
            var chosen: String? = null
            while (waiting.isNotEmpty()) {
                val id = waiting.removeFirst()
                val file = fileFor(id)
                if (file != null && ApkBatch.needsSession(file)) {
                    current = id
                    chosen = id
                    break
                }
            }
            chosen
        }
        if (nextId == null) {
            onIdle(context)
            return
        }
        val file = fileFor(nextId) ?: run {
            synchronized(gate) { if (current == nextId) current = null }
            advance()
            return
        }
        Thread {
            if (!ApkInstaller.canInstall(context)) {
                val leftover = synchronized(gate) {
                    val ids = ArrayList<String>()
                    current?.let { ids += it }
                    ids += waiting
                    current = null
                    waiting.clear()
                    for (id in ids) {
                        if (id !in autoIds) continue
                        val path = fileFor(id)?.path.orEmpty()
                        readyApps += ReadyNote(id, path)
                    }
                    ids
                }
                for (id in leftover) {
                    ApkInstallMarks.needsTap(context, id, ApkBatch.REASON_UNKNOWN_APPS)
                }
                main.post {
                    Toast.makeText(context, "Allow FlipSend to install unknown apps, then try Install all again.", Toast.LENGTH_LONG).show()
                    openUnknownApps(context)
                    publish()
                    deliver(context)
                }
                return@Thread
            }
            attempted = true
            val shown = ApkBatch.appName(file)
            val replace = synchronized(gate) { file.id in autoIds } &&
                ApkAuto.shouldReplace(file.packageName, file.hasInstalled, context.packageName, file.signatureMatch)
            if (replace) {
                ApkInstallMarks.replacing(context, file.id)
                InstallProgress.publish(shown, 0, file.path.let { java.io.File(it).length().coerceAtLeast(1) })
                publish()
                replaceOld(context, file)
            }
            ApkInstallMarks.installing(context, file.id)
            InstallProgress.publish(shown, 0, file.path.let { java.io.File(it).length().coerceAtLeast(1) })
            publish()
            val started = runCatching { commitOne(context, file) }
            if (started.isFailure) finishCurrent(nextId, InstallOutcome.FAILED)
        }.start()
    }

    private fun replaceOld(context: Context, file: ReceiveFile) {
        if (ApkAuto.neverUninstall(file.packageName, context.packageName)) return
        val latch = CountDownLatch(1)
        ApkInstaller.uninstall(context, file.packageName) { latch.countDown() }
        runCatching { latch.await(3, TimeUnit.MINUTES) }
    }

    private fun commitOne(context: Context, file: ReceiveFile): Int {
        val disk = File(file.path)
        val bySelf = runCatching { ApkArchives.installedBySelf(context, file.packageName) }.getOrDefault(file.installedBySelf)
        val self = file.packageName == context.packageName
        val silent = ApkAuto.canUpdateSilently(
            Build.VERSION.SDK_INT,
            file.hasInstalled,
            bySelf,
            file.signatureMatch,
            file.targetSdk,
            updatingSelf = self && file.hasInstalled
        )
        val auto = synchronized(gate) { file.id in autoIds }
        val forced = synchronized(gate) { file.id in forceUnspecified }
        if (auto) {
            synchronized(gate) {
                autoNames[file.id] = ApkBatch.appName(file)
                autoPaths[file.id] = file.path
            }
        }
        if (self) SelfUpdateStore.remember(context, file)
        // Other apps: silent only when Android will honour USER_ACTION_NOT_REQUIRED.
        // FlipSend itself: silent only when this process is in the foreground. A
        // background / relay receive uses USER_ACTION_UNSPECIFIED so Android 14+
        // shows its confirm instead of STATUS_FAILURE_BLOCKED (the 1.5.20 bug,
        // applied to self-update).
        val requestSilent = if (forced) {
            false
        } else if (self) {
            SelfUpdate.requestSilent(silent, ReceivingActivity.resumed)
        } else {
            silent
        }
        val defer = auto && ApkAuto.abandonsIfAndroidAsks()
        val shown = ApkBatch.appName(file)
        return ApkInstaller.commit(
            context,
            disk,
            file.packageName,
            historyId = file.id,
            silentUpdate = requestSilent,
            deferUserAction = defer,
            requestUpdateOwnership = auto,
            dontKillApp = self && Build.VERSION.SDK_INT >= 34,
            appName = shown,
            onProgress = { done, total -> InstallProgress.publish(shown, done, total) }
        )
    }

    private fun fileFor(id: String): ReceiveFile? {
        val live = ReceiveLive.snap().files.firstOrNull { it.id == id }
        if (live != null) {
            synchronized(gate) { held[id] = live }
            return live
        }
        return synchronized(gate) { held[id] }
    }

    private fun finishCurrent(historyId: String?, outcome: InstallOutcome) {
        val context = synchronized(gate) { app }
        val retryBlocked = outcome == InstallOutcome.BLOCKED && context != null &&
            synchronized(gate) {
                val active = current
                val file = active?.let { fileFor(it) }
                val self = file != null && file.packageName == context.packageName
                self && active !in retriedBlocked
            }
        if (retryBlocked) {
            val id = synchronized(gate) {
                val active = current ?: return
                retriedBlocked += active
                forceUnspecified += active
                waiting.addFirst(active)
                current = null
                active
            }
            ApkInstallMarks.finished(context, id, "failed", ApkBatch.REASON_BLOCKED)
            InstallProgress.clear()
            publish()
            advance()
            return
        }
        val id = synchronized(gate) {
            val active = current ?: return
            if (historyId != null && historyId != active) return
            current = null
            active
        }
        val file = ReceiveLive.snap().files.firstOrNull { it.id == id }
        val self = file?.packageName == context?.packageName
        if (self && outcome == InstallOutcome.SUCCESS && context != null) {
            SelfUpdateStore.clear(context)
        }
        if (self && outcome != InstallOutcome.SUCCESS && outcome != InstallOutcome.PENDING &&
            outcome != InstallOutcome.NEEDS_TAP && context != null
        ) {
            val pending = SelfUpdateStore.load(context)
            if (pending != null && !SelfUpdate.shouldRetry(pending, file?.installedCode ?: -1, java.io.File(file?.path.orEmpty()).isFile)) {
                SelfUpdateStore.clear(context)
            }
        }
        val auto = synchronized(gate) { id in autoIds }
        if (auto && outcome == InstallOutcome.SUCCESS) {
            val name = file?.let { ApkBatch.appName(it) }.orEmpty().ifBlank {
                synchronized(gate) { autoNames[id].orEmpty() }
            }
            if (name.isNotBlank()) synchronized(gate) { updatedNames += name }
        }
        if (auto && outcome == InstallOutcome.NEEDS_TAP) {
            val path = file?.path?.takeIf { it.isNotBlank() } ?: synchronized(gate) { autoPaths[id].orEmpty() }
            synchronized(gate) { readyApps += ReadyNote(id, path) }
        }
        val detail = ApkBatch.detailFor(outcome)
        val appName = file?.let { ApkBatch.appName(it) }.orEmpty().ifBlank {
            synchronized(gate) { autoNames[id].orEmpty() }
        }
        when (outcome) {
            InstallOutcome.SUCCESS -> ApkInstallMarks.finished(contextOrNull(), id, "success", "")
            InstallOutcome.NEEDS_TAP -> ApkInstallMarks.finished(contextOrNull(), id, "needs-tap", detail)
            else -> {
                ApkInstallMarks.finished(contextOrNull(), id, "failed", detail)
                if (auto && appName.isNotBlank()) synchronized(gate) { failedInstalls += appName to detail }
            }
        }
        if (outcome != InstallOutcome.PENDING) InstallProgress.clear()
        publish()
        advance()
    }

    private fun contextOrNull(): Context? = synchronized(gate) { app }

    private fun onIdle(context: Context) {
        if (synchronized(gate) { reportWhenIdle }) {
            deliver(context)
            return
        }
        if (!attempted) return
        attempted = false
        val summary = ApkBatch.summary(ReceiveLive.snap().files)
        if (summary.isEmpty()) return
        main.post {
            Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
            publish()
        }
    }

    private fun deliver(context: Context) {
        val payload = synchronized(gate) {
            if (!reportWhenIdle) return
            val updated = updatedNames.toList()
            val ready = readyApps.distinctBy { it.id }
            val failed = failedInstalls.toList()
            updatedNames.clear()
            readyApps.clear()
            failedInstalls.clear()
            autoIds.clear()
            held.clear()
            autoNames.clear()
            autoPaths.clear()
            forceUnspecified.clear()
            retriedBlocked.clear()
            reportWhenIdle = false
            attempted = false
            Triple(updated, ready, failed)
        }
        main.post { PcAutoInstall.publish(context, payload.first, payload.second, payload.third) }
    }

    private fun publish() {
        SessionHub.emit(SessionEvent.Receive(ReceiveLive.snap()))
        SessionHub.emit(SessionEvent.ReceivedChanged)
    }

    private fun openUnknownApps(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

/** Writes the same install mark to the live batch and the received-file log. */
internal object ApkInstallMarks {
    fun installing(context: Context, id: String) {
        if (ReceiveLive.snap().files.any { it.id == id }) {
            ReceiveLive.edit { noteInstall(id, "installing") }
        }
        ReceivedLog(context).mark(id, "installing", "")
    }

    fun replacing(context: Context, id: String) {
        if (ReceiveLive.snap().files.any { it.id == id }) {
            ReceiveLive.edit { noteInstall(id, "replacing") }
        }
        ReceivedLog(context).mark(id, "replacing", "")
    }

    fun needsTap(context: Context, id: String, detail: String) {
        finished(context, id, "needs-tap", detail)
    }

    fun finished(context: Context?, id: String, mark: String, detail: String) {
        if (ReceiveLive.snap().files.any { it.id == id }) {
            ReceiveLive.edit { noteInstall(id, mark, detail) }
        }
        if (context != null) ReceivedLog(context).mark(id, mark, detail)
    }

    /**
     * The install is done only when PackageManager reports this version or a newer one.
     * A session that never calls back must not leave the row on Installing.
     */
    fun settledByPackage(context: Context, id: String): Boolean {
        val log = ReceivedLog(context)
        val entry = log.find(id) ?: return false
        if (entry.installStatus != "installing" && entry.installStatus != "pending" && entry.installStatus != "replacing") {
            return false
        }
        if (entry.signatureMatch == ApkSignatures.DIFFER) return false
        if (entry.packageName.isBlank() || entry.incomingCode < 0) return false
        val installed = runCatching { ApkArchives.installed(context, entry.packageName) }.getOrNull() ?: return false
        if (installed.versionCode < entry.incomingCode) return false
        log.noteApp(
            id,
            appName = entry.appName.ifBlank { installed.label.ifBlank { entry.packageName } },
            packageName = entry.packageName,
            incomingVersion = entry.incomingVersion,
            incomingCode = entry.incomingCode,
            installedVersion = installed.versionName.orEmpty(),
            installedCode = installed.versionCode,
            hasInstalled = true,
            signatureMatch = entry.signatureMatch
        )
        finished(context, id, "success", "")
        val still = log.list().any {
            it.installStatus == "installing" || it.installStatus == "pending" || it.installStatus == "replacing"
        }
        if (!still) InstallProgress.clear()
        ApkInstallBus.emit(InstallOutcome.SUCCESS, "", id)
        SessionHub.emit(SessionEvent.ReceivedChanged)
        return true
    }
}

/** Reads an APK that just arrived and stores its name and versions on the receive screen. */
internal object ReceiveApk {
    fun remember(context: Context, id: String, file: File): Boolean {
        val incoming = runCatching { ApkArchives.read(context, file) }.getOrNull()
        val snap = if (incoming == null) {
            ApkInstallMarks.finished(context, id, "failed", ApkBatch.REASON_INVALID)
            ReceiveLive.snap()
        } else {
            val installed = runCatching { ApkArchives.installed(context, incoming.packageName) }.getOrNull()
            val match = ApkSignatures.compare(installed?.certificates.orEmpty(), incoming.certificates)
            val bySelf = installed != null && runCatching { ApkArchives.installedBySelf(context, incoming.packageName) }.getOrDefault(false)
            val appName = incoming.label.ifBlank { incoming.packageName }
            val log = ReceivedLog(context)
            log.noteApp(
                id,
                appName = appName,
                packageName = incoming.packageName,
                incomingVersion = incoming.versionName.orEmpty(),
                incomingCode = incoming.versionCode,
                installedVersion = installed?.versionName.orEmpty(),
                installedCode = installed?.versionCode ?: -1,
                hasInstalled = installed != null,
                signatureMatch = match
            )
            // Keep only the newest APK per package; delete files for older copies.
            val pruned = log.pruneOlderApks(incoming.packageName)
            for (old in pruned) runCatching { log.file(old).delete() }
            ReceiveLive.edit {
                notePackage(
                    id,
                    appName = appName,
                    packageName = incoming.packageName,
                    incomingVersion = incoming.versionName.orEmpty(),
                    incomingCode = incoming.versionCode,
                    installedVersion = installed?.versionName.orEmpty(),
                    installedCode = installed?.versionCode ?: -1,
                    hasInstalled = installed != null,
                    signatureMatch = match,
                    targetSdk = incoming.targetSdk,
                    installedBySelf = bySelf
                )
            }
        }
        SessionHub.emit(SessionEvent.Receive(snap))
        SessionHub.emit(SessionEvent.ReceivedChanged)
        return incoming != null
    }
}
