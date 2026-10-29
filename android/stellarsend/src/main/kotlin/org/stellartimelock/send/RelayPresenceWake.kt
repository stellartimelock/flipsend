package org.stellartimelock.send

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import org.stellartimelock.send.core.RelayPresence

/**
 * Short timed CPU hold for one relay rejoin, plus an inexact allow-while-idle
 * alarm if the rejoin did not finish. Nothing is held while the phone waits in
 * its relay room.
 */
internal object RelayPresenceWake : RelayPresence.Hooks {
    private const val WAKE_TAG = "FlipSend::rejoin"
    private const val REQUEST = 4107

    @Volatile private var app: Context? = null
    private val gate = Any()
    private var wakeLock: PowerManager.WakeLock? = null

    fun attach(context: Context) {
        app = context.applicationContext
        RelayPresence.hooks = this
    }

    override fun hold() {
        val context = app ?: return
        synchronized(gate) {
            val lock = wakeLock ?: context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
                ?.also { it.setReferenceCounted(false); wakeLock = it }
                ?: return
            runCatching { lock.acquire(RelayPresence.HOLD_MS) }
        }
    }

    override fun release() {
        synchronized(gate) {
            wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        }
    }

    override fun scheduleRetry(delayMs: Long) {
        val context = app ?: return
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pending(context)
            )
        }
    }

    override fun cancelRetry() {
        val context = app ?: return
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.cancel(pending(context)) }
    }

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST,
            Intent(context, RelayRejoinReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** The retry alarm: rejoin the relay if the phone is still not in its room. */
class RelayRejoinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RelayPresenceWake.attach(context)
        RelayPresence.kick(RelayPresence.ALARM)
    }
}
