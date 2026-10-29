package org.stellartimelock.send

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.stellartimelock.send.core.CopyToPc
import org.stellartimelock.send.core.FortressBotHandoff
import org.stellartimelock.send.core.PayloadKind
import org.stellartimelock.send.core.TransferState
import org.stellartimelock.send.core.sha256Hex
import java.io.File

object CopyToPcWork {
    fun requestFrom(intent: Intent?): CopyToPc.Request {
        val extras = intent?.extras
        val uri = streamUri(intent)
        val kind = stringExtra(extras, CopyToPc.EXTRA_KIND)
            ?: if (intent?.action == FortressBotHandoff.ACTION_SEND_FILE_TO_PC) FortressBotHandoff.KIND_FILE else null
        val request = CopyToPc.request(
            username = stringExtra(extras, CopyToPc.EXTRA_USERNAME),
            password = stringExtra(extras, CopyToPc.EXTRA_PASSWORD),
            title = stringExtra(extras, CopyToPc.EXTRA_TITLE),
            kind = kind,
            source = stringExtra(extras, CopyToPc.EXTRA_SOURCE),
            clearAfterSeconds = intExtra(extras, CopyToPc.EXTRA_CLEAR_AFTER_SECONDS),
            clearAfterSecondsText = stringExtra(extras, CopyToPc.EXTRA_CLEAR_AFTER_SECONDS),
            holder = stringExtra(extras, CopyToPc.EXTRA_HOLDER),
            number = stringExtra(extras, CopyToPc.EXTRA_NUMBER),
            expMonth = stringExtra(extras, CopyToPc.EXTRA_EXP_MONTH),
            expYear = stringExtra(extras, CopyToPc.EXTRA_EXP_YEAR),
            cvv = stringExtra(extras, CopyToPc.EXTRA_CVV),
            uri = uri?.toString(),
            filename = stringExtra(extras, CopyToPc.EXTRA_NAME)
                ?: stringExtra(extras, CopyToPc.EXTRA_FILENAME)
                ?: uri?.lastPathSegment,
            content = stringExtra(extras, CopyToPc.EXTRA_CONTENT)
        )
        wipeSecrets(intent)
        return request
    }

    fun deliver(context: Context, request: CopyToPc.Request, caller: String?): CopyToPc.Outcome {
        if (request.fileKind) {
            return deliverFile(context, request, caller)
        }
        val sameSigner = caller != null && FortressSignatures.sameAsSelf(context, caller)
        if (!CopyToPc.allow(caller, request.source, sameSigner)) {
            return CopyToPc.Outcome(false, CopyToPc.NOT_FORTRESS)
        }
        val text = request.clipboardText ?: return CopyToPc.Outcome(false, CopyToPc.NOTHING)
        val result = runCatching {
            val client = StellarSendClient(context.applicationContext)
            if (!client.snapshot().paired) {
                return CopyToPc.Outcome(false, CopyToPc.PC_NOT_CONNECTED)
            }
            client.pushPasswordToPc(text, request.clearAfterSeconds)
        }.getOrElse {
            return CopyToPc.Outcome(false, CopyToPc.PC_NOT_CONNECTED)
        }
        return CopyToPc.outcomeFrom(result.ok, result.code, result.message, request.clearAfterSeconds)
    }

    fun deliverFile(context: Context, request: CopyToPc.Request, caller: String?): CopyToPc.Outcome {
        val signed = caller != null && FortressSignatures.isKnownFortress(context, caller)
        if (!FortressBotHandoff.allowFileCaller(caller, signed)) {
            return CopyToPc.Outcome(false, CopyToPc.NOT_FORTRESS)
        }
        val destDir = File(context.cacheDir, "outgoing").apply { mkdirs() }
        val dest = File(destDir, FortressBotHandoff.outgoingName(request.filename))
        return try {
            val client = StellarSendClient(context.applicationContext)
            if (!client.snapshot().paired) {
                return CopyToPc.Outcome(false, CopyToPc.PC_NOT_CONNECTED)
            }
            val inline = request.content?.takeIf { it.isNotEmpty() }
            if (inline != null) {
                FortressBotHandoff.stageOutgoing(destDir, request.filename, inline)
            } else {
                val uriText = request.uri ?: return CopyToPc.Outcome(false, FortressBotHandoff.NOTHING)
                val uri = runCatching { Uri.parse(uriText) }.getOrNull()
                    ?: return CopyToPc.Outcome(false, FortressBotHandoff.NOTHING)
                copyOpaque(context, uri, dest)
            }
            val sendUri = Uri.fromFile(dest)
            val result = client.sendWifi(
                SendRequest(
                    PayloadKind.FILE,
                    dest.name,
                    uri = sendUri,
                    mimeType = FortressBotHandoff.MIME_JSON
                )
            )
            CopyToPc.fileOutcomeFrom(
                result.state == TransferState.SUCCEEDED,
                result.code.orEmpty(),
                result.message.orEmpty()
            )
        } catch (_: Exception) {
            CopyToPc.Outcome(false, CopyToPc.PC_NOT_CONNECTED)
        } finally {
            dest.delete()
            File(context.cacheDir, "outgoing").listFiles()?.forEach { file ->
                if (FortressBotHandoff.isIncomingName(file.name) || FortressBotHandoff.isResponseName(file.name)) {
                    file.delete()
                }
            }
        }
    }

