package org.stellartimelock.stellarsend

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.stellartimelock.send.ApkInstallReceiver
import org.stellartimelock.send.ApkSessions
import org.stellartimelock.send.BackgroundListen
import org.stellartimelock.send.ReceivedLog
import org.stellartimelock.send.core.ApkInstallDelivery
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApkInstallStatusTest {
    @Test
    fun statusPendingIntentIsAMutableBroadcastToTheReceiver() {
        val context = RuntimeEnvironment.getApplication()
        val intent = ApkSessions.statusCallbackIntent(context, 42, "hist-1", false)
        assertEquals(ApkInstallReceiver.ACTION, intent.action)
        assertEquals(ApkInstallReceiver::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.`package`)
        assertFalse(intent.component?.className?.contains("ApkInstallStatusActivity") == true)
        assertEquals("broadcast", ApkInstallDelivery.STATUS_CALLBACK)

        val pending = ApkSessions.statusPendingIntent(context, 42, "hist-1", false)
        assertTrue(pending.isBroadcast)
        assertFalse(pending.isActivity)
        assertFalse(pending.isImmutable)
    }

    @Test
    fun backgroundPendingUserActionPostsTapToInstallAndMarksPending() {
        val context = RuntimeEnvironment.getApplication()
        val apk = File(context.cacheDir, "CLOCK-1.2.1.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val entry = ReceivedLog(context).add(apk, "apk", 1_000)
        ReceivedLog(context).noteApp(
            entry.id,
            appName = "Clock",
            packageName = "org.example.clock",
            incomingVersion = "1.2.1",
            incomingCode = 121,
            installedVersion = "1.2.0",
            installedCode = 120,
            hasInstalled = true,
            signatureMatch = "match"
        )
        ReceivedLog(context).mark(entry.id, "pending", "", 672215793)
        assertFalse(BackgroundListen.hasVisibleWindow())
        assertFalse(ApkInstallDelivery.openConfirmNow(BackgroundListen.hasVisibleWindow()))

        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        val status = Intent(context, ApkInstallReceiver::class.java).apply {
            action = ApkInstallReceiver.ACTION
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            putExtra(PackageInstaller.EXTRA_SESSION_ID, 672215793)
            putExtra("historyId", entry.id)
            putExtra(Intent.EXTRA_INTENT, confirm)
        }
        ApkInstallReceiver().onReceive(context, status)

        val stored = ReceivedLog(context).find(entry.id)
        assertEquals("pending", stored?.installStatus)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = manager.activeNotifications.map { it.notification }
        assertTrue(posted.any { note ->
            val title = note.extras.getString(android.app.Notification.EXTRA_TITLE).orEmpty()
            title == "Tap to install Clock"
        })
        apk.delete()
        File(context.filesDir, "received.json").delete()
    }
}
