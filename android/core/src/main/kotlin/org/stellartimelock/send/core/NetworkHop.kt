package org.stellartimelock.send.core

/**
 * A phone moving between networks. Same Wi-Fi stays a direct LAN connection.
 * A different network, including mobile data, is found again and uses the
 * relay until a LAN peer answers. Neighboring Wi-Fi networks are not the
 * same LAN. Those use the punched direct link, then the relay.
 */
enum class NetTransport { NONE, WIFI, CELLULAR, OTHER }

data class NetworkPlace(val transport: NetTransport, val address: String?)

object NetworkHop {
    const val LINE = "Switching networks"

    fun changed(before: NetworkPlace?, after: NetworkPlace): Boolean {
        if (before == null) return false
        return before.transport != after.transport || before.address != after.address
    }

    fun path(foundOnLan: Boolean): String =
        if (foundOnLan) Reachability.DIRECT else Reachability.RELAY

    fun isSwitch(text: String?): Boolean = text?.trim() == LINE

    /** True when two IPv4 addresses sit on the same /24. Neighboring Wi-Fi is not. */
    fun sameSubnet(left: String?, right: String?): Boolean {
        val a = prefix(left) ?: return false
        val b = prefix(right) ?: return false
        return a == b
    }

    private fun prefix(ip: String?): String? {
        val text = ip?.trim().orEmpty()
        val parts = text.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return null
        return parts.take(3).joinToString(".")
    }
}
