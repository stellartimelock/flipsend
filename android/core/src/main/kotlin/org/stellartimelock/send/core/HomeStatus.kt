package org.stellartimelock.send.core

/**
 * Home-screen pairing line. The user should see Connected, Not connected,
 * or Disconnected without digging through Settings.
 */
object HomeStatus {
    const val CONNECTED = "Connected"
    const val LINKED = "Linked"
    const val NOT_CONNECTED = "Not connected"
    const val DISCONNECTED = "Disconnected"
    const val WAITING = "Waiting to pair"
    const val NO_PEER = "No computer paired yet"
    const val KEYS_DONT_MATCH = "Paired, keys don't match, scan code to fix"
    const val KEYS_DONT_MATCH_SHORT = "Keys don't match"

    fun headline(
        paired: Boolean,
        listening: Boolean,
        pairingWindowOpen: Boolean,
        lastError: String?
    ): String {
        if (TrustNewKey.blocksConnected(lastError)) return KEYS_DONT_MATCH
        if (isDrop(lastError)) return DISCONNECTED
        if (paired && listening) return CONNECTED
        if (pairingWindowOpen) return WAITING
        return NOT_CONNECTED
    }

    fun header(
        paired: Boolean,
        listening: Boolean,
        pairingWindowOpen: Boolean,
        lastError: String?
    ): String {
        val line = headline(paired, listening, pairingWindowOpen, lastError)
        return when (line) {
            CONNECTED -> LINKED
            KEYS_DONT_MATCH -> KEYS_DONT_MATCH_SHORT
            else -> line
        }
    }

    fun who(paired: Boolean, pairedName: String?, lastError: String? = null): String {
        if (TrustNewKey.blocksConnected(lastError)) return KEYS_DONT_MATCH
        val name = pairedName?.trim().orEmpty()
        if (paired && name.isNotEmpty()) return "Paired with $name"
        if (paired) return "Paired"
        return NO_PEER
    }

    fun isDrop(lastError: String?): Boolean {
        val text = lastError?.trim().orEmpty()
        if (text.isEmpty()) return false
        if (PairStatus.isWakeText(text) || NetworkHop.isSwitch(text)) return false
        return text.contains("disconnect", ignoreCase = true) ||
            text.contains("hung up", ignoreCase = true) ||
            text == RepairPrompt.MESSAGE ||
            text.startsWith(ConnectionDrop.WIFI_LISTEN) ||
            text.startsWith(ConnectionDrop.INCOMING) ||
            text.startsWith(ConnectionDrop.RELAY)
    }
}
