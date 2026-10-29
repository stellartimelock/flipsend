package org.stellartimelock.send

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.stellartimelock.send.core.RelayPlans
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object BackgroundListen {
    const val CHANNEL_ID = "background_listening"
    private const val PREFS = "stellarsend"
    private const val LISTEN_KEY = "listen_in_background"
    private const val RELAY_KEY = "relay_url"
    private const val RELAY_SET = "relay_url_set"

    private val startedActivities = AtomicInteger(0)
    private val installed = AtomicBoolean(false)

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(LISTEN_KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(LISTEN_KEY, enabled)
            .apply()
        sync(context)
    }

    /**
     * A missing preference uses the built-in relay. A saved blank address stays on Wi-Fi.
     * An older install that already stored a URL keeps that URL.
     */
    fun relayUrl(context: Context): String? {
        val prefs = prefs(context)
        val stored = prefs.getString(RELAY_KEY, null)?.trim()?.ifBlank { null }
        if (prefs.getBoolean(RELAY_SET, false)) return stored
        return stored ?: RelayPlans.DEFAULT_RELAY
    }

    fun setRelayUrl(context: Context, url: String?) {
        prefs(context).edit()
            .putString(RELAY_KEY, url?.trim().orEmpty())
            .putBoolean(RELAY_SET, true)
            .apply()
        StellarSendClient(context).refreshRelay()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun attach(context: Context) {
        val app = context.applicationContext as? Application ?: return
        if (!installed.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities.incrementAndGet()
                org.stellartimelock.send.core.LanAnnounce.boost()
                sync(activity)
                org.stellartimelock.send.core.RelayPresence.kick(org.stellartimelock.send.core.RelayPresence.APP_OPEN)
            }

            override fun onActivityStopped(activity: Activity) {
                val left = startedActivities.decrementAndGet()
                if (left < 0) startedActivities.set(0)
                if (activity.isChangingConfigurations) return
                sync(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun keepSocketAfterService(context: Context): Boolean = !enabled(context) && startedActivities.get() > 0

    /** True when FlipSend has a started activity. Used to decide whether the install confirm can open now. */
    fun hasVisibleWindow(): Boolean = startedActivities.get() > 0

    fun openChannelSettings(activity: Activity) {
        Notifier.ensureChannels(activity)
        val channel = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
        }
        try {
            activity.startActivity(channel)
        } catch (_: android.content.ActivityNotFoundException) {
            val appSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            }
            activity.startActivity(appSettings)
        }
    }

    private fun sync(context: Context) {
        val app = context.applicationContext
        val client = StellarSendClient(app)
        if (enabled(app)) {
            if (startedActivities.get() > 0) {
                runCatching {
                    ContextCompat.startForegroundService(app, Intent(app, TransferService::class.java))
                }.onFailure {
                    client.start()
                }
            }
            return
        }
        app.stopService(Intent(app, TransferService::class.java))
        if (startedActivities.get() > 0) client.start() else client.stop()
    }
}
