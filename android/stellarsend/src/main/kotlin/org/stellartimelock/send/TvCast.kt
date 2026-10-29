package org.stellartimelock.send

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaRouter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import org.stellartimelock.send.core.TvCast
import org.stellartimelock.send.core.TvTarget
import org.stellartimelock.send.core.TvTargetKind
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds Chromecasts (mDNS `_googlecast._tcp`) and wireless displays
 * (MediaRouter live video), then starts Android's own mirror path.
 */
class TvCastSession(private val activity: Activity, private val onStatus: (String) -> Unit) {
    @Volatile var active = false
        private set
    @Volatile var targetName: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private var router: MediaRouter? = null
    private var routerCallback: MediaRouter.Callback? = null
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var watchingDisplays = emptySet<Int>()
    private val userStop = AtomicBoolean(false)

    fun toggle() {
        if (active) {
            stop(user = true)
            return
        }
        onStatus(TvCast.LOOKING)
        Thread({
            val targets = discover(activity)
            activity.runOnUiThread { showPicker(targets) }
        }, "flipsend-tv-scan").apply { isDaemon = true; start() }
    }

    fun onResume() {
        if (!active || targetName.isNullOrBlank()) return
        val displays = currentExternalDisplays()
        if (displays.isNotEmpty()) {
            watchingDisplays = displays
            onStatus(TvCast.showing(targetName ?: "TV"))
        }
    }

