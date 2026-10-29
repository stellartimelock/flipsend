package org.stellartimelock.send.core

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.UUID

data class MdnsAnnouncement(val deviceId: UUID, val port: Int, val address: String)

object MdnsPackets {
    const val SERVICE = "_stellarsend._tcp.local"

    fun buildQuery(): ByteArray {
        val name = encodeName(SERVICE)
        val packet = ByteArray(12 + name.size + 4)
        ByteBuffer.wrap(packet, 4, 2).putShort(1)
        name.copyInto(packet, 12)
        val question = 12 + name.size
        ByteBuffer.wrap(packet, question, 2).putShort(12)
        ByteBuffer.wrap(packet, question + 2, 2).putShort(1)
        return packet
    }

    fun buildAnnouncement(deviceId: UUID, port: Int, address: InetAddress): ByteArray {
        if (address !is Inet4Address) throw IllegalArgumentException("mDNS announcement needs an IPv4 address.")
        val instanceLabel = "d" + deviceId.toString().replace("-", "")
        val instance = "$instanceLabel.$SERVICE"
        val host = "$instanceLabel.local"
        val service = encodeName(SERVICE)
        val instanceName = encodeName(instance)
        val hostName = encodeName(host)
        val txt = ("id=$deviceId").toByteArray(Charsets.US_ASCII)
        if (txt.size > 255) throw ProtocolException("TXT record is too long.")
        val body = ByteArrayOutputStream()
        writeRecord(body, service, 12, 120, encodeName(instance))
        val srv = ByteArray(6 + hostName.size)
        ByteBuffer.wrap(srv, 4, 2).putShort(port.toShort())
        hostName.copyInto(srv, 6)
        writeRecord(body, instanceName, 33, 120, srv)
        val txtRdata = ByteArray(txt.size + 1)
        txtRdata[0] = txt.size.toByte()
        txt.copyInto(txtRdata, 1)
        writeRecord(body, instanceName, 16, 120, txtRdata)
        writeRecord(body, hostName, 1, 120, address.address)
        val records = body.toByteArray()
        val packet = ByteArray(12 + records.size)
        ByteBuffer.wrap(packet, 2, 2).putShort(0x8400.toShort())
        ByteBuffer.wrap(packet, 6, 2).putShort(1)
        ByteBuffer.wrap(packet, 10, 2).putShort(3)
        records.copyInto(packet, 12)
        return packet
    }

    fun isQueryForUs(packet: ByteArray): Boolean {
        val needle = encodeName(SERVICE)
        return indexOf(packet, needle) >= 0 && packet.size >= 12 && (packet[2].toInt() and 0x80) == 0
    }

    fun tryParseAnnouncement(packet: ByteArray): MdnsAnnouncement? {
        if (packet.size < 12) return null
        return try {
            var offset = 12
            val questions = u16(packet, 4)
            val answers = u16(packet, 6)
            val authorities = u16(packet, 8)
            val additional = u16(packet, 10)
            repeat(questions) {
                offset = skipName(packet, offset)
                offset += 4
            }
            var id: UUID? = null
            var port: Int? = null
            var address: String? = null
            repeat(answers + authorities + additional) {
                offset = skipName(packet, offset)
                val type = u16(packet, offset)
                offset += 8
                val length = u16(packet, offset)
                offset += 2
                val data = packet.copyOfRange(offset, offset + length)
                offset += length
                if (type == 16 && data.size > 1) {
                    val txtLength = data[0].toInt() and 0xFF
                    if (txtLength == data.size - 1) {
                        val text = data.copyOfRange(1, data.size).toString(Charsets.US_ASCII)
                        if (text.startsWith("id=")) {
                            id = runCatching { UUID.fromString(text.substring(3)) }.getOrNull()
                        }
                    }
                } else if (type == 33 && data.size >= 6) {
                    port = u16(data, 4)
                } else if (type == 1 && data.size == 4) {
                    address = data.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
            }
            val foundId = id ?: return null
            val foundPort = port ?: return null
            val foundAddress = address ?: return null
            MdnsAnnouncement(foundId, foundPort, foundAddress)
        } catch (_: Exception) {
            null
        }
    }

    fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) throw ProtocolException("Invalid DNS label.")
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun writeRecord(stream: ByteArrayOutputStream, name: ByteArray, type: Int, ttl: Int, data: ByteArray) {
        stream.write(name)
        val meta = ByteArray(10)
        val buffer = ByteBuffer.wrap(meta)
        buffer.putShort(type.toShort())
        buffer.putShort(1)
        buffer.putInt(ttl)
        buffer.putShort(data.size.toShort())
        stream.write(meta)
        stream.write(data)
    }

    private fun u16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

    private fun skipName(packet: ByteArray, start: Int): Int {
        var hops = 0
        var end = -1
        var index = start
        while (index < packet.size && hops < 16) {
            val length = packet[index].toInt() and 0xFF
            if (length == 0) {
                index++
                break
            }
            if ((length and 0xC0) == 0xC0) {
                if (index + 1 >= packet.size) throw ProtocolException("Bad DNS pointer.")
                if (end < 0) end = index + 2
                index = ((length and 0x3F) shl 8) or (packet[index + 1].toInt() and 0xFF)
                hops++
                continue
            }
            index += 1 + length
        }
        return if (end < 0) index else end
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > data.size) return -1
        for (i in 0..data.size - needle.size) {
            if (data.copyOfRange(i, i + needle.size).contentEquals(needle)) return i
        }
        return -1
    }
}
