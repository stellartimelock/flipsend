package org.stellartimelock.send.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Wi-Fi and wake locks are only held while a transfer is in flight.
 * Concurrent send/receive share one hold via a ref-count.
 */
object TransferHold {
    const val WAKE_MS = 10 * 60_000L

    interface Hooks {
        fun acquire()
        fun release()
    }

    @Volatile var hooks: Hooks? = null
    private val depth = AtomicInteger(0)

    val held: Boolean get() = depth.get() > 0
    val count: Int get() = depth.get().coerceAtLeast(0)

    fun enter() {
        if (depth.getAndIncrement() == 0) hooks?.acquire()
    }

    fun leave() {
        val left = depth.decrementAndGet()
        if (left == 0) {
            hooks?.release()
        } else if (left < 0) {
            depth.set(0)
        }
    }

    fun <T> during(block: () -> T): T {
        enter()
        try {
            return block()
        } finally {
            leave()
        }
    }

    fun resetForTest() {
        depth.set(0)
        hooks = null
    }
}

/**
 * LAN UDP/mDNS announce. Off Wi-Fi it is skipped. Fast (3 s) only during
 * pairing/repair and for two minutes after screen-on, a network change,
 * app foreground, or a transfer. Otherwise 30 s.
 */
object LanAnnounce {
    const val FAST_MS = 3_000L
    const val SLOW_MS = 30_000L
    const val WINDOW_MS = 120_000L

    private val lastBoostMs = AtomicLong(0)
    private val waitThread = AtomicReference<Thread?>(null)

    fun boost(nowMs: Long = System.currentTimeMillis()) {
        lastBoostMs.set(nowMs)
        waitThread.get()?.interrupt()
    }

    fun lastBoostMs(): Long = lastBoostMs.get()

    fun armWait(thread: Thread) {
        waitThread.set(thread)
    }

    fun intervalMs(
        onWifi: Boolean,
        pairingOrRepair: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        lastBoostMs: Long = this.lastBoostMs.get()
    ): Long? {
        if (!onWifi) return null
        if (pairingOrRepair) return FAST_MS
        if (nowMs - lastBoostMs <= WINDOW_MS) return FAST_MS
        return SLOW_MS
    }

    fun sleepMs(intervalMs: Long?): Long = intervalMs ?: SLOW_MS

    fun resetForTest() {
        lastBoostMs.set(0)
        waitThread.set(null)
    }
}

/**
 * Idle relay rejoin: 1, 2, 5, 15, 30, then 60 s cap. Reset after a join
 * that stayed up more than 30 s.
 */
class RelayRejoin {
    private var attempt = 0

    fun delayAfterClose(upForMs: Long): Long {
        if (upForMs >= RelayWake.STABLE_JOIN_MS) attempt = 0
        val delay = RelayWake.rejoinMs(attempt)
        if (attempt < RelayWake.REJOIN_BACKOFF_MS.lastIndex) attempt++
        return delay
    }

    fun reset() {
        attempt = 0
    }
}

/** WebSocket close payload: 2-byte code plus optional UTF-8 reason. */
object RelayClose {
    fun describe(payload: ByteArray?): String {
        if (payload == null || payload.isEmpty()) return "code=none"
        val code = if (payload.size >= 2) {
            ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        } else {
            0
        }
        val reason = if (payload.size > 2) {
            payload.copyOfRange(2, payload.size).toString(Charsets.UTF_8).trim()
        } else {
            ""
        }
        return if (reason.isEmpty()) "code=$code" else "code=$code $reason"
    }
}

/**
 * Keeps the idle relay presence without a permanent wake lock. While the phone
 * waits in its relay room nothing is held: the relay's keepalives wake the radio.
 * After a drop, or when screen-on, a network change, or app open asks for a
 * rejoin, a short timed hold lets the rejoin finish before the CPU sleeps, and a
 * Doze-friendly alarm tries again if it still is not back.
 */
object RelayPresence {
    /** Longest a single rejoin may keep the CPU awake. Released as soon as the room is joined. */
    const val HOLD_MS = 90_000L
    /** Inexact, allow-while-idle alarm for a rejoin that did not finish under the hold. */
    const val ALARM_MS = 5 * 60_000L

    const val SCREEN_ON = "screen on"
    const val NETWORK = "network change"
    const val APP_OPEN = "app open"
    const val ALARM = "alarm"
    const val SERVICE = "service start"

    interface Hooks {
        fun hold()
        fun release()
        fun scheduleRetry(delayMs: Long)
        fun cancelRetry()
    }

    @Volatile var hooks: Hooks? = null
    @Volatile var rejoin: ((String) -> Unit)? = null

    /** Hold the CPU only for the first quick rejoins after a drop. After that the alarm and the kicks take over. */
    const val HOLD_DROPS = 3

    fun holdsAfterDrop(dropsInRow: Int): Boolean = dropsInRow in 1..HOLD_DROPS

    /** A rejoin is only needed when the phone is not already waiting in its room. */
    fun needsRejoin(waitingInRoom: Boolean): Boolean = !waitingInRoom

    /** Screen-on, network change, app open, or the retry alarm. */
    fun kick(reason: String) {
        rejoin?.invoke(reason)
    }

    fun resetForTest() {
        hooks = null
        rejoin = null
    }
}