    fun stop(user: Boolean) {
        userStop.set(user)
        active = false
        targetName = null
        unwatch()
        runCatching {
            val media = router ?: activity.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
            media?.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO, media.defaultRoute)
        }
        onStatus(
            TvCast.dropReason(
                userStop = user,
                displayGone = false,
                unselected = false
            )
        )
    }

    private fun showPicker(targets: List<TvTarget>) {
        val rows = targets.ifEmpty {
            listOf(
                TvTarget(TvCast.SYSTEM_ID, TvCast.SYSTEM_NAME, TvTargetKind.SYSTEM, TvCast.SYSTEM_DETAIL)
            )
        }
        val labels = rows.map { row ->
            if (row.kind == TvTargetKind.SYSTEM) row.name else "${row.name}\n${row.detail}"
        }.toTypedArray()
        val hint = TvCast.noneFound(targets.count { it.kind != TvTargetKind.SYSTEM })
        if (hint.isNotBlank()) onStatus(hint)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(TvCast.BUTTON)
            .setItems(labels) { _, index -> connect(rows[index]) }
            .setNegativeButton("Cancel") { _, _ ->
                if (!active) onStatus("")
            }
            .show()
    }

    private fun connect(target: TvTarget) {
        userStop.set(false)
        targetName = target.name
        watch()
        when (target.kind) {
            TvTargetKind.WIRELESS_DISPLAY -> {
                val media = activity.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
                val route = liveRoutes(media).firstOrNull { it.name.toString() == target.name }
                if (route == null) {
                    onStatus(openSystemCast(target.name))
                    active = true
                    return
                }
                val ok = runCatching {
                    media.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO, route)
                    true
                }.getOrDefault(false)
                if (!ok) {
                    onStatus(openSystemCast(target.name))
                    active = true
                    return
                }
                active = true
                watchingDisplays = currentExternalDisplays()
                onStatus(TvCast.showing(target.name))
            }
            TvTargetKind.GOOGLE_CAST, TvTargetKind.SYSTEM -> {
                active = true
                watchingDisplays = currentExternalDisplays()
                onStatus(openSystemCast(if (target.kind == TvTargetKind.SYSTEM) "" else target.name))
            }
        }
    }

    private fun openSystemCast(preferredName: String): String {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Intent(Settings.ACTION_CAST_SETTINGS))
            add(Intent("android.settings.CAST_SETTINGS"))
            add(Intent("android.settings.WIFI_DISPLAY_SETTINGS"))
        }
        for (intent in intents) {
            val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch.resolveActivity(activity.packageManager) != null) {
                return try {
                    activity.startActivity(launch)
                    if (preferredName.isBlank()) TvCast.PICK_SHEET else TvCast.pickNamed(preferredName)
                } catch (ex: Exception) {
                    TvCast.openFailed(ex.message ?: "The Cast sheet would not open.")
                }
            }
        }
        return TvCast.NO_SHEET
    }

    private fun watch() {
        unwatch()
        val media = activity.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
        router = media
        val callback = object : MediaRouter.Callback() {
            override fun onRouteSelected(router: MediaRouter?, type: Int, info: MediaRouter.RouteInfo?) {}
            override fun onRouteUnselected(router: MediaRouter?, type: Int, info: MediaRouter.RouteInfo?) {
                if (!active || userStop.get()) return
                if (info != null && info.name?.toString() == targetName) {
                    activity.runOnUiThread { stop(user = false) }
                }
            }
            override fun onRouteAdded(router: MediaRouter?, info: MediaRouter.RouteInfo?) {}
            override fun onRouteRemoved(router: MediaRouter?, info: MediaRouter.RouteInfo?) {}
            override fun onRouteChanged(router: MediaRouter?, info: MediaRouter.RouteInfo?) {}
            override fun onRouteGrouped(
                router: MediaRouter?,
                info: MediaRouter.RouteInfo?,
                group: MediaRouter.RouteGroup?,
                index: Int
            ) {}
            override fun onRouteUngrouped(
                router: MediaRouter?,
                info: MediaRouter.RouteInfo?,
                group: MediaRouter.RouteGroup?
            ) {}
            override fun onRouteVolumeChanged(router: MediaRouter?, info: MediaRouter.RouteInfo?) {}
        }
        routerCallback = callback
        media.addCallback(MediaRouter.ROUTE_TYPE_LIVE_VIDEO, callback)
        val displays = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager = displays
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                if (!active) return
                watchingDisplays = watchingDisplays + displayId
                onStatus(TvCast.showing(targetName ?: "TV"))
            }

            override fun onDisplayRemoved(displayId: Int) {
                if (!active || userStop.get()) return
                if (displayId in watchingDisplays) {
                    activity.runOnUiThread { dropped(displayGone = true) }
                }
            }

            override fun onDisplayChanged(displayId: Int) {}
        }
        displayListener = listener
        displays.registerDisplayListener(listener, main)
    }

    private fun dropped(displayGone: Boolean) {
        val reason = TvCast.dropReason(userStop = false, displayGone = displayGone, unselected = !displayGone)
        active = false
        targetName = null
        unwatch()
        onStatus(reason)
    }

    private fun unwatch() {
        routerCallback?.let { callback ->
            runCatching { router?.removeCallback(callback) }
        }
        routerCallback = null
        displayListener?.let { listener ->
            runCatching { displayManager?.unregisterDisplayListener(listener) }
        }
        displayListener = null
        watchingDisplays = emptySet()
    }

    private fun currentExternalDisplays(): Set<Int> {
        val manager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return manager.displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .map { it.displayId }
            .toSet()
    }

    companion object {
        fun discover(context: Context, waitMs: Long = 2_500): List<TvTarget> {
            val found = CopyOnWriteArrayList<TvTarget>()
            found += scanRoutes(context)
            found += scanCast(context, waitMs)
            found += TvTarget(TvCast.SYSTEM_ID, TvCast.SYSTEM_NAME, TvTargetKind.SYSTEM, TvCast.SYSTEM_DETAIL)
            return found.distinctBy { it.id.lowercase() + "|" + it.name.lowercase() }
        }

        private fun scanRoutes(context: Context): List<TvTarget> {
            val media = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter ?: return emptyList()
            return liveRoutes(media).map { route ->
                TvTarget(
                    id = "route:" + route.name,
                    name = route.name.toString(),
                    kind = TvTargetKind.WIRELESS_DISPLAY,
                    detail = "Wireless display / Miracast. FlipSend asks Android to mirror this phone."
                )
            }
        }

        private fun liveRoutes(media: MediaRouter): List<MediaRouter.RouteInfo> {
            val routes = mutableListOf<MediaRouter.RouteInfo>()
            for (i in 0 until media.routeCount) {
                val route = media.getRouteAt(i)
                if (route.supportedTypes and MediaRouter.ROUTE_TYPE_LIVE_VIDEO == 0) continue
                if (TvCast.isDefaultRouteName(route.name?.toString().orEmpty())) continue
                routes += route
            }
            return routes
        }

        @Suppress("DEPRECATION")
        private fun scanCast(context: Context, waitMs: Long): List<TvTarget> {
            val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
            val found = CopyOnWriteArrayList<TvTarget>()
            val done = CountDownLatch(1)
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {
                    done.countDown()
                }
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    done.countDown()
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    done.countDown()
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            val name = service.serviceName?.trim().orEmpty()
                            if (name.isNotBlank()) {
                                found += TvTarget(
                                    id = "cast:$name",
                                    name = name,
                                    kind = TvTargetKind.GOOGLE_CAST,
                                    detail = "Chromecast / Google TV"
                                )
                            }
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val txt = info.attributes.orEmpty().mapValues { (_, bytes) ->
                                runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                            }
                            val name = TvCast.friendlyName(txt, info.serviceName.orEmpty())
                            found += TvTarget(
                                id = "cast:$name",
                                name = name,
                                kind = TvTargetKind.GOOGLE_CAST,
                                detail = "Chromecast / Google TV on this Wi-Fi"
                            )
                        }
                    })
                }
            }
            return try {
                nsd.discoverServices(TvCast.GOOGLE_CAST_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
                done.await(waitMs, TimeUnit.MILLISECONDS)
                runCatching { nsd.stopServiceDiscovery(listener) }
                found.toList()
            } catch (_: Exception) {
                runCatching { nsd.stopServiceDiscovery(listener) }
                found.toList()
            }
        }
    }
}
