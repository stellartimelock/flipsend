package org.stellartimelock.send

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.stellartimelock.send.core.CloudUpdate
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads a signed FlipSend build when the app is opened or a push asks.
 * Never polls.
 */
object CloudPull {
    private val checked = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    fun onOpened(context: Context, pushAsked: Boolean = false) {
        if (!CloudUpdate.shouldCheck(checked.get(), pushAsked)) return
        if (!pushAsked) checked.set(true)
        val app = context.applicationContext
        Thread {
            runCatching { check(app) }.onFailure {
                android.util.Log.i("FlipSend", "Update check skipped: ${it.message}")
            }
        }.start()
    }

    fun fromIntent(intent: Intent?): Boolean =
        intent?.action == CloudUpdate.ACTION_CHECK ||
            intent?.getBooleanExtra("flipsend.update", false) == true

    private fun check(context: Context) {
        if (!BuildConfig.CLOUD_UPDATE) return
        val json = get(CloudUpdate.MANIFEST_URL) ?: return
        val manifest = CloudUpdate.parse(json) ?: return
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) installed.longVersionCode.toInt() else @Suppress("DEPRECATION") installed.versionCode
        if (!CloudUpdate.newerThan(manifest, code)) return
        val apk = download(context, manifest) ?: return
        val incoming = ApkArchives.read(context, apk) ?: run {
            apk.delete()
            return
        }
        if (incoming.packageName != context.packageName) {
            apk.delete()
            return
        }
        val prefix = incoming.certificates.firstOrNull()?.take(8)?.lowercase()
        if (!CloudUpdate.signerMatches(prefix, manifest)) {
            apk.delete()
            return
        }
        if (manifest.sha256.isNotBlank() && sha256(apk) != manifest.sha256) {
            apk.delete()
            return
        }
        main.post {
            ReceiveLive.edit {
                noteSender("FlipSend update")
                noteFile("cloud-update", apk.name, "apk", apk.absolutePath)
            }
            ReceiveApk.remember(context, "cloud-update", apk)
            ApkInstallQueue.start(context, listOf("cloud-update"))
        }
    }

    private fun download(context: Context, manifest: CloudUpdate.Manifest): File? {
        val inbox = File(context.filesDir, "inbox").apply { mkdirs() }
        val dest = File(inbox, "FlipSend-${manifest.versionName}.apk")
        val connection = (URL(manifest.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        return try {
            connection.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.takeIf { it.isFile && it.length() > 1_000 }
        } catch (_: Exception) {
            dest.delete()
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
        }
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16_384)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

}
