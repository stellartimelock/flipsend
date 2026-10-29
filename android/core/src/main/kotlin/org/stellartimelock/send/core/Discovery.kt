package org.stellartimelock.send.core

enum class ProbeReason { MANUAL, LAST_KNOWN, HOTSPOT_GATEWAY, HOTSPOT_RANGE }

data class ProbeTarget(val ip: String, val port: Int, val reason: ProbeReason, val timeoutMs: Int)

data class DiscoveryRequest(
    val lastKnownIp: String?,
    val manualIp: String?,
    val gatewayIp: String?,
    val ownIp: String?,
    val port: Int,
    val allowLoopback: Boolean = false,
    val extraIps: List<String> = emptyList()
)

data class DiscoveryPlan(
    val useMdns: Boolean,
    val useBroadcast: Boolean,
    val mdnsTimeoutMs: Int,
    val broadcastTimeoutMs: Int,
    val probes: List<ProbeTarget>,
    val totalBudgetMs: Int
)

object Hotspot {
    val gateways = setOf(
        "192.168.43.1",
        "192.168.49.1",
        "192.168.137.1",
        "172.20.10.1",
        "192.168.225.1"
    )
}

object DiscoveryPlanner {
    const val MAX_RANGE_PROBES = 16
    const val TOTAL_BUDGET_MS = 4000

    fun plan(request: DiscoveryRequest): DiscoveryPlan {
        val probes = mutableListOf<ProbeTarget>()
        val seen = mutableSetOf<String>()
        fun add(ip: String?, reason: ProbeReason, timeout: Int) {
            val normalized = normalize(ip, request.allowLoopback) ?: return
            if (normalized == request.ownIp || !seen.add(normalized)) return
            probes += ProbeTarget(normalized, request.port, reason, timeout)
        }
        add(request.manualIp, ProbeReason.MANUAL, 800)
        add(request.lastKnownIp, ProbeReason.LAST_KNOWN, 800)
        request.extraIps.forEach { add(it, ProbeReason.LAST_KNOWN, 800) }
        val gateway = normalize(request.gatewayIp, false)
        if (request.gatewayIp in Hotspot.gateways && gateway != null) {
            add(gateway, ProbeReason.HOTSPOT_GATEWAY, 500)
            val prefix = gateway.substringBeforeLast('.')
            var rangeAdded = 0
            fun addRange(candidate: String) {
                if (rangeAdded >= MAX_RANGE_PROBES) return
                val before = probes.size
                add(candidate, ProbeReason.HOTSPOT_RANGE, 200)
                if (probes.size > before) rangeAdded++
            }
            val last = normalize(request.lastKnownIp, false)
            if (last != null) {
                val octet = last.substringAfterLast('.').toIntOrNull()
                if (octet != null && octet in 2..254) addRange("$prefix.$octet")
            }
            var host = 2
            while (host <= 254 && rangeAdded < MAX_RANGE_PROBES) {
                addRange("$prefix.$host")
                host++
            }
        }
        return DiscoveryPlan(true, true, 1500, 1500, probes, TOTAL_BUDGET_MS)
    }

    fun describeFailure(plan: DiscoveryPlan): String {
        val parts = mutableListOf("mDNS", "UDP broadcast")
        plan.probes.filter { it.reason != ProbeReason.HOTSPOT_RANGE }.take(4).forEach {
            val label = when (it.reason) {
                ProbeReason.MANUAL -> "manual IP"
                ProbeReason.LAST_KNOWN -> "last-known IP"
                ProbeReason.HOTSPOT_GATEWAY -> "hotspot gateway"
                ProbeReason.HOTSPOT_RANGE -> "hotspot address"
            }
            parts += "$label ${it.ip}"
        }
        if (plan.probes.any { it.reason == ProbeReason.HOTSPOT_RANGE }) {
            parts += "a bounded sample of the phone-hotspot subnet"
        }
        return "Could not find the paired device. Checked ${parts.joinToString(", ")}. For faster direct transfers, turn on the VPN's allow local network option, or enter its Wi-Fi address. USB still works."
    }

    private fun normalize(ip: String?, allowLoopback: Boolean): String? {
        if (ip.isNullOrBlank() || !LocalAddressPolicy.isAllowedHost(ip, allowLoopback)) return null
        if (AdapterRank.isVpnRange(ip)) return null
        val last = ip.substringAfterLast('.').toIntOrNull() ?: return null
        if (last == 0 || last == 255) {
            if (!ip.startsWith("127.")) return null
        }
        return ip
    }
}
