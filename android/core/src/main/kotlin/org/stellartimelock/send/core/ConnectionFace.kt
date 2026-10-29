package org.stellartimelock.send.core

/** How FlipSend is connected, plus the other routes that are ready. */
enum class RouteKind { LAN, DIRECT, RELAY, USB, BLUETOOTH, OFFLINE }

data class ConnectionInput(
    val paired: Boolean,
    val listening: Boolean,
    val pairingWindowOpen: Boolean,
    val onLan: Boolean,
    val lanUp: Boolean,
    val directUp: Boolean,
    val directAvailable: Boolean,
    val relayUp: Boolean,
    val relayAvailable: Boolean,
    val usbUp: Boolean,
    val usbAvailable: Boolean,
    val lastError: String? = null,
    val directExpected: Boolean = false,
    val bluetoothUp: Boolean = false,
    val bluetoothAvailable: Boolean = false,
    val bluetoothNeedsSetup: Boolean = false
) {
    companion object {
        /** First paint, before the engine is open. Every real route is down. */
        fun offline() = ConnectionInput(
            paired = false,
            listening = false,
            pairingWindowOpen = false,
            onLan = false,
            lanUp = false,
            directUp = false,
            directAvailable = false,
            relayUp = false,
            relayAvailable = false,
            usbUp = false,
            usbAvailable = false
        )
    }
}

data class RouteChoice(
    val kind: RouteKind,
    val label: String,
    val active: Boolean,
    val available: Boolean,
    val best: Boolean
)

data class ConnectionFace(
    val active: RouteKind,
    val headline: String,
    val pathPill: String,
    val routesLine: String,
    val hint: String,
    val switchTo: RouteKind?,
    val routes: List<RouteChoice>
) {
    val connected: Boolean get() = active != RouteKind.OFFLINE

    /** Headline, pill, route line, and hint. They describe one state. */
    fun indicators(): List<String> = listOf(headline, pathPill, routesLine, hint).filter { it.isNotBlank() }

    fun agrees(): Boolean {
        if (routes.count { it.best } > 1) return false
        if (routes.count { it.active } > 1) return false
        if (hint.contains('\n') || routesLine.contains('\n')) return false
        val connectedWords = indicators().any { it.contains("Not connected", true) || it.contains("Disconnected", true) }
        if ((headline == HomeStatus.CONNECTED || headline == HomeStatus.LINKED) && connectedWords) return false
        if (headline == HomeStatus.KEYS_DONT_MATCH && connectedWords) return false
        if (active == RouteKind.OFFLINE) {
            if (headline == HomeStatus.CONNECTED || headline == HomeStatus.LINKED || pathPill.isNotBlank()) return false
            if (routesLine.contains(" now")) return false
        } else {
            if (headline != HomeStatus.CONNECTED && headline != HomeStatus.LINKED) return false
            if (routes.none { it.active && it.kind == active }) return false
            if (pathPill.isBlank()) return false
        }
        if (switchTo != null && (switchTo == active || routes.none { it.kind == switchTo && it.best })) return false
        return true
    }
}

object ConnectionState {
    const val LAN = "LAN"
    const val DIRECT = "Direct"
    const val RELAY = "Relay"
    const val USB = "USB"
    const val BLUETOOTH = "Bluetooth"
    private val rank = listOf(RouteKind.LAN, RouteKind.BLUETOOTH, RouteKind.DIRECT, RouteKind.USB, RouteKind.RELAY)

    fun face(input: ConnectionInput): ConnectionFace {
        val active = when {
            input.lanUp -> RouteKind.LAN
            input.bluetoothUp -> RouteKind.BLUETOOTH
            input.directUp -> RouteKind.DIRECT
            input.usbUp -> RouteKind.USB
            input.relayUp -> RouteKind.RELAY
            else -> RouteKind.OFFLINE
        }
        val available = mapOf(
            RouteKind.LAN to input.onLan,
            RouteKind.BLUETOOTH to input.bluetoothAvailable,
            RouteKind.DIRECT to input.directAvailable,
            RouteKind.USB to input.usbAvailable,
            RouteKind.RELAY to input.relayAvailable
        )
        val bestKind = rank.firstOrNull { available[it] == true }
        val routes = listOf(RouteKind.LAN, RouteKind.BLUETOOTH, RouteKind.DIRECT, RouteKind.RELAY, RouteKind.USB).map { kind ->
            val up = kind == active
            RouteChoice(kind, label(kind), up, up || available[kind] == true, kind == bestKind && (up || available[kind] == true))
        }.filter { it.available }
        val switchTo = routes.firstOrNull { it.best && !it.active }?.kind
        val blocked = TrustNewKey.blocksConnected(input.lastError)
        val shown = if (blocked) RouteKind.OFFLINE else active
        val headline = when {
            blocked -> HomeStatus.KEYS_DONT_MATCH
            shown != RouteKind.OFFLINE -> HomeStatus.CONNECTED
            input.pairingWindowOpen && !input.paired -> HomeStatus.WAITING
            else -> HomeStatus.NOT_CONNECTED
        }
        val routesLine = if (NetworkHop.isSwitch(input.lastError)) {
            NetworkHop.LINE
        } else {
            line(routes, active)
        }
        val hint = when {
            NetworkHop.isSwitch(input.lastError) -> ""
            input.onLan && !input.lanUp && (active == RouteKind.RELAY || active == RouteKind.BLUETOOTH) ->
                "Same Wi-Fi did not answer."
            input.directExpected && !input.directAvailable && !input.directUp && active == RouteKind.RELAY ->
                "Direct link did not open."
            else -> ""
        }
        return ConnectionFace(
            active = shown,
            headline = headline,
            pathPill = if (shown == RouteKind.OFFLINE) "" else label(shown),
            routesLine = if (blocked) "" else routesLine,
            hint = if (blocked) {
                input.lastError?.trim().orEmpty().ifBlank { TrustNewKey.message(null) }
            } else {
                hint
            },
            switchTo = if (blocked) null else switchTo,
            routes = if (blocked) emptyList() else routes
        )
    }

