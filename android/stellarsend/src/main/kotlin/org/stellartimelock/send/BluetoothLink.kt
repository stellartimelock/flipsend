package org.stellartimelock.send

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import org.stellartimelock.send.core.BluetoothService
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class BluetoothSnapshot(val present: Boolean, val permitted: Boolean, val radioOn: Boolean) {
    val canListen: Boolean get() = present && permitted && radioOn
}

/**
 * Classic Bluetooth RFCOMM. The socket is bridged to a local TCP socket so the
 * existing mutual-TLS handshake can run unchanged. Trust stays the paired
 * certificate, not a separate Bluetooth key.
 */
object BluetoothLink {
    private const val PREFS = "flipsend_bluetooth"
    private const val PEER = "peer"

    fun snapshot(context: Context): BluetoothSnapshot {
        val present = adapter(context) != null
        val permitted = permitted(context)
        val radioOn = if (!present || !permitted) false else runCatching { adapter(context)?.isEnabled == true }.getOrDefault(false)
        return BluetoothSnapshot(present, permitted, radioOn)
    }

    fun permitted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    fun canDial(context: Context): Boolean = snapshot(context).canListen

    fun remember(context: Context, address: String?) {
        if (!BluetoothService.isMac(address)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PEER, address!!.uppercase())
            .apply()
    }

    fun remembered(context: Context): String? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PEER, null)
        return saved?.takeIf { BluetoothService.isMac(it) }
    }

    @SuppressLint("MissingPermission")
    fun listen(context: Context): BluetoothServerSocket? {
        val radio = adapter(context) ?: return null
        if (!radio.isEnabled) return null
        return runCatching {
            radio.listenUsingInsecureRfcommWithServiceRecord(BluetoothService.NAME, uuid())
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    fun dial(context: Context, budgetMs: Int = BluetoothService.ATTEMPT_MS): BluetoothSocket? {
        val radio = adapter(context) ?: return null
        if (!permitted(context) || !radio.isEnabled) return null
        runCatching { radio.cancelDiscovery() }
        val deadline = System.currentTimeMillis() + budgetMs
        val targets = LinkedHashSet<BluetoothDevice>()
        remembered(context)?.let { mac ->
            runCatching { radio.getRemoteDevice(mac) }.getOrNull()?.let { targets += it }
        }
        runCatching { radio.bondedDevices }.getOrNull()?.let { targets += it }
        targets.forEach { device ->
            val left = deadline - System.currentTimeMillis()
            if (left < 400) return null
            connect(device, left.toInt())?.let { return it }
        }
        if (System.currentTimeMillis() + 800 >= deadline) return null
        if (!canScan(context)) return null
        discover(context, radio, (deadline - System.currentTimeMillis()).toInt()).forEach { device ->
            val left = deadline - System.currentTimeMillis()
            if (left < 400) return null
            connect(device, left.toInt())?.let { return it }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, budgetMs: Int): BluetoothSocket? {
        val primary = runCatching { device.createInsecureRfcommSocketToServiceRecord(uuid()) }.getOrNull()
        if (primary != null && connectWithTimeout(primary, budgetMs)) return primary
        runCatching { primary?.close() }
        val fallback = runCatching {
            val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, BluetoothService.CHANNEL) as BluetoothSocket
        }.getOrNull() ?: return null
        if (connectWithTimeout(fallback, budgetMs.coerceAtMost(1500))) return fallback
        runCatching { fallback.close() }
        return null
    }

    private fun connectWithTimeout(socket: BluetoothSocket, budgetMs: Int): Boolean {
        val done = AtomicBoolean(false)
        val worker = Thread {
            try {
                socket.connect()
                done.set(true)
            } catch (_: Exception) {
            }
        }
        worker.isDaemon = true
        worker.name = "stellarsend-bt-dial"
        worker.start()
        worker.join(budgetMs.toLong().coerceAtLeast(1))
        if (done.get()) return true
        runCatching { socket.close() }
        worker.join(200)
        return false
    }

    @SuppressLint("MissingPermission")
    private fun discover(context: Context, radio: BluetoothAdapter, budgetMs: Int): List<BluetoothDevice> {
        val found = ArrayList<BluetoothDevice>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                val device = deviceExtra(intent) ?: return
                if (found.none { it.address == device.address }) found += device
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }.isSuccess
        if (!registered) return emptyList()
        val started = runCatching { radio.startDiscovery() }.getOrDefault(false)
        if (started) {
            try {
                Thread.sleep(budgetMs.coerceIn(400, 2500).toLong())
            } catch (_: InterruptedException) {
            }
        }
        runCatching { radio.cancelDiscovery() }
        runCatching { context.unregisterReceiver(receiver) }
        return found
    }

    private fun deviceExtra(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun canScan(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    private fun adapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    private fun uuid(): UUID = UUID.fromString(BluetoothService.UUID_TEXT)
}

internal object BluetoothBridge {
    fun adopt(bluetooth: BluetoothSocket): Socket {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val accepted = CountDownLatch(1)
        val failed = AtomicReference<Exception?>(null)
        Thread({
            val local = try {
                server.accept()
            } catch (ex: Exception) {
                failed.set(ex)
                accepted.countDown()
                return@Thread
            }
            runCatching { server.close() }
            local.tcpNoDelay = true
            accepted.countDown()
            val stop = AtomicBoolean(false)
            val inbound = Thread { pump(bluetooth.inputStream, local.getOutputStream(), stop) }
            val outbound = Thread { pump(local.getInputStream(), bluetooth.outputStream, stop) }
            inbound.isDaemon = true
            outbound.isDaemon = true
            inbound.name = "stellarsend-bt-in"
            outbound.name = "stellarsend-bt-out"
            inbound.start()
            outbound.start()
            runCatching { inbound.join() }
            runCatching { outbound.join() }
            runCatching { bluetooth.close() }
            runCatching { local.close() }
        }, "stellarsend-bt-bridge").apply { isDaemon = true; start() }
        val client = Socket()
        try {
            client.tcpNoDelay = true
            client.connect(InetSocketAddress("127.0.0.1", server.localPort), 3000)
            if (!accepted.await(3, TimeUnit.SECONDS) || failed.get() != null) {
                throw IOException("Bluetooth bridge did not open.")
            }
            return client
        } catch (ex: Exception) {
            runCatching { client.close() }
            runCatching { bluetooth.close() }
            runCatching { server.close() }
            throw if (ex is IOException) ex else IOException("Bluetooth bridge did not open.", ex)
        }
    }

    private fun pump(input: InputStream, output: OutputStream, stop: AtomicBoolean) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (!stop.get()) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            stop.set(true)
            runCatching { output.close() }
        }
    }
}
