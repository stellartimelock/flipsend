package org.stellartimelock.send

import android.app.Activity
import android.content.pm.ServiceInfo
import android.os.Build
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import android.graphics.Rect
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.stellartimelock.send.core.ApkReplacePreview
import org.stellartimelock.send.core.ContentTypes
import org.stellartimelock.send.core.MEMO_NOT_IMPLEMENTED
import org.stellartimelock.send.core.ReplaceProgressText
import org.stellartimelock.send.core.MemoResult
import org.stellartimelock.send.core.MethodIds
import org.stellartimelock.send.core.PayloadKind
import org.stellartimelock.send.core.RelayPlans
import org.stellartimelock.send.core.RepairPrompt
import org.stellartimelock.send.core.TrustNewKey
import org.stellartimelock.send.core.RelayQuotaException
import org.stellartimelock.send.core.StellarMemoStub
import org.stellartimelock.send.core.memoForFile
import java.util.concurrent.atomic.AtomicBoolean

internal object Intents {
    fun gmail(context: Context, request: SendRequest): Intent =
        send(context, request, "com.google.android.gm", chooser = false)

    fun sms(context: Context, request: SendRequest): Intent {
        val smsPackage = Telephony.Sms.getDefaultSmsPackage(context)
        return if (request.kind == PayloadKind.TEXT) {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
                putExtra("sms_body", request.text.orEmpty())
                if (smsPackage != null) setPackage(smsPackage)
            }
        } else {
            send(context, request, smsPackage, chooser = false)
        }
    }

    fun textPipe(context: Context, request: SendRequest): Intent =
        send(context, request, "app.textpipe", chooser = false)

    fun shareSheet(context: Context, request: SendRequest): Intent =
        Intent.createChooser(send(context, request, null, chooser = false), "Share")

    private fun send(context: Context, request: SendRequest, packageName: String?, chooser: Boolean): Intent {
        val intent = Intent(Intent.ACTION_SEND)
        if (request.kind == PayloadKind.TEXT) {
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, request.text.orEmpty())
        } else {
            val uri = request.uri ?: throw IllegalArgumentException("Choose a file first.")
            intent.type = request.mimeType.ifBlank { "*/*" }
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(request.name, uri)
        }
        if (packageName != null) {
            intent.setPackage(packageName)
            if (request.uri != null) {
                runCatching { context.grantUriPermission(packageName, request.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
        return if (chooser) Intent.createChooser(intent, "Share") else intent
    }
}

internal object Notifier {
    private const val LISTENING = 41
    private const val CONSENT_BASE = 80
    private const val INSTALLS_CHANNEL = "installs"
    private const val INSTALL_CONFIRM_CHANNEL = "install_confirm"
    private const val INSTALL_CONFIRM_BASE = 200
    private const val REMOTE_CHANNEL = "remote_control"
    private const val REMOTE_NOTICE = 98

    fun listening(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, BackgroundListen.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("FlipSend")
            .setContentText(context.getString(R.string.listening))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openMainIntent(context))
            .build()
    }

    fun consent(context: Context, id: String, title: String, body: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, "consent")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openConsentIntent(context, id, title, body))
            .addAction(0, "Decline", pending(context, id, false))
            .addAction(0, "Accept", pending(context, id, true))
            .build()
        manager(context).notify(CONSENT_BASE + (id.hashCode() and 0xFF), notification)
    }

    fun clear(context: Context, id: String) {
        manager(context).cancel(CONSENT_BASE + (id.hashCode() and 0xFF))
    }

    fun beingControlled(context: Context) {
        ensureChannels(context)
        val stop = PendingIntent.getBroadcast(
            context,
            97,
            Intent(context, RemoteStopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, REMOTE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(org.stellartimelock.send.core.RemoteControl.BANNER)
            .setContentText(org.stellartimelock.send.core.RemoteControl.BANNER_DETAIL)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Stop", stop)
            .build()
        manager(context).notify(REMOTE_NOTICE, notification)
    }

    fun clearControlled(context: Context) {
        manager(context).cancel(REMOTE_NOTICE)
    }

    fun updatesInstalled(context: Context, text: String) {
        ensureChannels(context)
        val open = Intent(context, ReceivingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val pending = PendingIntent.getActivity(
            context,
            93,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, INSTALLS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(text)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager(context).notify(93, notification)
    }

    fun readyToInstall(context: Context, text: String, ids: List<String>, paths: List<String>) {
        ensureChannels(context)
        val open = Intent(context, InstallReadyReceiver::class.java)
            .putStringArrayListExtra(InstallReadyReceiver.EXTRA_IDS, ArrayList(ids))
            .putStringArrayListExtra(InstallReadyReceiver.EXTRA_PATHS, ArrayList(paths))
        val pending = PendingIntent.getBroadcast(
            context,
            94,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, INSTALLS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(text)
            .setContentText("Tap to install all")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager(context).notify(94, notification)
    }

    /**
     * Posts a high-priority heads-up notification when Android returns STATUS_PENDING_USER_ACTION
     * for a received APK. The notification is impossible to miss: it shows as a banner while the
     * screen is on, and as a full-screen alert when the screen is off. Tapping it opens
     * [ApkConfirmActivity] which replays the system install-confirm dialog.
     */
    fun installConfirmNeeded(context: Context, historyId: String, appName: String) {
        ensureChannels(context)
        val label = if (appName.isNotBlank()) "Tap to install $appName" else "Tap to install"
        val confirmIntent = Intent(context, ApkConfirmActivity::class.java)
            .putExtra("historyId", historyId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val requestCode = INSTALL_CONFIRM_BASE + (historyId.hashCode() and 0x7f)
        val pendingFlags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(context, requestCode, confirmIntent, pendingFlags)
        val notification = NotificationCompat.Builder(context, INSTALL_CONFIRM_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(label)
            .setContentText("Android is asking for your OK before installing")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .apply {
                setFullScreenIntent(pending, true)
            }
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        manager(context).notify(requestCode, notification)
    }

    fun clearInstallConfirm(context: Context, historyId: String) {
        val requestCode = INSTALL_CONFIRM_BASE + (historyId.hashCode() and 0x7f)
        manager(context).cancel(requestCode)
    }

    fun notice(context: Context, text: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, "transfers")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("FlipSend")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager(context).notify(92, notification)
    }


    private fun pending(context: Context, id: String, accept: Boolean): PendingIntent {
        val intent = Intent(context, ConsentReceiver::class.java)
            .putExtra(ConsentPrompt.EXTRA_ID, id)
            .putExtra(ConsentPrompt.EXTRA_ACCEPT, accept)
        val requestCode = (id.hashCode() and 0xFFFF) + if (accept) 1 else 0
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openConsentIntent(context: Context, id: String, title: String, body: String): PendingIntent {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(context.packageName)
            }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launchIntent.putExtra(ConsentPrompt.EXTRA_ID, id)
        launchIntent.putExtra(ConsentPrompt.EXTRA_TITLE, title)
        launchIntent.putExtra(ConsentPrompt.EXTRA_BODY, body)
        return PendingIntent.getActivity(
            context,
            (id.hashCode() and 0xFFFF) + 2,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    internal fun ensureChannels(context: Context) {
        val manager = manager(context)
        val listening = NotificationChannel(
            BackgroundListen.CHANNEL_ID,
            context.getString(R.string.channel_listening),
            NotificationManager.IMPORTANCE_MIN
        )
        listening.description = context.getString(R.string.channel_listening_description)
        listening.setSound(null, null as AudioAttributes?)
        listening.enableVibration(false)
        listening.enableLights(false)
        listening.setShowBadge(false)
        listening.lockscreenVisibility = Notification.VISIBILITY_SECRET
        manager.createNotificationChannel(listening)
        manager.createNotificationChannel(NotificationChannel("transfers", context.getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("consent", context.getString(R.string.channel_consent), NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(INSTALLS_CHANNEL, context.getString(R.string.channel_installs), NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(INSTALL_CONFIRM_CHANNEL, "Install confirmation", NotificationManager.IMPORTANCE_HIGH).also {
            it.description = "Shown when Android asks you to confirm an incoming app install"
            it.enableLights(true)
            it.enableVibration(true)
        })
        if (RemoteDesktopBuild.enabled(context)) {
            val remoteName = context.getString(R.string.channel_remote)
            if (remoteName.isNotBlank()) {
                manager.createNotificationChannel(NotificationChannel(REMOTE_CHANNEL, remoteName, NotificationManager.IMPORTANCE_HIGH))
            }
        }
    }

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun openMainIntent(context: Context): PendingIntent {
        val packageName = context.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)
    }
}

object ConsentPrompt {
    const val EXTRA_ID = "consent_id"
    const val EXTRA_ACCEPT = "consent_accept"
    const val EXTRA_TITLE = "consent_title"
    const val EXTRA_BODY = "consent_body"
}

class ConsentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ConsentPrompt.EXTRA_ID) ?: return
        ConsentBus.answer(id, intent.getBooleanExtra(ConsentPrompt.EXTRA_ACCEPT, false))
    }
}

class RemoteStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { StellarSendClient(context).stopRemote() }
        Notifier.clearControlled(context)
        SessionHub.emit(SessionEvent.RemoteChanged)
    }
}

class TransferService : Service() {
    private var screenReceiver: android.content.BroadcastReceiver? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notifier.listening(this)
        if (Build.VERSION.SDK_INT >= 29) {
            // Both flavors. dataSync times out after 6 hours and cannot start from boot.
            // The paired PC is a device connection. Sideload may still declare dataSync
            // in the manifest; this start uses connectedDevice only.
            startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(41, notification)
        }
        if (!BackgroundListen.enabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        TransferPower.attach(this)
        RelayPresenceWake.attach(this)
        registerScreenReceiver()
        runCatching { StellarSendClient(this).start() }
            .onFailure {
                unregisterScreenReceiver()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        return START_STICKY
    }

    /**
     * Android 15+ calls this when a dataSync foreground service hits its 6-hour cap.
     * The listen must stop within a few seconds, then come back as connectedDevice.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val restart = fgsType == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC &&
            BackgroundListen.enabled(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        if (restart) {
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, TransferService::class.java))
            }
        }
    }

    override fun onDestroy() {
        unregisterScreenReceiver()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!BackgroundListen.keepSocketAfterService(this)) {
            runCatching { StellarSendClient(this).stop() }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    org.stellartimelock.send.core.LanAnnounce.boost()
                    org.stellartimelock.send.core.RelayPresence.kick(org.stellartimelock.send.core.RelayPresence.SCREEN_ON)
                }
            }
        }
        runCatching {
            registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_SCREEN_ON))
        }.onSuccess { screenReceiver = receiver }
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }
}

fun Activity.showRepairPrompt(code: String?, message: String?, onRepair: (() -> Unit)? = null): Boolean {
    if (!RepairPrompt.unrecognized(code, message)) return false
    MaterialAlertDialogBuilder(this)
        .setTitle(RepairPrompt.TITLE)
        .setMessage(RepairPrompt.MESSAGE)
        .setPositiveButton(RepairPrompt.ACTION) { _, _ -> onRepair?.invoke() }
        .setNegativeButton("Not now", null)
        .showInSafeArea()
    return true
}

fun Activity.showTrustPrompt(client: StellarSendClient, code: String?, message: String?, onTrusted: ((Boolean) -> Unit)? = null): Boolean {
    val offer = client.pendingTrust() ?: return false
    val name = offer.name
    if (offer.fingerprint.isBlank()) return false
    MaterialAlertDialogBuilder(this)
        .setTitle(TrustNewKey.TITLE)
        .setMessage(TrustNewKey.message(name))
        .setPositiveButton(TrustNewKey.ACTION) { _, _ ->
            if (client.trustNewKey()) {
                Thread {
                    val ok = client.confirmTrustedPin()
                    runOnUiThread { onTrusted?.invoke(ok) }
                }.apply { isDaemon = true; start() }
            }
        }
        .setNegativeButton("Not now", null)
        .showInSafeArea()
    return true
}

object SendChooser {
    fun show(activity: Activity, client: StellarSendClient, request: SendRequest) {
        val methods = client.methods(request)
        if (methods.isEmpty()) {
            Toast.makeText(activity, "No send methods are available for this item.", Toast.LENGTH_LONG).show()
            return
        }
        if (methods.size == 1) {
            dispatch(activity, client, request, methods[0].id)
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Send")
            .setItems(methods.map { it.title }.toTypedArray()) { _, which ->
                dispatch(activity, client, request, methods[which].id)
            }
            .setNegativeButton("Cancel", null)
            .showInSafeArea()
    }

    /** Home Send text / clipboard: go straight to the paired PC, no chooser under heads-up banners. */
    fun sendToPc(activity: Activity, client: StellarSendClient, request: SendRequest) {
        val methods = client.methods(request)
        if (methods.any { it.id == MethodIds.WIFI }) {
            dispatch(activity, client, request, MethodIds.WIFI)
            return
        }
        show(activity, client, request)
    }

    private fun dispatch(activity: Activity, client: StellarSendClient, request: SendRequest, methodId: String) {
        when (methodId) {
            MethodIds.WIFI -> {
                val cancel = AtomicBoolean(false)
                Thread {
                    val outcome = runCatching { client.sendWifi(request) { cancel.get() } }
                    activity.runOnUiThread {
                        val result = outcome.getOrNull()
                        val error = outcome.exceptionOrNull()
                        if (result?.code == RelayPlans.QUOTA || error is RelayQuotaException) return@runOnUiThread
                        val text = result?.message ?: error?.message ?: "Sent"
                        val code = result?.code ?: error?.let { RepairPrompt.codeOf(it) }
                        if (activity.showTrustPrompt(client, code, text)) return@runOnUiThread
                        if (activity.showRepairPrompt(code, text) { client.rotate() }) return@runOnUiThread
                        Toast.makeText(activity, text.ifBlank { "Sent" }, Toast.LENGTH_LONG).show()
                    }
                }.start()
                val notice = if (ApkReplacePreview.isApk(request.name)) {
                    ReplaceProgressText.sentApk()
                } else {
                    "Sending to your PC."
                }
                Toast.makeText(activity, notice, Toast.LENGTH_SHORT).show()
            }
            MethodIds.USB -> {
                val stage = runCatching { client.stageUsb(request) }
                val message = stage.getOrNull()?.let { "${it.message}\n${it.adbPath}" } ?: stage.exceptionOrNull()?.message
                MaterialAlertDialogBuilder(activity)
                    .setTitle("USB")
                    .setMessage(message ?: "Could not stage the file.")
                    .setPositiveButton("OK", null)
                    .showInSafeArea()
            }
            MethodIds.MEMO -> {
                val result = if (request.kind == PayloadKind.FILE || request.uri != null && request.text == null) {
                    memoForFile()
                } else {
                    StellarMemoStub().sendText(request.text.orEmpty())
                }
                val message = when (result) {
                    MemoResult.NotImplemented -> MEMO_NOT_IMPLEMENTED
                    is MemoResult.Rejected -> result.message
                }
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Stellar memo (text only)")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .showInSafeArea()
            }
            else -> {
                val intent = runCatching { client.intentFor(methodId, request) }.getOrNull()
                if (intent == null) {
                    Toast.makeText(activity, "This send method is not available.", Toast.LENGTH_LONG).show()
                    return
                }
                try {
                    activity.startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    val name = when (methodId) {
                        MethodIds.GMAIL -> "Gmail is not installed."
                        MethodIds.SMS -> "No text message app is available."
                        MethodIds.TEXTPIPE -> "AI Text Manager is not installed."
                        else -> "Nothing on this phone can open that share."
                    }
                    Toast.makeText(activity, name, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

class SendToPcActivity : AppCompatActivity() {
    private var detailView: TextView? = null
    private val apkReplace = ApkReplaceFlow(this) { text -> detailView?.text = text }
    private val sessionListener: (SessionEvent) -> Unit = { event ->
        if (event is SessionEvent.Upgrade) runOnUiThread { RelayUpgrade.show(this, event.message) }
    }

    override fun onStart() {
        super.onStart()
        SessionHub.add(sessionListener)
        apkReplace.attach()
    }

    override fun onStop() {
        apkReplace.detach()
        SessionHub.remove(sessionListener)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // Share targets can open behind the caller; bring FlipSend forward so Send is tappable.
        runCatching {
            @Suppress("DEPRECATION")
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager)
                .moveTaskToFront(taskId, 0)
        }
        SafeDrawing.prepare(this)
        BackgroundListen.attach(this)
        val request = requestFrom(intent)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(getColor(R.color.space_bg))
        }
        val title = TextView(this).apply {
            text = "Send to PC"
            textSize = 22f
            setTextColor(getColor(R.color.space_text))
            contentDescription = "Send to PC"
        }
        val detail = TextView(this).apply {
            text = request?.name ?: "Nothing to send."
            textSize = 16f
            setTextColor(getColor(R.color.space_muted))
            setPadding(0, 24, 0, 24)
        }
        detailView = detail
        val send = com.google.android.material.button.MaterialButton(this).apply {
            text = "Send"
            textSize = 16f
            contentDescription = "Choose how to send"
            isEnabled = request != null
            FitButtons.prepare(this)
            setTextColor(getColor(R.color.space_on_accent))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.space_accent))
            setOnClickListener {
                val payload = request ?: return@setOnClickListener
                SendChooser.show(this@SendToPcActivity, StellarSendClient(this@SendToPcActivity), payload)
            }
        }
        root.addView(title)
        root.addView(detail)
        root.addView(send)
        val shared = request
        if (shared != null && shared.uri != null && ApkReplacePreview.isApk(shared.name)) {
            root.addView(com.google.android.material.button.MaterialButton(this).apply {
                text = "Replace app"
                textSize = 16f
                contentDescription = "Replace installed app with ${shared.name}"
                FitButtons.prepare(this)
                setTextColor(getColor(R.color.space_text))
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.space_quiet))
                setOnClickListener { apkReplace.offerUri(shared.uri, shared.name) }
            })
            root.addView(TextView(this).apply {
                text = "Replace checks StellarTimeLock backups/FlipSend for this app’s save. Matching signatures update the app in place and keep its data. Clean replace uninstalls through the system prompt, then installs the APK. The save file is not deleted, and opening the app again restores its data from that save."
                textSize = 16f
                setTextColor(getColor(R.color.space_muted))
                setPadding(0, 24, 0, 0)
            })
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.space_bg))
            isFillViewport = true
            isScrollContainer = true
            clipToPadding = false
            addView(root)
        }
        scroll.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            newFocus?.post {
                val rect = Rect(0, 0, newFocus.width, newFocus.height)
                newFocus.requestRectangleOnScreen(rect, true)
            }
        }
        setContentView(scroll)
        SafeDrawing.apply(this)
    }

    private fun requestFrom(intent: Intent?): SendRequest? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val stream = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val mime = intent.type ?: "application/octet-stream"
        return when {
            stream != null -> {
                val chosen = ChosenFile.fromUri(this, stream)
                SendRequest(PayloadKind.FILE, chosen.name, uri = stream, mimeType = ContentTypes.of(chosen.name, mime))
            }
            text != null -> SendRequest(PayloadKind.TEXT, "message.txt", text = text, mimeType = "text/plain")
            else -> null
        }
    }
}

internal fun qrBitmap(text: String, size: Int): Bitmap = QrBitmaps.encode(text, size)
