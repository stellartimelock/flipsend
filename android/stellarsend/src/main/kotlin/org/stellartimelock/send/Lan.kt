package org.stellartimelock.send

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.stellartimelock.send.core.AdapterMedium
import org.stellartimelock.send.core.AdapterRank
import org.stellartimelock.send.core.AdapterSnapshot
import org.stellartimelock.send.core.BeaconCodec
import org.stellartimelock.send.core.DiscoveryPlanner
import org.stellartimelock.send.core.DiscoveryRequest
import org.stellartimelock.send.core.LocalAddressPolicy
import org.stellartimelock.send.core.MdnsPackets
import org.stellartimelock.send.core.ProtocolLimits
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class VpnInfo(val on: Boolean, val bypassable: Boolean, val wifi: Network?)

internal data class DiscoveryHit(val ip: String, val port: Int, val via: String)

internal object NetInfo {
    fun vpn(context: Context): VpnInfo {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return VpnInfo(on = false, bypassable = true, wifi = null)
        var on = false
        var wifi: Network? = null
        for (network in manager.allNetworks) {
            val caps = manager.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                wifi = network
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                on = true
            }
        }
        // VpnTransportInfo.isBypassable is not a public SDK API. A live Wi-Fi
        // Network is the signal that bindSocket can skip the tun.
        return VpnInfo(on, bypassable = !on || wifi != null, wifi)
    }

    fun bindLan(socket: Socket, context: Context) {
        val info = vpn(context)
        val wifi = info.wifi ?: return
        if (!info.on) return
        runCatching { wifi.bindSocket(socket) }
    }

    fun ipv4(context: Context): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val info = vpn(context)
        val network = info.wifi ?: manager.activeNetwork
        val link = manager.getLinkProperties(network) ?: return null
        return link.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull { AdapterRank.isLanCandidate(it) }
    }

    fun candidates(context: Context): List<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val facts = mutableListOf<AdapterSnapshot>()
        for (network in manager.allNetworks) {
            val link = manager.getLinkProperties(network) ?: continue
            val caps = manager.getNetworkCapabilities(network)
            val name = link.interfaceName ?: ""
            val hasGateway = link.routes.any { it.isDefaultRoute && it.gateway is Inet4Address }
            val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            val medium = when {
                vpn || AdapterRank.isVpnName(name) -> AdapterMedium.VPN
                wifi -> AdapterMedium.WIFI
                ethernet -> AdapterMedium.ETHERNET
                else -> AdapterMedium.OTHER
            }
            link.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().forEach { address ->
                val ip = address.hostAddress ?: return@forEach
                facts += AdapterSnapshot(name, ip, up = true, hasGateway = hasGateway, medium = medium, virtual = AdapterRank.isVirtual(name))
            }
        }
        return AdapterRank.rank(facts)
    }

    fun onWifi(context: Context): Boolean = vpn(context).wifi != null

    fun gateway(context: Context): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val info = vpn(context)
        val network = info.wifi ?: manager.activeNetwork
        val link = manager.getLinkProperties(network) ?: return null
        return link.routes
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
            ?.takeIf { AdapterRank.isLanCandidate(it) || LocalAddressPolicy.isAllowedHost(it, false) }
    }
}