    fun activityCaller(activity: AppCompatActivity): String? {
        activity.callingPackage?.let { return it }
        if (Build.VERSION.SDK_INT >= 31) {
            activity.launchedFromPackage?.let { return it }
        }
        activity.referrer?.takeIf { it.scheme == "android-app" }?.host?.let { return it }
        return binderCaller(activity)
    }

    fun serviceCaller(context: Context): String? = binderCaller(context)

    private fun binderCaller(context: Context): String? {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return null
        return context.packageManager.getPackagesForUid(uid)?.firstOrNull()
    }

    private fun stringExtra(extras: Bundle?, name: String): String? =
        extras?.getString(name)

    private fun intExtra(extras: Bundle?, name: String): Int? {
        if (extras == null || !extras.containsKey(name)) return null
        val raw = extras.get(name)
        return when (raw) {
            is Int -> raw
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }

    private fun wipeSecrets(intent: Intent?) {
        if (intent == null) return
        intent.removeExtra(CopyToPc.EXTRA_USERNAME)
        intent.removeExtra(CopyToPc.EXTRA_PASSWORD)
        intent.removeExtra(CopyToPc.EXTRA_TITLE)
        intent.removeExtra(CopyToPc.EXTRA_HOLDER)
        intent.removeExtra(CopyToPc.EXTRA_NUMBER)
        intent.removeExtra(CopyToPc.EXTRA_EXP_MONTH)
        intent.removeExtra(CopyToPc.EXTRA_EXP_YEAR)
        intent.removeExtra(CopyToPc.EXTRA_CVV)
        intent.removeExtra(CopyToPc.EXTRA_CLEAR_AFTER_SECONDS)
        intent.removeExtra(CopyToPc.EXTRA_SOURCE)
        intent.removeExtra(CopyToPc.EXTRA_KIND)
        intent.removeExtra(CopyToPc.EXTRA_URI)
        intent.removeExtra(CopyToPc.EXTRA_FILENAME)
        intent.removeExtra(CopyToPc.EXTRA_STREAM)
        intent.removeExtra(CopyToPc.EXTRA_NAME)
        intent.removeExtra(CopyToPc.EXTRA_CONTENT)
        intent.data = null
    }

    private fun streamUri(intent: Intent?): Uri? {
        if (intent == null) return null
        val extras = intent.extras
        val stream = if (Build.VERSION.SDK_INT >= 33) {
            extras?.getParcelable(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras?.getParcelable(Intent.EXTRA_STREAM) as? Uri
        }
        if (stream != null) return stream
        intent.data?.let { return it }
        val raw = extras?.getString(CopyToPc.EXTRA_URI) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    private fun copyOpaque(context: Context, uri: Uri, dest: File) {
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw java.io.IOException("Could not read the Fortress file.")
        if (!dest.exists() || dest.length() <= 0L) {
            dest.delete()
            throw java.io.IOException("Nothing to send.")
        }
    }
}

/**
 * Translucent Fortress fallback for [CopyToPc.ACTION]. Fire-and-forget
 * [startActivity] with CATEGORY_DEFAULT. No result is required.
 */
class CopyToPcActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = CopyToPcWork.requestFrom(intent)
        val caller = CopyToPcWork.activityCaller(this)
        Thread {
            val outcome = CopyToPcWork.deliver(applicationContext, request, caller)
            runOnUiThread {
                Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }
}

/**
 * Preferred Fortress entry. `StellarSend.kt` resolves this exported service
 * and calls [startService].
 */
class CopyToPcService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        val request = CopyToPcWork.requestFrom(intent)
        val caller = CopyToPcWork.serviceCaller(this)
        Thread {
            try {
                val outcome = CopyToPcWork.deliver(applicationContext, request, caller)
                if (outcome.message != CopyToPc.NOT_FORTRESS) {
                    Toast.makeText(applicationContext, outcome.message, Toast.LENGTH_LONG).show()
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    internal fun enterForeground() {
        val notification = Notifier.listening(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTICE_ID, notification)
        }
    }

    companion object {
        const val NOTICE_ID = 42
    }
}

object FortressSignatures {
    fun sameAsSelf(context: Context, caller: String): Boolean {
        return try {
            val mine = certs(context, context.packageName)
            val theirs = certs(context, caller)
            mine.isNotEmpty() && theirs.any { it in mine }
        } catch (_: Exception) {
            false
        }
    }

    fun isKnownFortress(context: Context, caller: String): Boolean {
        if (caller != CopyToPc.FORTRESS_PACKAGE) return false
        return try {
            val hashes = certHashes(context, caller)
            hashes.any { FortressBotHandoff.knownFortressCert(it) }
        } catch (_: Exception) {
            false
        }
    }

    private fun certHashes(context: Context, packageName: String): Set<String> {
        return signers(context, packageName).map { sha256Hex(it.toByteArray()) }.toSet()
    }

    private fun certs(context: Context, packageName: String): Set<String> {
        return signers(context, packageName).map { it.toCharsString() }.toSet()
    }

    private fun signers(context: Context, packageName: String): Array<android.content.pm.Signature> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
    }
}
