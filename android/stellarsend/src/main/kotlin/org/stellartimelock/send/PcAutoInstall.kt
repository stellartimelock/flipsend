package org.stellartimelock.send

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import org.stellartimelock.send.core.ApkAuto
import org.stellartimelock.send.core.ApkBatch
import org.stellartimelock.send.core.CompanionServices
import java.io.File

/**
 * After a batch from the paired PC has been saved, install incoming apps
 * with no FlipSend notification or FlipSend confirm. An update of an app
 * already on the phone replaces the old copy first. Protected data apps
 * are never uninstalled.
 */
internal object PcAutoInstall {
    private val seen = HashSet<String>()
    private val eligible = HashSet<String>()
    private val gate = Any()

    fun onArrived(context: Context, id: String, force: Boolean = false) {
        val app = context.applicationContext
        if (id.isNotBlank()) synchronized(gate) { eligible += id }
        val snap = ReceiveLive.snap()
        if (!force && !ApkAuto.batchSettled(snap)) return
        if (snap.files.isEmpty()) return
        val fresh = synchronized(gate) {
            val next = snap.files.filter { it.id in eligible && it.id !in seen }
            if (next.isEmpty()) return
            seen += next.map { it.id }
            next
        }
        val plan = ApkAuto.plan(fresh, Build.VERSION.SDK_INT, app.packageName)
        val log = ReceivedLog(app)
        for (file in plan.skipped) {
            log.mark(file.id, "failed", ApkAuto.SKIP_PROTECTED)
            ReceiveLive.edit { noteInstall(file.id, "failed", ApkAuto.SKIP_PROTECTED) }
            val name = ApkBatch.appName(file)
            SessionHub.emit(SessionEvent.Notice("$name: ${ApkAuto.SKIP_PROTECTED}"))
        }
        if (plan.skipped.isNotEmpty()) {
            SessionHub.emit(SessionEvent.Receive(ReceiveLive.snap()))
            SessionHub.emit(SessionEvent.ReceivedChanged)
        }
        val order = plan.enqueueOrder(app.packageName)
        if (order.isEmpty()) {
            val already = fresh.filter { it !in plan.skipped }.map { ApkBatch.appName(it) }.filter { it.isNotBlank() }
            val failed = plan.skipped.map { ApkBatch.appName(it) to ApkAuto.SKIP_PROTECTED }
            if (already.isNotEmpty() || failed.isNotEmpty()) {
                publish(app, already, emptyList(), failed)
            }
            return
        }
        ApkInstallQueue.collectAuto(app, plan.silent.map { it.id }, order.map { it.id })
    }

    /**
     * Called when the install queue goes idle.
     *
     * [updated] - names of apps successfully installed.
     * [ready] - apps whose install session returned STATUS_PENDING_USER_ACTION
     *           (notification already posted; reported to PC as "Install Pending").
     * [failed] - apps that failed with a (name, reason) pair.
     *
     * Reports each result back to the paired PC as a separate log line.
     * "Transfer Succeeded" on the PC is unchanged; these lines are additions.
     */
    fun publish(
        context: Context,
        updated: List<String>,
        ready: List<ReadyNote>,
        failed: List<Pair<String, String>> = emptyList()
    ) {
        if (updated.isEmpty() && ready.isEmpty() && failed.isEmpty()) return

        Thread {
            val results = JSONArray()
            for (name in updated) {
                results.put(JSONObject().put("name", name).put("status", "installed"))
            }
            for (note in ready) {
                val name = ReceiveLive.snap().files.firstOrNull { it.id == note.id }
                    ?.let { ApkBatch.appName(it) }.orEmpty()
                results.put(JSONObject().put("name", name.ifBlank { note.id }).put("status", "pending"))
            }
            for ((name, reason) in failed) {
                results.put(JSONObject().put("name", name).put("status", "failed").put("reason", reason))
            }
            if (results.length() == 0) return@Thread
            val body = JSONObject().put("results", results)
            var last: Throwable? = null
            repeat(3) { attempt ->
                val sent = runCatching { StellarSendClient(context).callInstallResult(body) }
                if (sent.isSuccess) return@Thread
                last = sent.exceptionOrNull()
                if (attempt < 2) {
                    try {
                        Thread.sleep(400L * (attempt + 1))
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
            android.util.Log.w("FlipSend", "install.result did not reach the PC: ${last?.message}")
        }.start()
    }
}

/** Tap target for "N apps ready to install". Starts the same in-place Install all queue. */
class InstallReadyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ids = intent.getStringArrayListExtra(EXTRA_IDS).orEmpty().filter { it.isNotBlank() }
        val paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        ids.forEachIndexed { index, id ->
            if (ReceiveLive.snap().files.any { it.id == id }) return@forEachIndexed
            val path = paths.getOrNull(index) ?: return@forEachIndexed
            val file = File(path)
            if (!file.isFile) return@forEachIndexed
            ReceiveLive.edit {
                noteSender("your computer")
                noteFile(id, file.name, "apk", file.absolutePath)
            }
            ReceiveApk.remember(context, id, file)
        }
        if (ids.isNotEmpty()) ApkInstallQueue.start(context, ids)
    }

    companion object {
        const val EXTRA_IDS = "ids"
        const val EXTRA_PATHS = "paths"
    }
}
