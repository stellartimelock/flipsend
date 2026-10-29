package org.stellartimelock.send

import android.content.Context
import android.net.Uri
import org.stellartimelock.send.core.PayloadKind
import org.stellartimelock.send.core.ReceiveSnap
import org.stellartimelock.send.core.TransferUpdate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class SendRequest(
    val kind: PayloadKind,
    val name: String,
    val text: String? = null,
    val uri: Uri? = null,
    val mimeType: String = if (kind == PayloadKind.TEXT) "text/plain" else "application/octet-stream"
)

data class SessionSnapshot(
    val deviceName: String,
    val pairedName: String?,
    val paired: Boolean,
    val listening: Boolean,
    val pairingWindowOpen: Boolean,
    val lastError: String?,
    val manualHost: String?,
    val pairedAtMillis: Long = 0,
    val inboxLabel: String = "",
    val pathLabel: String = "",
    val vpnLine: String? = null
)

data class PairedDevice(
    val name: String,
    val pairedOn: String
)

data class DeviceCapabilities(val installedPackages: Set<String>, val smsAvailable: Boolean)

data class UsbStage(
    val adbPath: String,
    val message: String
)

sealed class SessionEvent {
    data object Changed : SessionEvent()
    data class Progress(val update: TransferUpdate) : SessionEvent()
    data class Consent(val id: String, val title: String, val body: String) : SessionEvent()
    data class Notice(val text: String) : SessionEvent()
    data class ApkReady(val path: String, val name: String) : SessionEvent()
    data class Receive(val snap: ReceiveSnap) : SessionEvent()
    data object ReceivedChanged : SessionEvent()
    data class Upgrade(val message: String) : SessionEvent()
    data object RemoteChanged : SessionEvent()
}

object SessionHub {
    private val listeners = CopyOnWriteArrayList<(SessionEvent) -> Unit>()

    fun add(listener: (SessionEvent) -> Unit) {
        listeners += listener
    }

    fun remove(listener: (SessionEvent) -> Unit) {
        listeners -= listener
    }

    internal fun emit(event: SessionEvent) {
        for (listener in listeners) {
            runCatching { listener(event) }
        }
    }

    fun listenerCount(): Int = listeners.size
}

internal object ConsentBus {
    private val pending = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

    fun await(id: String, timeoutMs: Long): Boolean {
        val future = CompletableFuture<Boolean>()
        pending[id] = future
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            false
        } finally {
            pending.remove(id)
        }
    }

    fun answer(id: String, accepted: Boolean) {
        pending[id]?.complete(accepted)
    }
}

class StellarSendClient(context: Context) {
    private val app = context.applicationContext
    private val engine = Runtime.engine(app)

    fun snapshot(): SessionSnapshot = engine.snapshot()

    fun showPairingCode(): String = engine.openPairingWindow()

    fun pairingQrText(): String = engine.pairingQrText()

    fun pairFromCode(qr: String) = engine.pairFromQr(qr)

    fun pairFromShortCode(code: String) = engine.pairFromShortCode(code)

    fun ownShortCode(): String = engine.ownShortCode()

    fun pairedDevices(): List<PairedDevice> = engine.pairedDevices()

    fun inboxLabel(): String = engine.inboxLabel()

    fun setInboxTree(uri: String?, confirmed: Boolean = false) = engine.setInboxTree(uri, confirmed)

    fun sendClipboard(text: String) = engine.sendWifi(
        SendRequest(org.stellartimelock.send.core.PayloadKind.TEXT, "clipboard.txt", text = text),
        cancelled = { false }
    )

    fun unpair() = engine.unpair()

    fun rotate() = engine.rotate()

    fun pendingTrust() = engine.pendingTrust()

    fun trustNewKey() = engine.trustNewKey()

    fun confirmTrustedPin() = engine.confirmTrustedPin()

    fun setManualHost(host: String?, confirmed: Boolean = false) = engine.setManualHost(host, confirmed)

    fun setDeviceName(name: String, confirmed: Boolean = false) = engine.setDeviceName(name, confirmed)