    fun label(kind: RouteKind): String = when (kind) {
        RouteKind.LAN -> LAN
        RouteKind.DIRECT -> DIRECT
        RouteKind.RELAY -> RELAY
        RouteKind.USB -> USB
        RouteKind.BLUETOOTH -> BLUETOOTH
        RouteKind.OFFLINE -> ""
    }

    private fun line(routes: List<RouteChoice>, active: RouteKind): String {
        if (routes.isEmpty()) return ""
        val parts = mutableListOf<String>()
        routes.firstOrNull { it.active }?.let { parts += "${it.label} now" }
        routes.filter { it.available && !it.active }.forEach { route ->
            parts += if (route.best) "${route.label} available (best)" else "${route.label} available"
        }
        if (active == RouteKind.OFFLINE && parts.none { it.endsWith("(best)") } && routes.any { it.best }) {
            // The best route is the only one and it is not up yet. Mark it.
        }
        if (active != RouteKind.OFFLINE && routes.any { it.best && !it.active }) parts += "tap to switch"
        return parts.joinToString(", ")
    }
}

/** One home-screen row. Bluetooth is a real route, same badges as the others. */
enum class ConnectionMark { CONNECTED, AVAILABLE, OFF, NOT_SET_UP, NOT_SUPPORTED }

data class ConnectionMethod(
    val name: String,
    val mark: ConnectionMark,
    val icon: String,
    val route: String?
) {
    val label: String
        get() = when (mark) {
            ConnectionMark.CONNECTED -> "Connected"
            ConnectionMark.AVAILABLE -> "Available"
            ConnectionMark.OFF -> "Off"
            ConnectionMark.NOT_SET_UP -> "Not set up"
            ConnectionMark.NOT_SUPPORTED -> "Not supported"
        }

    /** Only the better route that is already ready can be tapped. */
    val canSwitch: Boolean get() = mark == ConnectionMark.AVAILABLE && !route.isNullOrBlank()
}

object ConnectionMethods {
    const val WIFI = "Wi-Fi"
    const val DIRECT = "Direct"
    const val RELAY = "Relay"
    const val USB = "USB"
    const val BLUETOOTH = "Bluetooth"

    fun rows(input: ConnectionInput): List<ConnectionMethod> {
        val face = ConnectionState.face(input)
        val live = !TrustNewKey.blocksConnected(input.lastError)
        return listOf(
            method(WIFI, "wifi", RouteKind.LAN, live && input.lanUp, input.onLan, false, face),
            method(DIRECT, "direct", RouteKind.DIRECT, live && input.directUp, input.directAvailable, false, face),
            method(
                RELAY,
                "relay",
                RouteKind.RELAY,
                live && input.relayUp,
                input.relayAvailable,
                input.paired && !input.relayUp && !input.relayAvailable,
                face
            ),
            method(USB, "usb", RouteKind.USB, live && input.usbUp, input.usbAvailable, !input.usbUp && !input.usbAvailable, face),
            method(
                BLUETOOTH,
                "bluetooth",
                RouteKind.BLUETOOTH,
                live && input.bluetoothUp,
                input.bluetoothAvailable,
                input.bluetoothNeedsSetup,
                face
            )
        )
    }

    private fun method(
        name: String,
        icon: String,
        kind: RouteKind,
        up: Boolean,
        available: Boolean,
        needsSetup: Boolean,
        face: ConnectionFace
    ): ConnectionMethod {
        val mark = when {
            up -> ConnectionMark.CONNECTED
            available -> ConnectionMark.AVAILABLE
            needsSetup -> ConnectionMark.NOT_SET_UP
            else -> ConnectionMark.OFF
        }
        val route = if (face.switchTo == kind) ConnectionState.label(kind) else null
        return ConnectionMethod(name, mark, icon, route)
    }
}
