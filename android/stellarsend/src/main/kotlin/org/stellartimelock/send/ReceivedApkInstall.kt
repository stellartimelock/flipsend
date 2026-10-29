package org.stellartimelock.send

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import org.stellartimelock.send.core.ApkBatch
import org.stellartimelock.send.core.ReceiveFile
import java.io.File

/** Starts the same install path the Apps screen uses. */
object ReceivedApkInstall {
    fun start(activity: Activity, file: ReceiveFile, refresh: () -> Unit) {
        val disk = File(file.path)
        if (!disk.isFile) {
            Toast.makeText(activity, "That file is no longer on this phone.", Toast.LENGTH_LONG).show()
            return
        }
        if (!ApkInstaller.canInstall(activity)) {
            ApkInstallMarks.needsTap(activity, file.id, ApkBatch.REASON_UNKNOWN_APPS)
            refresh()
            Toast.makeText(activity, "Allow FlipSend to install unknown apps, then try again.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            try {
                activity.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, "Open Settings and allow FlipSend to install unknown apps.", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (ReceiveLive.snap().files.none { it.id == file.id }) {
            ReceiveLive.edit { noteFile(file.id, file.name, file.kind, file.path) }
            ReceiveLive.edit {
                notePackage(
                    file.id,
                    appName = file.appName,
                    packageName = file.packageName,
                    incomingVersion = file.incomingVersion,
                    incomingCode = file.incomingCode,
                    installedVersion = file.installedVersion,
                    installedCode = file.installedCode,
                    hasInstalled = file.hasInstalled,
                    signatureMatch = file.signatureMatch,
                    targetSdk = file.targetSdk,
                    installedBySelf = file.installedBySelf
                )
            }
        }
        ReceiveLive.edit { noteInstall(file.id, "") }
        ApkInstallQueue.start(activity, listOf(file.id))
    }
}
