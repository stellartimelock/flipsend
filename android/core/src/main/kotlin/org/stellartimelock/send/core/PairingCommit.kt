package org.stellartimelock.send.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Replaces identity.bin and peer.bin together. Both payloads are flushed to temp
 * files and renamed. If the peer file cannot be replaced, the previous identity
 * bytes are written back.
 */
object PairingCommit {
    fun commit(identity: File, identityBytes: ByteArray, peer: File, peerBytes: ByteArray) {
        val previousIdentity = identity.takeIf { it.isFile }?.readBytes()
        val previousPeer = peer.takeIf { it.isFile }?.readBytes()
        val identityTemp = temp(identity)
        val peerTemp = temp(peer)
        try {
            writeTemp(identityTemp, identityBytes)
            writeTemp(peerTemp, peerBytes)
            replace(identityTemp, identity)
            try {
                replace(peerTemp, peer)
            } catch (ex: Exception) {
                restore(identity, previousIdentity)
                restore(peer, previousPeer)
                throw ex
            }
        } finally {
            identityTemp.delete()
            peerTemp.delete()
        }
    }

    private fun temp(file: File) = File(file.parentFile, file.name + ".pairing.tmp")

    private fun writeTemp(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    private fun replace(temp: File, target: File) {
        if (target.isDirectory) throw IOException("Could not replace ${target.name}.")
        val parent = target.parentFile ?: throw IOException("Could not replace ${target.name}.")
        if (!target.exists()) {
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            if (!target.isFile || target.length() <= 0) {
                throw IOException("Could not replace ${target.name}.")
            }
            return
        }
        val holding = File(parent, target.name + ".pairing.old")
        if (holding.exists()) holding.delete()
        if (!target.renameTo(holding)) {
            throw IOException("Could not replace ${target.name}.")
        }
        if (!temp.renameTo(target)) {
            holding.renameTo(target)
            throw IOException("Could not replace ${target.name}.")
        }
        holding.delete()
    }

    private fun restore(target: File, previous: ByteArray?) {
        if (previous == null) {
            target.delete()
            return
        }
        val temp = temp(target)
        writeTemp(temp, previous)
        try {
            replace(temp, target)
        } finally {
            temp.delete()
        }
    }
}