    fun saveBackups() = engine.saveBackups()

    fun restoreSave(index: Int, confirmed: Boolean = false) = engine.restoreSave(index, confirmed)

    fun methods(request: SendRequest) = engine.methods(request)

    fun capabilities() = engine.capabilities()

    fun sendWifi(request: SendRequest, cancelled: () -> Boolean = { false }): org.stellartimelock.send.core.TransferOutcome =
        engine.sendWifi(request, cancelled)

    fun pushPasswordToPc(password: String?, clearAfterSeconds: Int? = null): org.stellartimelock.send.core.ServiceResult =
        engine.pushPassword(password, clearAfterSeconds)

    fun openBrowserOnPc(url: String?): org.stellartimelock.send.core.ServiceResult = engine.openBrowser(url)

    fun browserStatus(): org.stellartimelock.send.core.ServiceResult = engine.browserStatus()

    fun runTerminal(command: String, confirmed: Boolean, onOutput: (String, String) -> Unit): org.stellartimelock.send.core.ServiceResult =
        engine.runTerminal(command, confirmed, onOutput)

    fun shareScreen(cancelled: () -> Boolean): org.stellartimelock.send.core.ServiceResult = engine.shareScreen(cancelled)

    fun stopShare() = engine.stopShare()

    fun remoteActive(): Boolean = engine.remoteActive()

    fun remoteSessionId(): String? = engine.remoteSessionId()

    fun requestPhoneControl(): org.stellartimelock.send.core.ServiceResult = engine.requestRemote(org.stellartimelock.send.core.RemoteControl.FROM_PHONE)

    fun watchPc(cancelled: () -> Boolean, onFrame: (ByteArray) -> Unit): org.stellartimelock.send.core.ServiceResult =
        engine.watchRemote(cancelled, onFrame)

    fun openDesktop(
        cancelled: () -> Boolean,
        onReady: (String) -> Unit,
        onFrame: (ByteArray) -> Unit
    ): org.stellartimelock.send.core.ServiceResult = engine.openDesktop(cancelled, onReady, onFrame)

    fun enqueueRemoteInput(event: org.stellartimelock.send.core.RemoteInput) = engine.enqueueRemoteInput(event)

    fun stopRemote() = engine.stopRemote()

    fun stageUsb(request: SendRequest): UsbStage = engine.stageUsb(request)

    fun memo(text: String) = engine.memo(text)

    fun intentFor(methodId: String, request: SendRequest) = engine.intentFor(methodId, request)

    fun answerConsent(id: String, accepted: Boolean) = ConsentBus.answer(id, accepted)

    fun start() = engine.start()

    fun stop() = engine.stop()

    fun refreshRelay() = engine.refreshRelay()

    fun noteNetworkChange() = engine.noteNetworkChange()

    fun connectionInput(): org.stellartimelock.send.core.ConnectionInput = engine.connectionInput()

    fun useRoute(kind: String) = engine.useRoute(kind)

    fun probeRelayUsage() = engine.probeRelayUsage()

    fun qrBitmap(text: String, size: Int) = QrBitmaps.encode(text, size)

    /**
     * Reports install outcomes to the paired PC. Best-effort; silently ignored when the PC is
     * not reachable. The PC logs each result separately from "Transfer Succeeded".
     */
    fun callInstallResult(body: org.json.JSONObject) =
        engine.callInstallResult(body)
}

internal object Runtime {
    private val gate = Any()
    private var engine: Engine? = null

    fun engine(context: Context): Engine = synchronized(gate) {
        engine ?: Engine(context.applicationContext).also { engine = it }
    }

    fun peek(): Engine? = synchronized(gate) { engine }
}

/** VPN status for the home meter without creating the pairing Engine. */
object VpnMeter {
    fun line(context: Context): String? {
        val vpn = NetInfo.vpn(context.applicationContext)
        val usingRelay = Runtime.peek()?.snapshot()?.pathLabel == org.stellartimelock.send.core.Reachability.RELAY
        return org.stellartimelock.send.core.VpnStatus.line(vpn.on, vpn.bypassable, usingRelay)
    }
}
