package org.stellartimelock.send

import android.content.Context
import org.stellartimelock.send.core.ApkBatch
import org.stellartimelock.send.core.DurableFile
import org.stellartimelock.send.core.SelfUpdate
import java.io.File

/**
 * Remembers a FlipSend self-update on disk so process death (the package
 * being replaced) cannot lose the APK path or the received-list row.
 * After [android.content.Intent.ACTION_MY_PACKAGE_REPLACED] the listener
 * starts again and [resume] either marks Installed or starts the session
 * once more.
 */
internal object SelfUpdateStore {
    private val gate = Any()
    @Volatile
    private var resuming = false

    fun save(context: Context, pending: SelfUpdate.Pending) {
        val file = file(context)
        DurableFile.write(file, SelfUpdate.encode(pending), allowShrink = true)
    }

    fun load(context: Context): SelfUpdate.Pending? {
        val file = file(context)
        if (!file.isFile) return null
        return runCatching { SelfUpdate.decode(file.readBytes()) }.getOrNull()
    }

    fun clear(context: Context) {
        DurableFile.deleteAll(file(context))
    }

    fun remember(context: Context, file: org.stellartimelock.send.core.ReceiveFile) {
        if (file.packageName != context.packageName) return
        val previous = load(context)
        val attempts = (previous?.attempts ?: 0) + 1
        save(
            context,
            SelfUpdate.Pending(
                historyId = file.id,
                path = file.path,
                versionCode = file.incomingCode,
                versionName = file.incomingVersion,
                appName = ApkBatch.appName(file),
                attempts = attempts
            )
        )
    }

    /**
     * Called when FlipSend starts listening (boot, package replaced, or
     * the app is opened). If the new version is already on the phone, the
     * row is marked Installed and the PC is told. If the APK is still
     * newer, the same install queue runs again — never an uninstall.
     */
    fun resume(context: Context) {
        val app = context.applicationContext
        synchronized(gate) {
            if (resuming) return
            if (load(app) == null) return
            resuming = true
        }
        Thread({
            try {
                settleOrRetry(app)
            } finally {
                synchronized(gate) { resuming = false }
            }
        }, "stellarsend-self-update").also { it.isDaemon = true; it.start() }
    }

    private fun settleOrRetry(context: Context) {
        val pending = load(context) ?: return
        repeat(6) { attempt ->
            val installed = runCatching { ApkArchives.installed(context, context.packageName) }.getOrNull()
            val installedCode = installed?.versionCode ?: -1
            if (SelfUpdate.settled(installedCode, pending)) {
                settle(context, pending, installedCode)
                return
            }
            if (attempt < 5) Thread.sleep(400)
        }
        val installedCode = runCatching { ApkArchives.installed(context, context.packageName) }.getOrNull()?.versionCode ?: -1
        val apk = File(pending.path)
        val entry = ReceivedLog(context).find(pending.historyId)
        val status = entry?.installStatus.orEmpty()
        if (status == "needs-tap") return
        if (sessionStillOpen(context, entry?.sessionId ?: -1)) return
        if (SelfUpdate.shouldRetry(pending, installedCode, apk.isFile) &&
            InstallPrefs.auto(context) &&
            !ApkInstallQueue.running &&
            status != "success"
        ) {
            requeue(context, pending, apk)
        } else if (!SelfUpdate.shouldRetry(pending, installedCode, apk.isFile)) {
            clear(context)
        }
    }

    private fun settle(context: Context, pending: SelfUpdate.Pending, installedCode: Long) {
        if (pending.historyId.isNotBlank()) {
            val marked = ApkInstallMarks.settledByPackage(context, pending.historyId)
            if (!marked) {
                ApkInstallMarks.finished(context, pending.historyId, "success", "")
            }
            val log = ReceivedLog(context)
            log.find(pending.historyId)?.let { entry ->
                log.noteApp(
                    entry.id,
                    appName = entry.appName.ifBlank { pending.appName },
                    packageName = context.packageName,
                    incomingVersion = pending.versionName.ifBlank { entry.incomingVersion },
                    incomingCode = pending.versionCode,
                    installedVersion = entry.installedVersion,
                    installedCode = installedCode,
                    hasInstalled = true,
                    signatureMatch = entry.signatureMatch
                )
            }
        }
        PcAutoInstall.publish(context, listOf(pending.appName.ifBlank { "FlipSend" }), emptyList(), emptyList())
        runCatching { File(pending.path).takeIf { it.isFile }?.delete() }
        clear(context)
        SessionHub.emit(SessionEvent.ReceivedChanged)
    }

    private fun requeue(context: Context, pending: SelfUpdate.Pending, apk: File) {
        if (ReceiveLive.snap().files.none { it.id == pending.historyId }) {
            ReceiveLive.edit {
                noteSender("your computer")
                noteFile(pending.historyId, apk.name, "apk", apk.absolutePath)
            }
            ReceiveApk.remember(context, pending.historyId, apk)
        }
        ApkInstallQueue.collectAuto(context, emptyList(), listOf(pending.historyId))
    }

    private fun sessionStillOpen(context: Context, sessionId: Int): Boolean {
        if (sessionId < 0) return false
        return runCatching {
            context.packageManager.packageInstaller.mySessions.any { it.sessionId == sessionId }
        }.getOrDefault(false)
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, SelfUpdate.FILE_NAME)
}
