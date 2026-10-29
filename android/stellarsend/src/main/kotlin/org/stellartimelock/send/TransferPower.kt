package org.stellartimelock.send

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import org.stellartimelock.send.core.TransferHold

/**
 * Wi-Fi and CPU locks for an in-flight transfer. Idle listen does not hold them.
 * LOW_LATENCY when the platform has it, otherwise HIGH_PERF. Wake lock times out
 * at 10 minutes if a transfer fails to release.
 */
internal object TransferPower : TransferHold.Hooks {
    private const val WIFI_TAG = "FlipSend:receive"
    private const val WAKE_TAG = "FlipSend::listen"

    @Volatile private var app: Context? = null
    private val gate = Any()
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun attach(context: Context) {
        app = context.applicationContext
        TransferHold.hooks = this
    }

    override fun acquire() {
        val context = app ?: return
        synchronized(gate) {
            acquireWifi(context)
            acquireWake(context)
        }
    }

    override fun release() {
        synchronized(gate) {
            wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
            wakeLock = null
            wifiLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
            wifiLock = null
        }
    }

    private fun acquireWifi(context: Context) {
        wifiLock?.takeIf { it.isHeld }?.let { return }
        val wm = context.getSystemService(WifiManager::class.java) ?: return
        val lock = runCatching { createWifiLock(wm) }.getOrElse { createHighPerf(wm) }
        lock.setReferenceCounted(false)
        runCatching { lock.acquire() }
        wifiLock = lock
    }

    private fun acquireWake(context: Context) {
        wakeLock?.takeIf { it.isHeld }?.let { return }
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
        lock.setReferenceCounted(false)
        runCatching { lock.acquire(TransferHold.WAKE_MS) }
        wakeLock = lock
    }

    private fun createWifiLock(wm: WifiManager): WifiManager.WifiLock {
        return if (Build.VERSION.SDK_INT >= 29) {
            wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_TAG)
        } else {
            createHighPerf(wm)
        }
    }

    @Suppress("DEPRECATION")
    private fun createHighPerf(wm: WifiManager): WifiManager.WifiLock =
        wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)
}
