package org.stellartimelock.send.core

enum class AdapterMedium { WIFI, ETHERNET, VPN, OTHER }

data class AdapterSnapshot(
    val name: String,
    val address: String,
    val up: Boolean,
    val hasGateway: Boolean,
    val medium: AdapterMedium,
    val virtual: Boolean
)

object AdapterRank {
    const val MAX_CANDIDATES = 8

    private val vpnNeedles = listOf(
        "surfshark", "openvpn", "wireguard", "wintun", "nordlynx", "proton", "mullvad", "tailscale", "vpn"
    )

    fun isVirtual(name: String): Boolean {
        val text = name.lowercase()
        return "vethernet" in text || "hyper-v" in text || "hyperv" in text || "wsl" in text
    }

    fun isVpnName(name: String): Boolean {
        val text = name.lowercase()
        if (vpnNeedles.any { it in text }) return true
        return text.split(' ', '-', '_', '(', ')', '.', '/', '\\').any { token ->
            token == "tap" || isTunToken(token)
        }
    }

    fun isTunName(name: String): Boolean {
        val text = name.lowercase()
        if (isTunToken(text)) return true
        return text.split(' ', '-', '_', '(', ')', '.', '/', '\\').any { isTunToken(it) }
    }

    private fun isTunToken(token: String): Boolean {
        if (token == "tun") return true
        return token.startsWith("tun") && token.length <= 6 && token.drop(3).all { it.isDigit() }
    }

    fun isCandidateAddress(address: String): Boolean {
        if (!LocalAddressPolicy.isAllowedHost(address, allowLoopback = false)) return false
        return !address.startsWith("169.254.")
    }

    /**
     * CGNAT and vendor VPN pools. A real Wi-Fi adapter on 10.x is still allowed
     * when [rank] sees WIFI/ETHERNET; these ranges are dropped from QR/hosts
     * and from adapters that are not physical LAN.
     */
    fun isVpnRange(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) return false
        val bytes = parts.map { it.toIntOrNull() ?: return false }
        if (bytes.any { it !in 0..255 }) return false
        if (bytes[0] == 10 && bytes[1] == 14) return true // Surfshark 10.14.0.0/16
        if (bytes[0] == 10 && bytes[1] == 8) return true // OpenVPN 10.8.0.0/16
        if (bytes[0] == 10 && bytes[1] == 7) return true // common WireGuard
        if (bytes[0] == 100 && bytes[1] in 64..127) return true // RFC 6598 / Tailscale
        return false
    }

    fun isLanCandidate(address: String): Boolean =
        isCandidateAddress(address) && !isVpnRange(address)

    fun isLanAdapter(adapter: AdapterSnapshot): Boolean {
        if (!adapter.up || adapter.virtual) return false
        if (adapter.medium == AdapterMedium.VPN) return false
        if (isVpnName(adapter.name) || isTunName(adapter.name)) return false
        if (!isCandidateAddress(adapter.address)) return false
        if (adapter.medium == AdapterMedium.WIFI || adapter.medium == AdapterMedium.ETHERNET) return true
        return !isVpnRange(adapter.address)
    }

    fun rank(adapters: List<AdapterSnapshot>): List<String> {
        return adapters
            .filter { isLanAdapter(it) }
            .sortedWith(compareBy<AdapterSnapshot> { tier(it) }.thenBy { it.address })
            .map { it.address }
            .distinct()
            .take(MAX_CANDIDATES)
    }

    private fun tier(adapter: AdapterSnapshot): Int {
        val medium = when (adapter.medium) {
            AdapterMedium.WIFI, AdapterMedium.ETHERNET -> 0
            AdapterMedium.VPN -> 2
            AdapterMedium.OTHER -> 1
        }
        return medium * 2 + if (adapter.hasGateway) 0 else 1
    }
}

object VpnStatus {
    const val LINE = "A VPN is on. Using the relay."
    const val BYPASS_TIP = "For faster Wi-Fi transfers, add FlipSend to your VPN's bypass list."

    fun line(vpnOn: Boolean, bypassable: Boolean, usingRelay: Boolean): String? {
        if (!vpnOn) return null
        if (!usingRelay && bypassable) return null
        return if (bypassable) LINE else "$LINE $BYPASS_TIP"
    }
}

/** Room membership is a device id, never an IP. */
object RelaySelf {
    fun sameDevice(listenDevice: String?, dialDevice: String?): Boolean {
        if (listenDevice.isNullOrBlank() || dialDevice.isNullOrBlank()) return false
        return listenDevice.equals(dialDevice, ignoreCase = true)
    }
}

object Reachability {
    const val DIRECT = "Direct on Wi-Fi"
    const val PUNCHED = "Direct"
    const val RELAY = "Through relay"
    const val BLUETOOTH = "Bluetooth"
    const val PC =
        "Couldn't reach your PC. For faster direct transfers, turn on the VPN's allow local network option, or enter the PC's Wi-Fi address."
}

object RelayRoom {
    fun paired(a: String, b: String): String {
        val left = if (a <= b) a else b
        val right = if (a <= b) b else a
        return sha256Hex("stellarsend-room-v1|$left|$right".toByteArray(Charsets.UTF_8))
    }

    fun pairing(showingDevice: String): String =
        sha256Hex("stellarsend-pair-v1|$showingDevice".toByteArray(Charsets.UTF_8))

    fun invite(code: String): String =
        sha256Hex("stellarsend-invite-v1|${code.trim().uppercase()}".toByteArray(Charsets.UTF_8))

    fun isToken(room: String?): Boolean =
        room != null && room.length == 64 && room.all { it in '0'..'9' || it in 'a'..'f' }
}

data class RelayEndpoint(val secure: Boolean, val host: String, val port: Int, val path: String)

object RelayAddress {
    fun parse(raw: String?): RelayEndpoint? {
        val text = raw?.trim().orEmpty()
        val schemeSplit = text.split("://", limit = 2)
        if (schemeSplit.size != 2) return null
        val secure = when (schemeSplit[0].lowercase()) {
            "wss", "https" -> true
            "ws", "http" -> false
            else -> return null
        }
        val rest = schemeSplit[1]
        if (rest.isEmpty()) return null
        val slash = rest.indexOf('/')
        val authority = if (slash < 0) rest else rest.substring(0, slash)
        var path = if (slash < 0 || rest.substring(slash).isBlank() || rest.substring(slash) == "/") {
            "/v1"
        } else {
            rest.substring(slash)
        }
        val hostPort = splitHost(authority) ?: return null
        if (!secure && hostPort.first != "localhost" && hostPort.first != "127.0.0.1") return null
        val port = hostPort.second ?: if (secure) 443 else 80
        if (port !in 1..65535) return null
        return RelayEndpoint(secure, hostPort.first, port, path)
    }

    private fun splitHost(authority: String): Pair<String, Int?>? {
        if (authority.isBlank() || '@' in authority) return null
        val colon = authority.lastIndexOf(':')
        if (colon > 0 && authority.indexOf(':') == colon) {
            val host = authority.substring(0, colon)
            val port = authority.substring(colon + 1).toIntOrNull() ?: return null
            if (host.isBlank()) return null
            return host to port
        }
        return authority to null
    }
}
