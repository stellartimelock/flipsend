package org.stellartimelock.send.core

/**
 * Repeat pairing with the same phone keeps the stored pin. Hosts and
 * the display name can update. A different phone still replaces trust.
 * Re-pair must not write peer.bin until both sides confirm.
 */
object PeerTrust {
    data class Snapshot(
        val deviceId: String,
        val name: String,
        val fingerprint: String,
        val publicKey: String,
        val host: String?,
        val port: Int,
        val pairedAtMillis: Long,
        val hosts: List<String> = emptyList()
    )

    fun sameDevice(existingId: String?, incomingId: String): Boolean {
        if (existingId.isNullOrBlank() || incomingId.isBlank()) return false
        return existingId.equals(incomingId, ignoreCase = true)
    }

    fun merge(existing: Snapshot?, incoming: Snapshot): Snapshot {
        if (existing == null || !sameDevice(existing.deviceId, incoming.deviceId)) {
            return incoming
        }
        val hosts = (existing.hosts + incoming.hosts + listOfNotNull(existing.host, incoming.host))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return existing.copy(
            name = incoming.name.ifBlank { existing.name },
            host = incoming.host?.ifBlank { null } ?: existing.host,
            port = if (incoming.port > 0) incoming.port else existing.port,
            pairedAtMillis = if (existing.pairedAtMillis > 0) existing.pairedAtMillis else incoming.pairedAtMillis,
            hosts = hosts
        )
    }

    /**
     * A fresh Scan a code / Type a code writes both pins. Repeat pairing
     * through [merge] still keeps the stored pin.
     */
    fun scan(existing: Snapshot?, incoming: Snapshot): Snapshot {
        val merged = merge(existing, incoming)
        return merged.copy(
            fingerprint = incoming.fingerprint.trim().lowercase(),
            publicKey = incoming.publicKey.ifBlank { merged.publicKey },
            pairedAtMillis = if (incoming.pairedAtMillis > 0) incoming.pairedAtMillis else merged.pairedAtMillis
        )
    }

    /** One-tap trust of a new pin for the already-paired computer. */
    fun repin(existing: Snapshot, fingerprint: String, publicKey: String = existing.publicKey): Snapshot {
        require(fingerprint.isNotBlank()) { "The new pairing pin is missing." }
        return existing.copy(
            fingerprint = fingerprint.trim().lowercase(),
            publicKey = publicKey.ifBlank { existing.publicKey }
        )
    }

    /** Update the last host without touching the trusted pin. */
    fun withHost(existing: Snapshot, host: String?, port: Int): Snapshot = existing.copy(
        host = host?.trim()?.ifBlank { null } ?: existing.host,
        port = if (port > 0) port else existing.port
    )

    fun shouldWrite(existing: Snapshot?, merged: Snapshot): Boolean {
        if (existing == null) return true
        if (!sameDevice(existing.deviceId, merged.deviceId)) return true
        return existing.name != merged.name ||
            existing.host != merged.host ||
            existing.port != merged.port ||
            existing.hosts.toSet() != merged.hosts.toSet() ||
            existing.fingerprint != merged.fingerprint ||
            existing.publicKey != merged.publicKey
    }
}