internal object LanFinder {
    fun announce(deviceId: UUID, port: Int, name: String, ownIp: String?, code: String? = null, onWifi: Boolean = true) {
        if (!onWifi) return
        runCatching {
            val payload = BeaconCodec.encode(deviceId, port, name, code)
            DatagramSocket().use { udp ->
                udp.broadcast = true
                udp.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), ProtocolLimits.UDP_PORT))
            }
        }
        if (ownIp.isNullOrBlank()) return
        runCatching {
            val address = InetAddress.getByName(ownIp)
            val announcement = MdnsPackets.buildAnnouncement(deviceId, port, address)
            val query = MdnsPackets.buildQuery()
            MulticastSocket().use { socket ->
                val group = InetAddress.getByName("224.0.0.251")
                socket.send(DatagramPacket(announcement, announcement.size, group, 5353))
                socket.send(DatagramPacket(query, query.size, group, 5353))
            }
        }
    }

    fun find(context: Context, peerId: UUID, request: DiscoveryRequest): DiscoveryHit? {
        val plan = DiscoveryPlanner.plan(request)
        val hit = AtomicReference<DiscoveryHit?>(null)
        val stop = AtomicBoolean(false)
        val deadline = System.currentTimeMillis() + plan.totalBudgetMs
        val threads = mutableListOf<Thread>()
        fun launch(body: () -> Unit) {
            threads += Thread {
                runCatching { body() }
            }.also { it.isDaemon = true; it.start() }
        }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val multicast = wifi?.createMulticastLock("stellarsend")
        multicast?.setReferenceCounted(false)
        runCatching { multicast?.acquire() }
        try {
            launch { announce(peerId, request.port, "peer", request.ownIp) }
            if (plan.useMdns) launch { listenMdns(peerId, deadline, stop, hit) }
            if (plan.useBroadcast) launch { listenUdp(peerId, deadline, stop, hit) }
            val probes = ArrayDeque(plan.probes)
            repeat(4) {
                launch {
                    while (!stop.get() && System.currentTimeMillis() < deadline) {
                        val probe = synchronized(probes) {
                            if (probes.isEmpty()) null else probes.removeFirst()
                        } ?: return@launch
                        if (tcp(context, probe.ip, probe.port, probe.timeoutMs)) {
                            hit.compareAndSet(null, DiscoveryHit(probe.ip, probe.port, probe.reason.name))
                            stop.set(true)
                        }
                    }
                }
            }
            while (hit.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(40)
            }
        } finally {
            stop.set(true)
            threads.forEach { it.interrupt() }
            runCatching { if (multicast?.isHeld == true) multicast.release() }
        }
        return hit.get()
    }

    fun findByCode(code: String, ownIp: String?, budgetMs: Long = 4000): DiscoveryHit? {
        val hit = AtomicReference<DiscoveryHit?>(null)
        val stop = AtomicBoolean(false)
        val deadline = System.currentTimeMillis() + budgetMs
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true
        socket.bind(InetSocketAddress(ProtocolLimits.UDP_PORT))
        socket.soTimeout = 300
        try {
            val buffer = ByteArray(512)
            while (!stop.get() && System.currentTimeMillis() < deadline && hit.get() == null) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val beacon = BeaconCodec.tryParse(packet.data.copyOf(packet.length)) ?: continue
                val advertised = beacon.optString("code")
                if (!org.stellartimelock.send.core.ShortCode.same(code, advertised)) continue
                val remote = packet.address?.hostAddress ?: continue
                if (remote == ownIp || !LocalAddressPolicy.isAllowedHost(remote, false)) continue
                hit.set(DiscoveryHit(remote, beacon.getInt("port"), "broadcast"))
            }
        } finally {
            socket.close()
        }
        return hit.get()
    }

    private fun listenMdns(peerId: UUID, deadline: Long, stop: AtomicBoolean, hit: AtomicReference<DiscoveryHit?>) {
        val socket = MulticastSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(5353))
        socket.timeToLive = 1
        @Suppress("DEPRECATION")
        socket.joinGroup(InetAddress.getByName("224.0.0.251"))
        socket.soTimeout = 300
        try {
            val query = MdnsPackets.buildQuery()
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName("224.0.0.251"), 5353))
            val buffer = ByteArray(2048)
            while (!stop.get() && System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val announcement = MdnsPackets.tryParseAnnouncement(packet.data.copyOf(packet.length)) ?: continue
                if (announcement.deviceId == peerId && LocalAddressPolicy.isAllowedHost(announcement.address, false)) {
                    hit.compareAndSet(null, DiscoveryHit(announcement.address, announcement.port, "mdns"))
                    stop.set(true)
                }
            }
        } finally {
            socket.close()
        }
    }

    private fun listenUdp(peerId: UUID, deadline: Long, stop: AtomicBoolean, hit: AtomicReference<DiscoveryHit?>) {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true
        socket.bind(InetSocketAddress(ProtocolLimits.UDP_PORT))
        socket.soTimeout = 300
        try {
            val buffer = ByteArray(512)
            while (!stop.get() && System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val beacon = BeaconCodec.tryParse(packet.data.copyOf(packet.length)) ?: continue
                if (!beacon.getString("id").equals(peerId.toString(), true)) continue
                val remote = packet.address?.hostAddress ?: continue
                if (!LocalAddressPolicy.isAllowedHost(remote, false)) continue
                hit.compareAndSet(null, DiscoveryHit(remote, beacon.getInt("port"), "broadcast"))
                stop.set(true)
            }
        } finally {
            socket.close()
        }
    }

    private fun tcp(context: Context, ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                NetInfo.bindLan(socket, context)
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
