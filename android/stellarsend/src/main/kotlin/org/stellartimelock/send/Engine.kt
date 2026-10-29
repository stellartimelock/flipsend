package org.stellartimelock.send

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import org.json.JSONObject
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import org.stellartimelock.send.core.BluetoothService
import org.stellartimelock.send.core.ConnectionDrop
import org.stellartimelock.send.core.ConnectionInput
import org.stellartimelock.send.core.ConnectionState
import org.stellartimelock.send.core.SendRoute
import org.stellartimelock.send.core.ApkAuto
import org.stellartimelock.send.core.ApkReplacePreview
import org.stellartimelock.send.core.BrowserUrls
import org.stellartimelock.send.core.OverwriteDecision
import org.stellartimelock.send.core.OverwriteKind
import org.stellartimelock.send.core.ClipboardPolicy
import org.stellartimelock.send.core.CompanionServices
import org.stellartimelock.send.core.CopyToPc
import org.stellartimelock.send.core.DeviceMaterial
import org.stellartimelock.send.core.DirectAttempt
import org.stellartimelock.send.core.DiscoveryPlanner
import org.stellartimelock.send.core.DiscoveryRequest
import org.stellartimelock.send.core.ErrorCodes
import org.stellartimelock.send.core.FileNames
import org.stellartimelock.send.core.FortressBotHandoff
import org.stellartimelock.send.core.FrameSlices
import org.stellartimelock.send.core.FolderArchive
import org.stellartimelock.send.core.LocalAddressPolicy
import org.stellartimelock.send.core.NetTransport
import org.stellartimelock.send.core.NetworkHop
import org.stellartimelock.send.core.NetworkPlace
import org.stellartimelock.send.core.Reachability
import org.stellartimelock.send.core.ReceivedHistory
import org.stellartimelock.send.core.RelayAnnouncement
import org.stellartimelock.send.core.RelayPlans
import org.stellartimelock.send.core.RelayQuotaException
import org.stellartimelock.send.core.RelayReply
import org.stellartimelock.send.core.RelayRoom
import org.stellartimelock.send.core.RelayWake
import org.stellartimelock.send.core.RelayWakeException
import org.stellartimelock.send.core.LanAnnounce
import org.stellartimelock.send.core.RelayRejoin
import org.stellartimelock.send.core.RelayPresence
import org.stellartimelock.send.core.TransferHold
import org.stellartimelock.send.core.LogRedaction
import org.stellartimelock.send.core.MEMO_NOT_IMPLEMENTED
import org.stellartimelock.send.core.MethodIds
import org.stellartimelock.send.core.PairingCodec
import org.stellartimelock.send.core.PairStatus
import org.stellartimelock.send.core.PayloadKind
import org.stellartimelock.send.core.PairingCommit
import org.stellartimelock.send.core.RemoteDesktop
import org.stellartimelock.send.core.RemoteDesktopGate
import org.stellartimelock.send.core.RemoteControl
import org.stellartimelock.send.core.RemotePhrases
import org.stellartimelock.send.core.RemoteInput
import org.stellartimelock.send.core.RemoteSession
import org.stellartimelock.send.core.ScreenShare
import org.stellartimelock.send.core.ProtocolException
import org.stellartimelock.send.core.ProtocolLimits
import org.stellartimelock.send.core.RepairPrompt
import org.stellartimelock.send.core.RetryPolicy
import org.stellartimelock.send.core.SendMethod
import org.stellartimelock.send.core.SendMethodRegistry
import org.stellartimelock.send.core.ServiceCodec
import org.stellartimelock.send.core.ServiceRequest
import org.stellartimelock.send.core.ServiceResult
import org.stellartimelock.send.core.StellarMemoStub
import org.stellartimelock.send.core.TerminalAuth
import org.stellartimelock.send.core.TerminalPolicy
import org.stellartimelock.send.core.KeyChangedException
import org.stellartimelock.send.core.TlsPins
import org.stellartimelock.send.core.TrustNewKey
import org.stellartimelock.send.core.TransferOutcome
import org.stellartimelock.send.core.TransferSession
import org.stellartimelock.send.core.TransferState
import org.stellartimelock.send.core.TransferUpdate
import org.stellartimelock.send.core.base64UrlEncode
import org.stellartimelock.send.core.memoForFile
import org.stellartimelock.send.core.sha256Hex
import org.stellartimelock.send.core.trimDeviceName
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

internal class Engine(private val context: Context) {
    private val userSave = UserSaveVault(context)
    private val identities = IdentityVault(context)
    private val peers = PeerVault(context)
    private val inbox = InboxFolder(context)
    private val receipts = ReceivedLog(context)
    private val memo = StellarMemoStub()
    private val gate = Any()
    @Volatile private var identity = identities.loadOrCreate(deviceName())
    @Volatile private var pairingUntil = 0L
    @Volatile private var repair: Repair? = null

    private data class Repair(val previous: DeviceMaterial, val until: Long)
    @Volatile private var pendingTrust: org.stellartimelock.send.core.TrustOffer? = null
    @Volatile private var lastError: String? = null
    @Volatile private var listening = false
    private var server: ServerSocket? = null
    private var listenThread: Thread? = null
    private var announceThread: Thread? = null
    private val stop = AtomicBoolean(false)
    @Volatile private var pathLabel = ""
    @Volatile private var directMissed = false
    @Volatile private var quotaAnnounced = false
    @Volatile private var relaySocket: Socket? = null
    private var relayThread: Thread? = null
    private val liveSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastPlace: NetworkPlace? = null
    private val hopGen = AtomicInteger()
    private val hopTicket = AtomicInteger()
    @Volatile private var preferRelay = false
    @Volatile private var preferBluetooth = false
    @Volatile private var bluetoothSession = false
    private var bluetoothThread: Thread? = null
    private var bluetoothServer: BluetoothServerSocket? = null
    @Volatile private var sharing = false
    private val remote = RemoteSession()
    private val remoteOut = java.util.concurrent.ConcurrentLinkedQueue<RemoteInput>()
    @Volatile private var remoteDrive = false
    @Volatile private var remoteWatch = false

    fun snapshot(): SessionSnapshot {
        val peer = peers.load()
        return SessionSnapshot(
            deviceName = deviceName(),
            pairedName = peer?.name,
            paired = peer != null,
            listening = listening,
            pairingWindowOpen = System.currentTimeMillis() <= pairingUntil && peer == null,
            lastError = PairStatus.detailError(peer != null, listening, lastError),
            manualHost = manualHost(),
            pairedAtMillis = peer?.pairedAtMillis ?: 0,
            inboxLabel = inboxLabel(),
            pathLabel = PairStatus.pathLabel(peer != null, listening, pathLabel),
            vpnLine = run {
                val vpn = NetInfo.vpn(context)
                org.stellartimelock.send.core.VpnStatus.line(
                    vpn.on,
                    vpn.bypassable,
                    pathLabel == Reachability.RELAY
                )
            }
        )
    }

    fun ownShortCode(): String = org.stellartimelock.send.core.ShortCode.format(identity.fingerprint)

    fun pairedDevices(): List<PairedDevice> {
        val peer = peers.load() ?: return emptyList()
        val whenPaired = if (peer.pairedAtMillis > 0) {
            "Paired " + org.stellartimelock.send.core.UsDate.format(peer.pairedAtMillis)
        } else {
            "Paired"
        }
        return listOf(PairedDevice(peer.name, whenPaired))
    }

    fun inboxLabel(): String = PhoneInbox.label()

    /** Same pairing advert the QR already uses. Does not open the pairing window. */
    fun pairingQrText(): String = pairingAdvertQr()

    fun openPairingWindow(): String {
        start()
        val until = System.currentTimeMillis() + 120_000
        val session = repair
        if (session != null) repair = session.copy(until = until)
        pairingUntil = until
        runCatching { relaySocket?.close() }
        val qr = pairingAdvertQr()
        note("Pairing window opened.")
        SessionHub.emit(SessionEvent.Changed)
        return qr
    }

    private fun pairingAdvertQr(): String {
        val hosts = NetInfo.candidates(context)
        return PairingCodec.toQr(
            org.stellartimelock.send.core.PairingAdvert(
                1,
                UUID.fromString(identity.deviceId),
                base64UrlEncode(identity.certificate.publicKey.encoded),
                identity.fingerprint,
                hosts.firstOrNull().orEmpty(),
                ProtocolLimits.TCP_PORT,
                trimDeviceName(deviceName()),
                hosts
            )
        )
    }

    fun pairFromQr(qr: String) {
        start()
        val advert = PairingCodec.parse(qr)
        val hosts = (advert.candidates() + listOfNotNull(manualHost())).distinct()
        val peer = StoredPeer(
            advert.deviceId.toString(),
            advert.name,
            advert.fingerprint,
            advert.publicKeySpkiBase64Url,
            hosts.firstOrNull(),
            advert.port,
            System.currentTimeMillis(),
            hosts
        )
        if (hosts.isEmpty() && relayUrl() == null) {
            commitScanned(peer)
            note("Pairing saved. The other device will be found on the local network.")
            SessionHub.emit(SessionEvent.Changed)
            return
        }
        val ssl = try {
            val direct = firstDirect(hosts, advert.port, advert.fingerprint)
            pathLabel = Reachability.DIRECT.also { directMissed = false }
            direct
        } catch (ex: Exception) {
            val url = relayUrl() ?: throw java.io.IOException(Reachability.PC, ex)
            pathLabel = Reachability.RELAY
            try {
                relaySocket(url, RelayRoom.pairing(advert.deviceId.toString()), advert.fingerprint)
            } catch (relay: RelayQuotaException) {
                noteQuota(relay.message)
                throw relay
            } catch (relay: RelayWakeException) {
                lastError = relay.message
                SessionHub.emit(SessionEvent.Changed)
                throw relay
            } catch (relay: Exception) {
                val quota = takeQuota()
                if (quota != null) {
                    noteQuota(quota)
                    throw RelayQuotaException(quota)
                }
                throw java.io.IOException(Reachability.PC, relay)
            }
        }
        ssl.use {
            TransferSession.handshake(
                it.outputStream,
                it.inputStream,
                identity,
                peer.fingerprint,
                peer.deviceId,
                sha256Hex((it.session.peerCertificates[0] as java.security.cert.X509Certificate).publicKey.encoded),
                allowNew = false,
                confirmPeer = null,
                announcement = planAnnouncement()
            )
        }
        commitScanned(peer)
        note("Paired.")
        lastError = null
        SessionHub.emit(SessionEvent.Changed)
    }

    fun pairFromShortCode(code: String) {
        start()
        if (!org.stellartimelock.send.core.ShortCode.looksLike(code)) {
            throw ProtocolException("Enter the 8 character code shown on the computer.")
        }
        val hit = LanFinder.findByCode(code, NetInfo.ipv4(context))
            ?: throw ProtocolException("Could not find a computer showing that code. Scan the QR code if broadcast is blocked.")
        connectPairing(hit.ip, hit.port) { ssl ->
            val remoteCert = ssl.session.peerCertificates.first() as java.security.cert.X509Certificate
            val remoteFp = sha256Hex(remoteCert.publicKey.encoded)
            if (!org.stellartimelock.send.core.ShortCode.matches(code, remoteFp)) {
                throw ProtocolException("That code does not match the computer that answered.")
            }
            val remote = TransferSession.handshake(
                ssl.outputStream,
                ssl.inputStream,
                identity,
                remoteFp,
                null,
                remoteFp,
                allowNew = false,
                confirmPeer = null,
                announcement = planAnnouncement()
            )
            commitScanned(
                StoredPeer(
                    remote.getString("deviceId"),
                    remote.optString("name", "Computer"),
                    remote.getString("fingerprint"),
                    base64UrlEncode(remoteCert.publicKey.encoded),
                    hit.ip,
                    hit.port,
                    System.currentTimeMillis()
                )
            )
        }
        note("Paired.")
        lastError = null
        SessionHub.emit(SessionEvent.Changed)
    }

    fun unpair() {
        rollbackRepair(silent = true)
        val before = peers.load()?.fingerprint
        note("Forget pin before=${org.stellartimelock.send.core.TrustNewKey.shortPin(before)} file=${peers.location.absolutePath}")
        peers.delete()
        pairingUntil = 0
        lastError = null
        pendingTrust = null
        acceptNextPin = false
        justTrusted = false
        lastSeenPin = ""
        lastSeenKey = ""
        val after = peers.reload()
        note("Forget pin after=${org.stellartimelock.send.core.TrustNewKey.shortPin(after?.fingerprint)} gone=${!peers.location.exists()}")
        note("Device unpaired.")
        SessionHub.emit(SessionEvent.Changed)
    }

    fun rotate() {
        rollbackRepair(silent = true)
        val until = System.currentTimeMillis() + 120_000
        repair = Repair(identity, until)
        identity = org.stellartimelock.send.core.Identities.generate(name = deviceName())
        pairingUntil = until
        lastError = null
        note("Re-scan / re-pair started. This phone stays paired until the computer confirms.")
        SessionHub.emit(SessionEvent.Changed)
    }

    fun pendingTrust(): org.stellartimelock.send.core.TrustOffer? {
        val offer = pendingTrust ?: return null
        val live = peers.load()
        if (live == null || !TrustNewKey.offerStillValid(offer, live.fingerprint, identity.fingerprint)) {
            dropStaleTrust(offer, live)
            return null
        }
        return offer
    }

    private fun dropStaleTrust(offer: org.stellartimelock.send.core.TrustOffer, live: StoredPeer?) {
        pendingTrust = null
        if (lastError == TrustNewKey.message(offer.name)) lastError = null
        note(
            "Trust offer dropped. offer=${TrustNewKey.shortPin(offer.fingerprint)} " +
                "stored=${TrustNewKey.shortPin(live?.fingerprint)}"
        )
        SessionHub.emit(SessionEvent.Changed)
    }

    @Volatile private var acceptNextPin = false
    @Volatile private var justTrusted = false
    @Volatile private var lastSeenPin = ""
    @Volatile private var lastSeenKey = ""

    fun trustNewKey(): Boolean {
        val existing = peers.load() ?: return false
        val offer = pendingTrust
        val fingerprint = offer?.fingerprint?.takeIf { it.isNotBlank() } ?: lastSeenPin
        val publicKey = offer?.publicKey?.takeIf { it.isNotBlank() } ?: lastSeenKey
        note(
            "Trust pin before=${org.stellartimelock.send.core.TrustNewKey.shortPin(existing.fingerprint)} " +
                "offer=${org.stellartimelock.send.core.TrustNewKey.shortPin(fingerprint)} " +
                "file=${peers.location.absolutePath}"
        )
        if (fingerprint.isNotBlank() && fingerprint.equals(existing.fingerprint, ignoreCase = true)) {
            pendingTrust = null
            lastError = null
            acceptNextPin = false
            note("Trust pin ${TrustNewKey.shortPin(existing.fingerprint)} already saved. Nothing to change.")
            SessionHub.emit(SessionEvent.Changed)
            return true
        }
        if (fingerprint.isNotBlank() && fingerprint.equals(identity.fingerprint, ignoreCase = true)) {
            pendingTrust = null
            lastError = null
            note("Trust refused. The offered pin is this phone's own key.")
            SessionHub.emit(SessionEvent.Changed)
            return false
        }
        if (fingerprint.isNotBlank()) {
            val next = peers.trustNewKey(fingerprint, publicKey.ifBlank { existing.publicKey }) ?: return false
            val reloaded = peers.reload()
            note(
                "Trust pin after=${org.stellartimelock.send.core.TrustNewKey.shortPin(reloaded?.fingerprint)} " +
                    "mtime=${peers.location.lastModified()}"
            )
            if (reloaded == null || !reloaded.fingerprint.equals(fingerprint, ignoreCase = true)) {
                note("Trust pin did not reload from ${peers.location.absolutePath}.")
                acceptNextPin = true
                peers.markAcceptNext()
            } else {
                acceptNextPin = false
            }
            pendingTrust = null
            lastError = null
            lastSeenPin = fingerprint
            lastSeenKey = publicKey
            justTrusted = true
            note("Trusted the new key for ${next.name}.")
            SessionHub.emit(SessionEvent.Changed)
            return true
        }
        acceptNextPin = true
        justTrusted = true
        peers.markAcceptNext()
        pendingTrust = null
        lastError = null
        note(
            "Trusted the next key for ${existing.name}. " +
                "acceptNext saved. pin still ${org.stellartimelock.send.core.TrustNewKey.shortPin(existing.fingerprint)}"
        )
        SessionHub.emit(SessionEvent.Changed)
        return true
    }

    fun confirmTrustedPin(): Boolean {
        val peer = peers.load() ?: return false
        val host = peer.host?.takeIf { it.isNotBlank() } ?: return false
        return try {
            connect(host, peer.port, peer.fingerprint) { }
            lastError = null
            justTrusted = false
            acceptNextPin = false
            note("Handshake succeeded with the new pin ${org.stellartimelock.send.core.TrustNewKey.shortPin(peer.fingerprint)}.")
            SessionHub.emit(SessionEvent.Changed)
            true
        } catch (ex: Exception) {
            if (trustAlreadySaved(ex, peer)) {
                note(
                    "Trust pin ${org.stellartimelock.send.core.TrustNewKey.shortPin(peer.fingerprint)} is on disk. " +
                        "The other side has not written this phone's pin yet. ${ex.javaClass.simpleName}: ${ex.message}"
                )
                return false
            }
            noteKeyChange(ex, peer)
            false
        }
    }

    fun setManualHost(host: String?, confirmed: Boolean = false): OverwriteDecision {
        val decision = userSave.setManualHost(host, confirmed)
        if (decision.kind == OverwriteKind.ALLOW) {
            SessionHub.emit(SessionEvent.Changed)
        }
        return decision
    }

    fun setDeviceName(name: String, confirmed: Boolean = false): OverwriteDecision {
        val decision = userSave.setDeviceName(name, confirmed)
        if (decision.kind == OverwriteKind.ALLOW) {
            SessionHub.emit(SessionEvent.Changed)
        }
        return decision
    }

    fun setInboxTree(uri: String?, confirmed: Boolean = false): OverwriteDecision {
        val decision = inbox.set(uri, confirmed)
        if (decision.kind == OverwriteKind.ALLOW) {
            SessionHub.emit(SessionEvent.Changed)
        }
        return decision
    }

    fun saveBackups() = userSave.backups()

    fun restoreSave(index: Int, confirmed: Boolean = false): OverwriteDecision {
        val decision = userSave.restore(index, confirmed)
        if (decision.kind == OverwriteKind.ALLOW) {
            SessionHub.emit(SessionEvent.Changed)
        }
        return decision
    }

    fun methods(request: SendRequest): List<SendMethod> {
        val caps = capabilities()
        return SendMethodRegistry.available(request.kind, caps.installedPackages, caps.smsAvailable)
    }

    fun capabilities(): DeviceCapabilities {
        val installed = mutableSetOf<String>()
        if (installed(SendMethodRegistry.GMAIL_PACKAGE)) installed += SendMethodRegistry.GMAIL_PACKAGE
        if (installed(SendMethodRegistry.TEXTPIPE_PACKAGE)) installed += SendMethodRegistry.TEXTPIPE_PACKAGE
        val sms = Telephony.Sms.getDefaultSmsPackage(context) != null
        return DeviceCapabilities(installed, sms)
    }

    fun memo(text: String) = memo.sendText(text).also {
        note(MEMO_NOT_IMPLEMENTED)
    }

    fun stageUsb(request: SendRequest): UsbStage {
        val directory = File(context.getExternalFilesDir(null), "stellar-outbox")
        directory.mkdirs()
        val file = when (request.kind) {
            PayloadKind.TEXT -> {
                val text = request.text ?: throw ProtocolException("Enter text first.")
                if (text.toByteArray(Charsets.UTF_8).size > ProtocolLimits.MAX_TEXT_BYTES) {
                    throw ProtocolException("Text is too long.", ErrorCodes.LIMIT)
                }
                val target = FileNames.allocate(directory, if (request.name.endsWith(".txt")) request.name else request.name + ".txt")
                target.writeText(text)
                target
            }
            PayloadKind.FILE -> {
                val source = request.uri ?: throw ProtocolException("Choose a file first.")
                val target = FileNames.allocate(directory, request.name)
                copyBounded(source, target, ProtocolLimits.MAX_FILE_BYTES)
                target
            }
        }
        val adb = adbPath(file)
        return UsbStage(adb, "Staged for USB. On the PC, confirm Pull phone outbox. adb is not included with FlipSend.")
    }

    fun pushPassword(password: String?, clearAfterSeconds: Int? = null): ServiceResult {
        val id = UUID.randomUUID().toString()
        val error = ClipboardPolicy.rejectReason(password)
        if (error != null) return ServiceResult.fail(id, ErrorCodes.BAD_REQUEST, error)
        val seconds = CopyToPc.clampClearAfter(clearAfterSeconds)
        return callCompanion(
            CompanionServices.VAULT_CLIPBOARD,
            "put",
            JSONObject().put("password", password).put("clearAfterSeconds", seconds)
        )
    }

    /**
     * Reports install outcomes to the paired PC. Best-effort; silently ignored when the PC is
     * unreachable. The PC logs "Install Succeeded", "Install Pending", or "Install Failed: <reason>"
     * as separate lines from "Transfer Succeeded".
     */
    fun callInstallResult(body: org.json.JSONObject): org.stellartimelock.send.core.ServiceResult =
        callCompanion(CompanionServices.INSTALL_RESULT, "report", body)

    fun openBrowser(url: String?): ServiceResult {
        val id = UUID.randomUUID().toString()
        val check = BrowserUrls.tryNormalize(url)
        if (!check.ok) return ServiceResult.fail(id, ErrorCodes.BAD_REQUEST, check.error)
        return callCompanion(CompanionServices.REMOTE_BROWSER, "open", JSONObject().put("url", check.absolute))
    }

    fun browserStatus(): ServiceResult =
        callCompanion(CompanionServices.REMOTE_BROWSER, "status", JSONObject())

    fun runTerminal(command: String, confirmed: Boolean, onOutput: (String, String) -> Unit): ServiceResult {
        val id = UUID.randomUUID().toString()
        val trimmed = command.trim()
        val invalid = TerminalPolicy.rejectReason(trimmed)
        if (invalid != null) return ServiceResult.fail(id, ErrorCodes.BAD_REQUEST, invalid)
        if (TerminalPolicy.isDestructive(trimmed) && !confirmed) {
            return ServiceResult.fail(id, TerminalPolicy.NEEDS_CONFIRM, "This command needs confirmation on the phone.")
        }
        val signature = TerminalAuth.sign(identity.privateKey, id, trimmed, confirmed)
        val body = JSONObject()
            .put("command", trimmed)
            .put("confirmed", confirmed)
            .put("sig", signature)
        return callCompanionStreaming(ServiceRequest(id, CompanionServices.TERMINAL, "run", body), onOutput)
    }

    fun shareScreen(cancelled: () -> Boolean): ServiceResult {
        val id = UUID.randomUUID().toString()
        val peer = peers.load() ?: return ServiceResult.fail(id, ErrorCodes.UNKNOWN_PEER, ScreenShare.NEED_PAIR)
        sharing = true
        try {
            return try {
                val hit = locate(peer)
                pathLabel = Reachability.DIRECT.also { directMissed = false }
                connect(hit.ip, hit.port, peer.fingerprint) { ssl ->
                    val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as java.security.cert.X509Certificate).publicKey.encoded)
                    TransferSession.shareScreen(
                        ssl.outputStream,
                        ssl.inputStream,
                        identity,
                        peer.deviceId,
                        peer.fingerprint,
                        remoteFp,
                        { ScreenShareHub.latest() },
                        { cancelled() || !sharing },
                        planAnnouncement()
                    )
                }
            } catch (ex: Exception) {
                if (ex is InterruptedException) throw ex
                unrecognizedResult(id, ex)?.let { return it }
                val url = relayUrl()
                if (url != null) {
                    try {
                        pathLabel = Reachability.RELAY
                        return relaySocket(url, RelayRoom.paired(identity.deviceId, peer.deviceId), peer.fingerprint).use { ssl ->
                            val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as java.security.cert.X509Certificate).publicKey.encoded)
                            TransferSession.shareScreen(
                                ssl.outputStream,
                                ssl.inputStream,
                                identity,
                                peer.deviceId,
                                peer.fingerprint,
                                remoteFp,
                                { ScreenShareHub.latest() },
                                { cancelled() || !sharing },
                                planAnnouncement()
                            )
                        }
                    } catch (relay: Exception) {
                        if (relay is InterruptedException) throw relay
                        unrecognizedResult(id, relay)?.let { return it }
                        if (relay is RelayWakeException) {
                            lastError = if (listening) null else relay.message
                            SessionHub.emit(SessionEvent.Changed)
                            return ServiceResult.fail(id, RelayPlans.WAKE, relay.message ?: RelayWake.GAVE_UP)
                        }
                        return ServiceResult.fail(id, ErrorCodes.IO, Reachability.PC)
                    }
                }
                ServiceResult.fail(id, ErrorCodes.IO, LogRedaction.redact(ex.message ?: Reachability.PC))
            }
        } finally {
            sharing = false
            SessionHub.emit(SessionEvent.Changed)
        }
    }

    fun stopShare() {
        sharing = false
    }

    fun refreshRemoteToggle() {
        val was = remote.enabled
        remote.enabled = RemoteControlPrefs.phoneControl(context)
        remote.paired = peers.load() != null
        if (was && !remote.enabled && remote.from == RemoteControl.FROM_PC &&
            remote.state != org.stellartimelock.send.core.RemoteSessionState.IDLE
        ) {
            stopRemote()
        }
    }

    fun remoteActive(): Boolean =
        remote.state == org.stellartimelock.send.core.RemoteSessionState.ACTIVE && remote.from == RemoteControl.FROM_PC

    fun remoteSessionId(): String? = remote.sessionId

    fun enqueueRemoteInput(event: RemoteInput) {
        if (remoteOut.size < 40) remoteOut.add(event)
    }

    fun stopRemote() {
        remoteDrive = false
        remoteWatch = false
        val id = remote.sessionId
        remote.stop(id)
        remoteOut.clear()
        Notifier.clearControlled(context)
        SessionHub.emit(SessionEvent.RemoteChanged)
        if (id != null && peers.load() != null) {
            Thread {
                runCatching {
                    callCompanion(
                        CompanionServices.REMOTE_CONTROL,
                        RemoteControl.OP_STOP,
                        JSONObject().put("session", id)
                    )
                }
            }.start()
        }
    }

    fun requestRemote(from: String): ServiceResult {
        if (!RemoteDesktopGate.allow(RemoteDesktopBuild.enabled(context), CompanionServices.REMOTE_CONTROL, RemoteControl.OP_REQUEST, from)) {
            return ServiceResult.fail(java.util.UUID.randomUUID().toString(), ErrorCodes.NOT_IMPLEMENTED, RemoteDesktopGate.UNAVAILABLE)
        }
        refreshRemoteToggle()
        desktopNote("request from this phone.")
        if (!remote.paired) {
            desktopNote("refused. ${RemoteControl.NEED_PAIR}")
            return ServiceResult.fail(java.util.UUID.randomUUID().toString(), ErrorCodes.UNKNOWN_PEER, RemoteControl.NEED_PAIR)
        }
        if (from == RemoteControl.FROM_PC && !remote.enabled) {
            desktopNote("refused. ${RemoteControl.NEED_TOGGLE}")
            return ServiceResult.fail(java.util.UUID.randomUUID().toString(), ErrorCodes.DECLINED, RemoteControl.NEED_TOGGLE)
        }
        remote.endWatch(remote.sessionId)
        val sessionId = java.util.UUID.randomUUID().toString()
        val result = callCompanion(
            CompanionServices.REMOTE_CONTROL,
            RemoteControl.OP_REQUEST,
            JSONObject().put("session", sessionId).put("from", from),
            mapIdentity = false
        )
        val plain = result.copy(message = RemoteDesktop.explain(result.code, result.message))
        if (plain.ok) {
            remote.request(sessionId, from)
            remote.allow(sessionId)
            desktopNote("allowed.")
            SessionHub.emit(SessionEvent.RemoteChanged)
        } else {
            desktopNote("refused. ${plain.message}")
        }
        return plain
    }

    fun watchRemote(cancelled: () -> Boolean, onFrame: (ByteArray) -> Unit): ServiceResult {
        if (!RemoteDesktopGate.allow(RemoteDesktopBuild.enabled(context), CompanionServices.REMOTE_CONTROL, RemoteControl.OP_WATCH, RemoteControl.FROM_PHONE)) {
            return ServiceResult.fail("", ErrorCodes.NOT_IMPLEMENTED, RemoteDesktopGate.UNAVAILABLE)
        }
        val sessionId = remote.sessionId ?: return ServiceResult.fail("", ErrorCodes.DECLINED, RemoteControl.NEED_ALLOW)
        val peer = peers.load() ?: return ServiceResult.fail(sessionId, ErrorCodes.UNKNOWN_PEER, RemoteControl.NEED_PAIR)
        remoteWatch = true
        remote.beginWatch(sessionId)
        desktopNote("watch starting.")
        var frames = 0
        var bytes = 0L
        val startedAt = System.currentTimeMillis()
        val wrapped: (ByteArray) -> Unit = { jpeg ->
            frames++
            bytes += jpeg.size
            if (frames == 1 || frames % 20 == 0) {
                val seconds = ((System.currentTimeMillis() - startedAt).coerceAtLeast(1L)) / 1000.0
                desktopNote("frames $frames, ${bytes} bytes, ${"%.1f".format(frames / seconds)} fps.")
            }
            onFrame(jpeg)
        }
        try {
            val result = try {
                val hit = locate(peer)
                pathLabel = Reachability.DIRECT.also { directMissed = false }
                desktopNote("on Wi-Fi.")
                connect(hit.ip, hit.port, peer.fingerprint) { ssl ->
                    val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as java.security.cert.X509Certificate).publicKey.encoded)
                    TransferSession.watchRemote(
                        ssl.outputStream,
                        ssl.inputStream,
                        identity,
                        peer.deviceId,
                        peer.fingerprint,
                        remoteFp,
                        sessionId,
                        { remoteOut.poll() },
                        wrapped,
                        { cancelled() || !remoteWatch },
                        planAnnouncement()
                    )
                }
            } catch (ex: Exception) {
                if (ex is InterruptedException) throw ex
                desktopNote("Wi-Fi closed. ${ex.javaClass.simpleName}: ${ex.message}")
                val url = relayUrl()
                if (url != null) {
                    try {
                        pathLabel = Reachability.RELAY
                        desktopNote("trying the relay.")
                        val ssl = relaySocket(url, RelayRoom.paired(identity.deviceId, peer.deviceId), peer.fingerprint)
                        val punch = if (preferRelay) null else DirectAttempt.start(RelaySeen.take(ssl), listOfNotNull(NetInfo.ipv4(context)))
                        try {
                            ssl.use {
                                val remoteFp = sha256Hex((it.session.peerCertificates[0] as java.security.cert.X509Certificate).publicKey.encoded)
                                TransferSession.watchRemote(
                                    it.outputStream,
                                    it.inputStream,
                                    identity,
                                    peer.deviceId,
                                    peer.fingerprint,
                                    remoteFp,
                                    sessionId,
                                    { remoteOut.poll() },
                                    wrapped,
                                    { cancelled() || !remoteWatch },
                                    planAnnouncement(),
                                    direct = punch,
                                    onDirect = { up ->
                                        directMissed = !up
                                        pathLabel = if (up) Reachability.PUNCHED else Reachability.RELAY
                                        note(if (up) desktopLine("on the direct link.") else desktopLine("stayed on the relay."))
                                        SessionHub.emit(SessionEvent.Changed)
                                    }
                                )
                            }
                        } finally {
                            runCatching { punch?.close() }
                        }
                    } catch (relay: Exception) {
                        if (relay is InterruptedException) throw relay
                        desktopNote("relay closed. ${relay.javaClass.simpleName}: ${relay.message}")
                        ServiceResult.fail(sessionId, ErrorCodes.IO, RemoteDesktop.explain(ErrorCodes.IO, relay.message ?: Reachability.PC))
                    }
                } else {
                    ServiceResult.fail(sessionId, ErrorCodes.IO, RemoteDesktop.explain(ErrorCodes.IO, ex.message ?: Reachability.PC))
                }
            }
            val plain = result.copy(message = RemoteDesktop.explain(result.code, result.message))
            desktopNote("watch ended. ${plain.message}")
            return plain
        } finally {
            remote.endWatch(sessionId)
            remoteWatch = false
            SessionHub.emit(SessionEvent.RemoteChanged)
        }
    }

    /**
     * Stays on the request connection and reads frames. A 1.5.35 computer that
     * closes after Allow falls back to the older watch connection.
     */
    fun openDesktop(cancelled: () -> Boolean, onReady: (String) -> Unit, onFrame: (ByteArray) -> Unit): ServiceResult {
        if (!RemoteDesktopGate.allow(RemoteDesktopBuild.enabled(context), CompanionServices.REMOTE_CONTROL, RemoteControl.OP_REQUEST, RemoteControl.FROM_PHONE)) {
            return ServiceResult.fail(java.util.UUID.randomUUID().toString(), ErrorCodes.NOT_IMPLEMENTED, RemoteDesktopGate.UNAVAILABLE)
        }
        refreshRemoteToggle()
        desktopNote("request from this phone.")
        if (!remote.paired) {
            desktopNote("refused. ${RemoteControl.NEED_PAIR}")
            return ServiceResult.fail(java.util.UUID.randomUUID().toString(), ErrorCodes.UNKNOWN_PEER, RemoteControl.NEED_PAIR)
        }
        val peer = peers.load() ?: return ServiceResult.fail(java.util.UUID.randomUUID().toString(), ErrorCodes.UNKNOWN_PEER, RemoteControl.NEED_PAIR)
        remote.endWatch(remote.sessionId)
        val sessionId = java.util.UUID.randomUUID().toString()
        val asked = remote.request(sessionId, RemoteControl.FROM_PHONE)
        if (!asked.ok) {
            desktopNote("refused. ${asked.message}")
            return ServiceResult.fail(sessionId, asked.code, asked.message)
        }
        var frames = 0
        var bytes = 0L
        val startedAt = System.currentTimeMillis()
        val wrapped: (ByteArray) -> Unit = { jpeg ->
            frames++
            bytes += jpeg.size
            if (frames == 1 || frames % 20 == 0) {
                val seconds = ((System.currentTimeMillis() - startedAt).coerceAtLeast(1L)) / 1000.0
                desktopNote("frames $frames, ${bytes} bytes, ${"%.1f".format(frames / seconds)} fps.")
            }
            onFrame(jpeg)
        }
        val opened = runRemoteLink(peer, cancelled) { ssl, punch, onDirect ->
            val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as java.security.cert.X509Certificate).publicKey.encoded)
            TransferSession.openRemote(
                ssl.outputStream,
                ssl.inputStream,
                identity,
                peer.deviceId,
                peer.fingerprint,
                remoteFp,
                sessionId,
                { remoteOut.poll() },
                {
                    remote.allow(sessionId)
                    desktopNote("allowed.")
                    SessionHub.emit(SessionEvent.RemoteChanged)
                    onReady(sessionId)
                },
                wrapped,
                { cancelled() },
                planAnnouncement(),
                direct = punch,
                onDirect = onDirect
            )
        }
        if (opened.code == RemoteDesktop.TRY_WATCH) {
            note("This computer closed the request. Opening the watch connection.")
            if (remote.sessionId != sessionId || remote.state != org.stellartimelock.send.core.RemoteSessionState.ACTIVE) {
                remote.request(sessionId, RemoteControl.FROM_PHONE)
                remote.allow(sessionId)
            }
            return watchRemote(cancelled, onFrame)
        }
        val plain = opened.copy(message = RemoteDesktop.explain(opened.code, opened.message))
        if (!plain.ok) {
            desktopNote("refused. ${plain.message}")
            remote.stop(sessionId)
        } else {
            desktopNote("session ended. ${plain.message}")
            remote.endWatch(sessionId)
        }
        SessionHub.emit(SessionEvent.RemoteChanged)
        return plain
    }

    private fun runRemoteLink(
        peer: StoredPeer,
        cancelled: () -> Boolean,
        block: (javax.net.ssl.SSLSocket, org.stellartimelock.send.core.DirectAttempt?, (Boolean) -> Unit) -> ServiceResult
    ): ServiceResult {
        return try {
            val hit = locate(peer)
            pathLabel = Reachability.DIRECT.also { directMissed = false }
            desktopNote("on Wi-Fi.")
            connect(hit.ip, hit.port, peer.fingerprint) { ssl ->
                block(ssl, null) { }
            }
        } catch (ex: Exception) {
            if (ex is InterruptedException) throw ex
            if (cancelled()) return ServiceResult.done("", RemoteControl.STOPPED)
            noteKeyChange(ex, peer)?.let { return it }
            desktopNote("Wi-Fi closed. ${ex.javaClass.simpleName}: ${ex.message}")
            val url = relayUrl() ?: return ServiceResult.fail("", ErrorCodes.IO, RemoteDesktop.explain(ErrorCodes.IO, ex.message ?: Reachability.PC))
            try {
                pathLabel = Reachability.RELAY
                desktopNote("trying the relay.")
                val ssl = relaySocket(url, RelayRoom.paired(identity.deviceId, peer.deviceId), peer.fingerprint)
                val punch = if (preferRelay) null else DirectAttempt.start(RelaySeen.take(ssl), listOfNotNull(NetInfo.ipv4(context)))
                try {
                    ssl.use {
                        block(it, punch) { up ->
                            directMissed = !up
                            pathLabel = if (up) Reachability.PUNCHED else Reachability.RELAY
                            note(if (up) desktopLine("on the direct link.") else desktopLine("stayed on the relay."))
                            SessionHub.emit(SessionEvent.Changed)
                        }
                    }
                } finally {
                    runCatching { punch?.close() }
                }
            } catch (relay: Exception) {
                if (relay is InterruptedException) throw relay
                noteKeyChange(relay, peer)?.let { return it }
                desktopNote("relay closed. ${relay.javaClass.simpleName}: ${relay.message}")
                ServiceResult.fail("", ErrorCodes.IO, RemoteDesktop.explain(ErrorCodes.IO, relay.message ?: Reachability.PC))
            }
        }
    }

    fun sendWifi(request: SendRequest, cancelled: () -> Boolean): TransferOutcome {
        repairOpen()
        val peer = peers.load() ?: return fail(ErrorCodes.UNKNOWN_PEER, "Pair a device before sending.")
        TransferPower.attach(context)
        return TransferHold.during {
            LanAnnounce.boost()
            sendWifiLocked(peer, request, cancelled)
        }
    }

    private fun sendWifiLocked(peer: StoredPeer, request: SendRequest, cancelled: () -> Boolean): TransferOutcome {
        val prepared = prepare(request)
        val transferId = java.util.UUID.randomUUID().toString()
        var attempt = 1
        while (true) {
            if (cancelled()) return fail(ErrorCodes.CANCELLED, "Cancelled").copy(state = TransferState.CANCELLED)
            val hopAtAttempt = hopGen.get()
            try {
                val requestPlan = discoveryRequest(peer)
                val plan = DiscoveryPlanner.plan(requestPlan)
                val vpn = NetInfo.vpn(context)
                val skipLan = vpn.on && !vpn.bypassable
                if (skipLan) {
                    note(org.stellartimelock.send.core.VpnStatus.line(true, false, true) ?: org.stellartimelock.send.core.VpnStatus.LINE)
                }
                LanFinder.announce(
                    UUID.fromString(identity.deviceId),
                    ProtocolLimits.TCP_PORT,
                    deviceName(),
                    requestPlan.ownIp,
                    onWifi = NetInfo.onWifi(context)
                )
                val hit = if (preferRelay || preferBluetooth || skipLan) null else LanFinder.find(context, UUID.fromString(peer.deviceId), requestPlan)
                val bluetooth = if (hit == null && !preferRelay && BluetoothLink.canDial(context)) {
                    runCatching { BluetoothLink.dial(context, BluetoothService.ATTEMPT_MS) }.getOrNull()
                } else {
                    null
                }
                if (hit == null && bluetooth == null && !preferRelay && BluetoothLink.canDial(context)) {
                    note("Bluetooth did not answer. Waking relay…")
                }
                val route = SendRoute.choose(lan = hit != null, bluetooth = bluetooth != null, forceRelay = preferRelay)
                val outcome = absorbQuota(when (route) {
                    SendRoute.WIFI -> {
                        pathLabel = Reachability.DIRECT.also { directMissed = false }
                        sendTo(hit!!, peer, prepared, cancelled, transferId)
                    }
                    SendRoute.BLUETOOTH -> {
                        pathLabel = Reachability.BLUETOOTH
                        bluetoothSession = true
                        try {
                            sendOverBluetooth(bluetooth!!, peer, prepared, cancelled, transferId)
                        } finally {
                            bluetoothSession = false
                        }
                    }
                    else -> {
                        val url = relayUrl()
                        if (url == null) {
                            val message = DiscoveryPlanner.describeFailure(plan)
                            lastError = message
                            if (RetryPolicy.shouldRetry(false, false, attempt, true)) {
                                Thread.sleep(RetryPolicy.delayMs(attempt))
                                attempt++
                                continue
                            }
                            SessionHub.emit(SessionEvent.Changed)
                            return fail(ErrorCodes.IO, Reachability.PC)
                        }
                        pathLabel = Reachability.RELAY
                        sendOverRelay(url, peer, prepared, cancelled, transferId)
                    }
                })
                if (outcome.state == TransferState.SUCCEEDED && hit != null) {
                    touchPeer(peer.copy(host = hit.ip, port = hit.port), confirmedHandshake = true)
                    lastError = null
                } else if (outcome.state == TransferState.SUCCEEDED) {
                    touchPeer(peer, confirmedHandshake = true)
                    lastError = null
                } else if (hopGen.get() != hopAtAttempt) {
                    lastError = NetworkHop.LINE
                } else {
                    lastError = outcome.message
                }
                val transient = outcome.code == ErrorCodes.IO || hopGen.get() != hopAtAttempt
                if (outcome.state == TransferState.SUCCEEDED || outcome.state == TransferState.CANCELLED ||
                    !RetryPolicy.shouldRetry(false, false, attempt, transient)
                ) {
                    SessionHub.emit(SessionEvent.Changed)
                    return outcome
                }
            } catch (ex: Exception) {
                if (ex is InterruptedException) throw ex
                noteKeyChange(ex, peer)?.let { return fail(it.code, it.message) }
                unrecognizedOutcome(ex)?.let { return it }
                val quota = takeQuota()
                if (ex is RelayQuotaException || quota != null) {
                    val message = quota ?: ex.message
                    noteQuota(message)
                    return fail(RelayPlans.QUOTA, message ?: RelayPlans.QUOTA_MESSAGE)
                }
                if (ex is RelayWakeException) {
                    lastError = ex.message
                    SessionHub.emit(SessionEvent.Changed)
                    return fail(RelayPlans.WAKE, ex.message ?: RelayWake.GAVE_UP)
                }
                val hopped = hopGen.get() != hopAtAttempt
                val transient = hopped || ex is IOException || ex is SocketException || ex is SocketTimeoutException
                lastError = if (hopped) NetworkHop.LINE else LogRedaction.redact(ex.message ?: "Transfer failed.")
                if (!transient || !RetryPolicy.shouldRetry(false, false, attempt, true)) {
                    SessionHub.emit(SessionEvent.Changed)
                    return fail(ErrorCodes.IO, lastError ?: "Transfer failed.")
                }
                note(if (hopped) NetworkHop.LINE else "Transfer interrupted. Retrying.")
            }
            attempt++
            val hopped = hopGen.get() != hopAtAttempt
            Thread.sleep(if (hopped) 200L else RetryPolicy.delayMs(attempt - 1))
        }
    }

    fun intentFor(methodId: String, request: SendRequest): Intent? {
        return when (methodId) {
            MethodIds.GMAIL -> Intents.gmail(context, request)
            MethodIds.SMS -> Intents.sms(context, request)
            MethodIds.TEXTPIPE -> Intents.textPipe(context, request)
            MethodIds.SHARE -> Intents.shareSheet(context, request)
            else -> null
        }
    }

    fun start() {
        TransferPower.attach(context)
        RelayPresenceWake.attach(context)
        RelayPresence.rejoin = { reason -> rejoinRelayNow(reason) }
        refreshRemoteToggle()
        watchNetwork()
        synchronized(gate) {
            stop.set(false)
            if (listenThread?.isAlive != true) {
                try {
                    if (server == null || server?.isClosed == true) {
                        server = bindListen()
                    }
                    listening = true
                    listenThread = Thread({ listen() }, "stellarsend-listen").also { it.isDaemon = true; it.start() }
                    note("Listening for a paired device.")
                    Thread({ sweepLeftovers() }, "stellarsend-apk-sweep").also { it.isDaemon = true; it.start() }
                    SelfUpdateStore.resume(context)
                } catch (ex: Exception) {
                    listening = false
                    dropped(ConnectionDrop.WIFI_LISTEN, ex)
                }
            }
            if (announceThread?.isAlive != true) {
                announceThread = Thread({ announceLoop() }, "stellarsend-announce").also { it.isDaemon = true; it.start() }
            }
            if (relayThread?.isAlive != true) {
                relayThread = Thread({ relayLoop() }, "stellarsend-relay-wait").also { it.isDaemon = true; it.start() }
            }
            if (bluetoothThread?.isAlive != true) {
                bluetoothThread = Thread({ bluetoothListen() }, "stellarsend-bluetooth").also { it.isDaemon = true; it.start() }
            }
            if (peers.load() != null) {
                if (PairStatus.isWakeText(lastError) && NetInfo.onWifi(context)) lastError = null
                if (pathLabel == Reachability.DIRECT) clearSwitchLine()
            }
            SessionHub.emit(SessionEvent.Changed)
        }
    }

    private fun bindListen(): ServerSocket {
        val socket = ServerSocket()
        socket.reuseAddress = true
        val ip = NetInfo.ipv4(context)
        if (ip != null) {
            socket.bind(InetSocketAddress(ip, ProtocolLimits.TCP_PORT))
        } else {
            socket.bind(InetSocketAddress(ProtocolLimits.TCP_PORT))
        }
        return socket
    }

    fun stop() {
        stop.set(true)
        listening = false
        unwatchNetwork()
        runCatching { server?.close() }
        runCatching { bluetoothServer?.close() }
        runCatching { relaySocket?.close() }
        runCatching { relayWaiting?.close() }
        RelayPresence.hooks?.cancelRetry()
        RelayPresence.hooks?.release()
        wakeRelaySleep()
        liveSockets.forEach { runCatching { it.close() } }
        SessionHub.emit(SessionEvent.Changed)
    }

    internal fun noteNetworkChange() {
        if (stop.get()) return
        val ticket = hopTicket.incrementAndGet()
        Thread({
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (ticket != hopTicket.get() || stop.get()) return@Thread
            val place = currentPlace()
            val first = lastPlace == null
            if (!NetworkHop.changed(lastPlace, place)) {
                lastPlace = place
                if (first) LanAnnounce.boost()
                rejoinRelayNow(RelayPresence.NETWORK)
                return@Thread
            }
            lastPlace = place
            LanAnnounce.boost()
            hop()
        }, "stellarsend-hop").apply { isDaemon = true; start() }
    }

    internal fun connectionInput(): ConnectionInput {
        val peer = peers.load() != null
        val onWifi = NetInfo.onWifi(context)
        val bt = BluetoothLink.snapshot(context)
        return ConnectionInput(
            paired = peer,
            listening = listening,
            pairingWindowOpen = System.currentTimeMillis() <= pairingUntil && !peer,
            onLan = peer && onWifi,
            lanUp = pathLabel == Reachability.DIRECT,
            directUp = pathLabel == Reachability.PUNCHED,
            directAvailable = pathLabel == Reachability.PUNCHED || (peer && relayUrl() != null && pathLabel != Reachability.DIRECT && !directMissed),
            relayUp = pathLabel == Reachability.RELAY,
            relayAvailable = peer && relayUrl() != null,
            usbUp = false,
            usbAvailable = false,
            lastError = lastError,
            directExpected = directMissed,
            bluetoothUp = pathLabel == Reachability.BLUETOOTH || bluetoothSession,
            bluetoothAvailable = peer && bt.canListen,
            bluetoothNeedsSetup = bt.present && !bt.permitted
        )
    }

    internal fun useRoute(kind: String) {
        when (kind) {
            ConnectionState.RELAY -> {
                preferRelay = true
                preferBluetooth = false
                pathLabel = Reachability.RELAY
                SessionHub.emit(SessionEvent.Changed)
            }
            ConnectionState.BLUETOOTH -> {
                preferRelay = false
                preferBluetooth = true
                pathLabel = Reachability.BLUETOOTH
                SessionHub.emit(SessionEvent.Changed)
            }
            else -> {
                preferRelay = false
                preferBluetooth = false
                hop()
            }
        }
    }

    private fun watchNetwork() {
        if (networkCallback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = noteNetworkChange()
            override fun onLost(network: Network) = noteNetworkChange()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = noteNetworkChange()
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = noteNetworkChange()
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
    }

    private fun unwatchNetwork() {
        val callback = networkCallback ?: return
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        networkCallback = null
        lastPlace = null
    }

    private fun currentPlace(): NetworkPlace {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkPlace(NetTransport.NONE, null)
        val network = manager.activeNetwork ?: return NetworkPlace(NetTransport.NONE, null)
        val caps = manager.getNetworkCapabilities(network)
        val transport = when {
            caps == null -> NetTransport.OTHER
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetTransport.CELLULAR
            else -> NetTransport.OTHER
        }
        return NetworkPlace(transport, NetInfo.ipv4(context))
    }

    private fun hop() {
        LanAnnounce.boost()
        hopGen.incrementAndGet()
        lastError = NetworkHop.LINE
        directMissed = false
        pathLabel = ""
        liveSockets.toList().forEach { runCatching { it.close() } }
        synchronized(gate) {
            runCatching { server?.close() }
            server = null
            runCatching { bluetoothServer?.close() }
            bluetoothServer = null
            runCatching { relaySocket?.close() }
            relaySocket = null
            runCatching { relayWaiting?.close() }
            relayWaiting = null
        }
        note("Relay rejoin: ${RelayPresence.NETWORK}.")
        relayRejoin.reset()
        RelayPresence.hooks?.hold()
        wakeRelaySleep()
        SessionHub.emit(SessionEvent.Changed)
        start()
    }

    private fun clearSwitchLine() {
        if (NetworkHop.isSwitch(lastError)) lastError = null
    }

    fun refreshRelay() {
        runCatching { relaySocket?.close() }
    }

    fun probeRelayUsage(): RelayReply? {
        val url = relayUrl() ?: return null
        return try {
            val reply = RelayLink.usage(url, relayAuth()) { showWaiting() }
            PlanStatus.noteUsage(context, reply)
            SessionHub.emit(SessionEvent.Changed)
            reply
        } catch (_: Exception) {
            null
        }
    }

    private fun listen() {
        var seenHop = hopGen.get()
        while (!stop.get()) {
            val socket = ensureListenSocket() ?: run {
                dropped(ConnectionDrop.WIFI_LISTEN)
                if (!sleepOrStop(1000)) break
                continue
            }
            listening = true
            seenHop = hopGen.get()
            val client = try {
                socket.accept()
            } catch (ex: Exception) {
                if (stop.get()) break
                val hopped = hopGen.get() != seenHop
                seenHop = hopGen.get()
                synchronized(gate) {
                    runCatching { if (server === socket) server?.close() }
                    if (server === socket) server = null
                    if (!hopped) listening = false
                }
                if (hopped) {
                    lastError = NetworkHop.LINE
                    SessionHub.emit(SessionEvent.Changed)
                } else {
                    dropped(ConnectionDrop.WIFI_LISTEN, ex)
                }
                if (!sleepOrStop(if (hopped) 50 else 1000)) break
                continue
            }
            liveSockets.add(client)
            client.keepAlive = true
            client.tcpNoDelay = true
            client.soTimeout = 8_000
            val hopSnapshot = seenHop
            Thread({
                try {
                    handle(client)
                } catch (ex: Exception) {
                    if (hopGen.get() != hopSnapshot) {
                        lastError = NetworkHop.LINE
                        SessionHub.emit(SessionEvent.Changed)
                    } else {
                        dropped(ConnectionDrop.INCOMING, ex)
                    }
                } finally {
                    liveSockets.remove(client)
                    runCatching { client.close() }
                }
            }, "stellarsend-accept").apply { isDaemon = true; start() }
        }
        listening = false
        SessionHub.emit(SessionEvent.Changed)
    }

    private fun ensureListenSocket(): ServerSocket? = synchronized(gate) {
        if (stop.get()) return@synchronized null
        val existing = server
        if (existing != null && !existing.isClosed) return@synchronized existing
        try {
            bindListen().also { server = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun handle(socket: Socket, relay: Boolean = false) {
        val hopAtStart = hopGen.get()
        repairOpen()
        val peer = peers.load()
        val window = System.currentTimeMillis() <= pairingUntil
        val repairing = repair != null
        val pairing = repairing || (window && peer == null)
        if (peer == null && !pairing) {
            note("Rejected a connection while unpaired.")
            return
        }
        if (peer == null && socket.inetAddress?.isLoopbackAddress != true && isThrowaway(socket)) return
        TransferHold.enter()
        LanAnnounce.boost()
        try {
        val remoteHost = socket.inetAddress?.hostAddress
        val takeNext = pairing || acceptNextPin || peer?.acceptNext == true
        val takeAny = takeNext || peer != null
        val expect = if (takeNext) null else peer
        var accepting: SSLSocket? = null
        val ssl = try {
            acceptTls(socket, expect?.fingerprint, takeAny).also { accepting = it; it.startHandshake() }
        } catch (ex: Exception) {
            stashSeenFromSsl(accepting, peer)
            if (peer != null && !trustAlreadySaved(ex, peer)) noteKeyChange(ex, peer)
            throw ex
        }
        socket.soTimeout = 0
        ssl.soTimeout = 0
        val remoteCert = ssl.session.peerCertificates.first() as java.security.cert.X509Certificate
        val remoteFp = sha256Hex(remoteCert.publicKey.encoded)
        if (remoteFp.equals(identity.fingerprint, ignoreCase = true)) {
            note("Ignored a connection from this phone.")
            return
        }
        if (peer != null) {
            applySeenPin(peer, remoteCert)
        }
        val inboxDir = PhoneInbox.directory(context)
        var senderName = expect?.name ?: "your computer"
        var shownTransfer = ""
        var pendingPeer: StoredPeer? = null
        val seen = if (relay) RelaySeen.take(socket) else null
        val punch = if (relay && !preferRelay && !pairing) DirectAttempt.start(seen, listOfNotNull(NetInfo.ipv4(context))) else null
        val outcome = try {
            TransferSession.receive(
            ssl.outputStream,
            ssl.inputStream,
            identity,
            expect?.fingerprint,
            expect?.deviceId,
            remoteFp,
            allowNew = pairing,
            confirmPeer = { name, deviceId, fingerprint ->
                val accepted = ask("Pair with $name?", "Confirm this device before trusting its key.")
                if (accepted) {
                    senderName = name
                    val host = if (remoteHost != null && LocalAddressPolicy.isAllowedHost(remoteHost, false)) remoteHost else null
                    pendingPeer = StoredPeer(deviceId, name, fingerprint, base64UrlEncode(remoteCert.publicKey.encoded), host, ProtocolLimits.TCP_PORT, System.currentTimeMillis())
                }
                accepted
            },
            confirmOffer = { name, _ ->
                val paired = peers.load() != null
                val fortress = FortressBotHandoff.acceptOffer(name, paired)
                when {
                    fortress != null -> fortress
                    ApkAuto.autoAcceptOffer(name, paired) -> true
                    else -> ask("Receive $name?", "Incoming transfer from $senderName.")
                }
            },
            openFile = { FileNames.allocate(inboxDir, it) },
            onProgress = { update ->
                val snap = ReceiveLive.edit {
                    noteSender(senderName)
                    noteProgress(update.id, update.name, update.bytes, update.total, update.batchIndex, update.batchCount)
                }
                SessionHub.emit(SessionEvent.Progress(update))
                SessionHub.emit(SessionEvent.Receive(snap))
                if (update.id != shownTransfer) {
                    shownTransfer = update.id
                }
            },
            announcement = planAnnouncement(),
            onPaired = { pendingPeer?.let { if (pairing) commitScanned(it) else commitPeer(it) } },
            onService = { handleIncomingRemote(it) },
            onRemoteInput = { event ->
                refreshRemoteToggle()
                remote.acceptInput(event.session) && RemoteControlService.apply(event)
            },
            remoteCancelled = { stop.get() },
            direct = if (pairing) null else punch,
            onDirect = { up ->
                directMissed = !up
                pathLabel = if (up) Reachability.PUNCHED else Reachability.RELAY
                SessionHub.emit(SessionEvent.Changed)
            }
        )
        } finally {
            runCatching { punch?.close() }
        }
        val quotaMessage = takeQuota()
        if (quotaMessage != null) {
            noteQuota(quotaMessage)
            return
        }
        val receivedText = outcome.text
        if (outcome.state == TransferState.SUCCEEDED && receivedText != null) {
            val file = FileNames.allocate(inboxDir, "message.txt")
            file.writeText(receivedText)
            copyText(receivedText)
            val entry = receipts.add(file, "text")
            PhoneInbox.maybeExport(context, file)
            publishReceived(senderName, entry.id, file.name, "text", file.absolutePath)
            SessionHub.emit(SessionEvent.Notice("Saved text from the computer and copied it to the clipboard."))
            SessionHub.emit(SessionEvent.ReceivedChanged)
        } else if (outcome.state == TransferState.SUCCEEDED && outcome.message == "Paired") {
            note("Paired.")
        } else if (outcome.state == TransferState.SUCCEEDED) {
            val savedPath = outcome.savedPath
            if (savedPath != null) {
                var saved = File(savedPath)
                var kind = when {
                    ApkReplacePreview.isApk(saved.name) -> "apk"
                    ReceivedHistory.isText(saved.name) -> "text"
                    else -> "file"
                }
                if (FortressBotHandoff.hideFromReceivedList(saved.name)) {
                    if (peer == null && peers.load() == null) {
                        saved.delete()
                        note(FortressBotHandoff.REJECTED_UNPAIRED)
                    } else if (FortressBotHandoff.handOffToFortress(saved.name)) {
                        if (FortressBotDelivery.handOff(context, saved)) {
                            note(FortressBotHandoff.HANDED)
                        } else {
                            note(FortressBotHandoff.FORTRESS_MISSING)
                        }
                    } else {
                        saved.delete()
                    }
                } else {
                    if (FolderArchive.isBundle(saved.name)) {
                        val unpacked = runCatching { FolderArchive.unpack(saved, PhoneInbox.directory(context)) }.getOrNull()
                        if (unpacked != null) {
                            saved.delete()
                            saved = unpacked
                            kind = "folder"
                            note("Received a folder.")
                        }
                    } else if (kind == "apk") {
                        note("Received ${saved.name}.")
                    } else {
                        note("Received a file.")
                    }
                    val entry = receipts.add(saved, kind)
                    PhoneInbox.maybeExport(context, saved)
                    publishReceived(senderName, entry.id, saved.name, kind, saved.absolutePath)
                    if (kind == "apk") offerReceivedApk(saved, entry.id, fromPairedPc = peer != null || peers.load() != null)
                    else {
                        if (peer != null && InstallPrefs.auto(context)) PcAutoInstall.onArrived(context, entry.id)
                        SessionHub.emit(SessionEvent.ReceivedChanged)
                    }
                }
            }
        } else if (shownTransfer.isNotEmpty()) {
            SessionHub.emit(SessionEvent.Receive(ReceiveLive.edit { noteIdle() }))
            if (peer != null && InstallPrefs.auto(context)) PcAutoInstall.onArrived(context, "", force = true)
        }
        lastError = when {
            outcome.state == TransferState.SUCCEEDED -> null
            hopGen.get() != hopAtStart -> NetworkHop.LINE
            else -> outcome.message
        }
        SessionHub.emit(SessionEvent.Changed)
        } finally {
            TransferHold.leave()
        }
    }

    private fun announceLoop() {
        LanAnnounce.armWait(Thread.currentThread())
        while (!stop.get()) {
            repairOpen()
            val id = runCatching { UUID.fromString(identity.deviceId) }.getOrNull()
            val paired = peers.load() != null
            val pairing = repair != null || (System.currentTimeMillis() <= pairingUntil && !paired)
            val onWifi = NetInfo.onWifi(context)
            val interval = LanAnnounce.intervalMs(onWifi, pairing)
            if (id != null && (paired || pairing) && interval != null) {
                val code = if (pairing) org.stellartimelock.send.core.ShortCode.format(identity.fingerprint) else null
                LanFinder.announce(id, ProtocolLimits.TCP_PORT, deviceName(), NetInfo.ipv4(context), code, onWifi = true)
            }
            try {
                Thread.sleep(LanAnnounce.sleepMs(interval))
            } catch (_: InterruptedException) {
                if (stop.get()) return
            }
        }
    }

    private fun bluetoothListen() {
        while (!stop.get()) {
            if (!BluetoothLink.canDial(context)) {
                if (!sleepOrStop(1500)) return
                continue
            }
            val server = BluetoothLink.listen(context)
            if (server == null) {
                if (!sleepOrStop(1500)) return
                continue
            }
            bluetoothServer = server
            try {
                while (!stop.get()) {
                    val client = server.accept()
                    BluetoothLink.remember(context, runCatching { client.remoteDevice?.address }.getOrNull())
                    val raw = try {
                        BluetoothBridge.adopt(client)
                    } catch (ex: Exception) {
                        runCatching { client.close() }
                        note("Bluetooth link closed. ${ex.javaClass.simpleName}")
                        continue
                    }
                    raw.soTimeout = 8_000
                    liveSockets.add(raw)
                    Thread({
                        try {
                            bluetoothSession = true
                            pathLabel = Reachability.BLUETOOTH
                            SessionHub.emit(SessionEvent.Changed)
                            handle(raw)
                        } catch (ex: Exception) {
                            dropped(ConnectionDrop.INCOMING, ex)
                        } finally {
                            bluetoothSession = false
                            liveSockets.remove(raw)
                            runCatching { raw.close() }
                            if (!preferBluetooth && pathLabel == Reachability.BLUETOOTH && NetInfo.onWifi(context) && listening) {
                                pathLabel = Reachability.DIRECT
                            }
                            SessionHub.emit(SessionEvent.Changed)
                        }
                    }, "stellarsend-bt-accept").apply { isDaemon = true; start() }
                }
            } catch (ex: Exception) {
                if (!stop.get()) note("Bluetooth listen stopped. ${ex.javaClass.simpleName}")
            } finally {
                runCatching { server.close() }
                if (bluetoothServer === server) bluetoothServer = null
            }
            if (!stop.get() && !sleepOrStop(400)) return
        }
    }

    private fun sendOverBluetooth(
        bluetooth: BluetoothSocket,
        peer: StoredPeer,
        prepared: Prepared,
        cancelled: () -> Boolean,
        transferId: String
    ): TransferOutcome {
        BluetoothLink.remember(context, runCatching { bluetooth.remoteDevice?.address }.getOrNull())
        val raw = BluetoothBridge.adopt(bluetooth)
        raw.soTimeout = 12_000
        return connectExisting(raw, peer.fingerprint) { ssl ->
            val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as java.security.cert.X509Certificate).publicKey.encoded)
            prepared.open().use { content ->
                TransferSession.send(
                    ssl.outputStream,
                    ssl.inputStream,
                    identity,
                    peer.deviceId,
                    peer.fingerprint,
                    remoteFp,
                    prepared.kind,
                    prepared.name,
                    prepared.mime,
                    prepared.size,
                    prepared.sha256,
                    content,
                    cancelled,
                    { SessionHub.emit(SessionEvent.Progress(it)) },
                    planAnnouncement(),
                    transferId
                )
            }
        }
    }

    private fun <T> connectExisting(raw: Socket, fingerprint: String, block: (SSLSocket) -> T): T {
        val ssl = clientHandshake(raw, "bluetooth", raw.localPort.coerceAtLeast(1), fingerprint)
        raw.soTimeout = 0
        ssl.soTimeout = 0
        ssl.use { return block(it) }
    }

    private fun sendTo(hit: DiscoveryHit, peer: StoredPeer, prepared: Prepared, cancelled: () -> Boolean, transferId: String): TransferOutcome {
        return connect(hit.ip, hit.port, peer.fingerprint) { ssl ->
            val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as java.security.cert.X509Certificate).publicKey.encoded)
            prepared.open().use { content ->
                TransferSession.send(
                    ssl.outputStream,
                    ssl.inputStream,
                    identity,
                    peer.deviceId,
                    peer.fingerprint,
                    remoteFp,
                    prepared.kind,
                    prepared.name,
                    prepared.mime,
                    prepared.size,
                    prepared.sha256,
                    content,
                    cancelled,
                    { SessionHub.emit(SessionEvent.Progress(it)) },
                    planAnnouncement(),
                    transferId
                )
            }
        }
    }

    private fun <T> connect(host: String, port: Int, fingerprint: String, block: (SSLSocket) -> T): T {
        if (!LocalAddressPolicy.isAllowedHost(host, false)) {
            throw ProtocolException("Refusing a non-local address.")
        }
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.sendBufferSize = FrameSlices.MAX_SLICE * 8
        raw.receiveBufferSize = FrameSlices.MAX_SLICE * 8
        NetInfo.bindLan(raw, context)
        raw.connect(InetSocketAddress(host, port), 8000)
        val ssl = clientHandshake(raw, host, port, fingerprint)
        ssl.use { return block(it) }
    }

    private fun clientHandshake(raw: Socket, host: String, port: Int, fingerprint: String): SSLSocket {
        val peer = peers.load()
        val takeNext = acceptNextPin || peer?.acceptNext == true
        val takeAny = takeNext || peer != null
        val ssl = TlsPins.client(
            identity,
            if (takeNext) null else fingerprint,
            allowNew = takeAny
        ).socketFactory.createSocket(raw, host, port, true) as SSLSocket
        TlsPins.configure(ssl, true)
        try {
            ssl.startHandshake()
        } catch (ex: Exception) {
            stashSeenFromSsl(ssl, peer)
            if (peer != null && !trustAlreadySaved(ex, peer)) noteKeyChange(ex, peer)
            throw ex
        }
        val remoteCert = ssl.session.peerCertificates.first() as java.security.cert.X509Certificate
        if (peer != null) {
            applySeenPin(peer, remoteCert)
        }
        return ssl
    }

    private fun applySeenPin(peer: StoredPeer, remoteCert: java.security.cert.X509Certificate) {
        val remoteFp = sha256Hex(remoteCert.publicKey.encoded)
        val remoteKey = base64UrlEncode(remoteCert.publicKey.encoded)
        if (remoteFp.equals(identity.fingerprint, ignoreCase = true)) {
            note("Handshake reached this phone's own key. Not a new PC key.")
            throw IOException("This phone answered its own connection.")
        }
        lastSeenPin = remoteFp
        lastSeenKey = remoteKey
        val stored = peers.load() ?: peer
        note(
            "Handshake pin stored=${org.stellartimelock.send.core.TrustNewKey.shortPin(stored.fingerprint)} " +
                "seen=${org.stellartimelock.send.core.TrustNewKey.shortPin(remoteFp)} " +
                "acceptNext=${acceptNextPin || stored.acceptNext}"
        )
        if (remoteFp.equals(stored.fingerprint, ignoreCase = true)) {
            if (stored.acceptNext) {
                peers.savePinned(stored.copy(acceptNext = false))
                acceptNextPin = false
            }
            pendingTrust?.let { dropStaleTrust(it, stored) }
            return
        }
        val samePc = TlsPins.deviceId(remoteCert)?.let { org.stellartimelock.send.core.PeerTrust.sameDevice(stored.deviceId, it) } == true
        if (acceptNextPin || stored.acceptNext || samePc || justTrusted) {
            val next = peers.trustNewKey(remoteFp, remoteKey)
            val reloaded = peers.reload()
            note(
                "Trust pin after handshake before=${org.stellartimelock.send.core.TrustNewKey.shortPin(stored.fingerprint)} " +
                    "after=${org.stellartimelock.send.core.TrustNewKey.shortPin(reloaded?.fingerprint)}"
            )
            acceptNextPin = false
            justTrusted = false
            pendingTrust = null
            lastError = null
            lastSeenPin = remoteFp
            lastSeenKey = remoteKey
            note("Trusted the new key for ${next?.name ?: stored.name}.")
            SessionHub.emit(SessionEvent.Changed)
            return
        }
        val changed = org.stellartimelock.send.core.KeyChangedException(remoteFp, remoteCert.publicKey.encoded)
        noteKeyChange(changed, stored)
        throw changed
    }

    private fun firstDirect(hosts: List<String>, port: Int, fingerprint: String): SSLSocket {
        val unique = hosts.filter { LocalAddressPolicy.isAllowedHost(it, false) }.distinct()
        if (unique.isEmpty()) throw IOException(Reachability.PC)
        val winner = AtomicReference<SSLSocket?>(null)
        val remaining = AtomicInteger(unique.size)
        val done = CountDownLatch(1)
        unique.forEach { host ->
            Thread {
                var raw: Socket? = null
                try {
                    raw = Socket()
                    NetInfo.bindLan(raw, context)
                    raw.connect(InetSocketAddress(host, port), 2500)
                    val ssl = clientHandshake(raw, host, port, fingerprint)
                    if (!winner.compareAndSet(null, ssl)) ssl.close() else done.countDown()
                } catch (_: Exception) {
                    runCatching { raw?.close() }
                } finally {
                    if (remaining.decrementAndGet() == 0) done.countDown()
                }
            }.apply { isDaemon = true; start() }
        }
        done.await(8, TimeUnit.SECONDS)
        return winner.get() ?: throw IOException(Reachability.PC)
    }

    private fun relaySocket(url: String, room: String, fingerprint: String, role: String = "dial"): SSLSocket {
        val dial = role == "dial"
        if (dial) {
            relayDial.begin()
            runCatching { relaySocket?.close() }
            runCatching { relayWaiting?.close() }
            val deadline = System.currentTimeMillis() + 800
            while (relayHeld.get() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        }
        try {
            val raw = RelayLink.open(url, room, role, relayAuth(), { showWaiting() }, { noteReady(it) }, identity.deviceId)
            val seen = RelaySeen.take(raw)
            return try {
                val ssl = clientHandshake(raw, "stellarsend", raw.port, fingerprint)
                RelaySeen.put(ssl, seen)
                ssl
            } catch (ex: Exception) {
                runCatching { raw.close() }
                val quota = takeQuota()
                if (quota != null) throw RelayQuotaException(quota)
                throw ex
            }
        } finally {
            if (dial) relayDial.end()
        }
    }

    private fun sendOverRelay(url: String, peer: StoredPeer, prepared: Prepared, cancelled: () -> Boolean, transferId: String): TransferOutcome {
        val ssl = relaySocket(url, RelayRoom.paired(identity.deviceId, peer.deviceId), peer.fingerprint)
        val punch = if (preferRelay) null else DirectAttempt.start(RelaySeen.take(ssl), listOfNotNull(NetInfo.ipv4(context)))
        return try {
            ssl.use {
                val remoteFp = sha256Hex((it.session.peerCertificates[0] as java.security.cert.X509Certificate).publicKey.encoded)
                prepared.open().use { content ->
                    TransferSession.send(
                        it.outputStream,
                        it.inputStream,
                        identity,
                        peer.deviceId,
                        peer.fingerprint,
                        remoteFp,
                        prepared.kind,
                        prepared.name,
                        prepared.mime,
                        prepared.size,
                        prepared.sha256,
                        content,
                        cancelled,
                        { SessionHub.emit(SessionEvent.Progress(it)) },
                        planAnnouncement(),
                        transferId,
                        direct = punch,
                        onDirect = { up ->
                            directMissed = !up
                            pathLabel = if (up) Reachability.PUNCHED else Reachability.RELAY
                            SessionHub.emit(SessionEvent.Changed)
                        }
                    )
                }
            }
        } finally {
            runCatching { punch?.close() }
        }
    }

    private val relayDial = org.stellartimelock.send.core.RelayDialGate()
    private val relayHeld = java.util.concurrent.atomic.AtomicBoolean(false)
    private val relayRejoin = RelayRejoin()
    private val kickGapMs = 3_000L

    /** The listen socket while it waits in the relay room for the PC. Closed on a network hop. */
    @Volatile private var relayWaiting: Socket? = null
    private val relayKick = Object()
    private var relayKickPending = false
    @Volatile private var lastKickMs = 0L
    @Volatile private var dropsInRow = 0

    /**
     * Screen-on, a network change, app open, or the retry alarm. If the phone is
     * already waiting in its room nothing happens. Otherwise skip the backoff and
     * rejoin now under a short timed hold.
     */
    fun rejoinRelayNow(reason: String) {
        if (stop.get()) return
        if (reason != RelayPresence.SCREEN_ON) LanAnnounce.boost()
        if (!RelayPresence.needsRejoin(waitingInRoom = relayHeld.get())) return
        val now = System.currentTimeMillis()
        if (now - lastKickMs < kickGapMs) return
        lastKickMs = now
        note("Relay rejoin: $reason.")
        relayRejoin.reset()
        if (online()) RelayPresence.hooks?.hold()
        wakeRelaySleep()
    }

    private fun wakeRelaySleep() {
        synchronized(relayKick) {
            relayKickPending = true
            relayKick.notifyAll()
        }
    }

    /** Backoff wait that a rejoin kick ends early. False when the engine stopped. */
    private fun relaySleep(ms: Long): Boolean {
        synchronized(relayKick) {
            if (!relayKickPending) {
                try {
                    relayKick.wait(ms)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            relayKickPending = false
        }
        return !stop.get()
    }

    /** Lost the room. Log why, keep the CPU up briefly for the rejoin, and arm the Doze-safe retry. */
    private fun relayDropped(reason: String) {
        dropsInRow++
        note("Relay dropped: $reason. Rejoining.")
        if (RelayPresence.holdsAfterDrop(dropsInRow) && online()) {
            RelayPresence.hooks?.hold()
        } else {
            RelayPresence.hooks?.release()
        }
        RelayPresence.hooks?.scheduleRetry(RelayPresence.ALARM_MS)
    }

    private fun online(): Boolean =
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null }.getOrDefault(true)

    private fun relayLoop() {
        while (!stop.get()) {
            if (!relayDial.shouldListen()) {
                if (!sleepOrStop(40)) return
                continue
            }
            val url = relayUrl()
            val room = currentRelayRoom()
            if (url == null || room == null) {
                if (!sleepOrStop(500)) return
                continue
            }
            var raw: Socket? = null
            var joinedAt = 0L
            try {
                relayHeld.set(true)
                raw = RelayLink.open(url, room, "listen", relayAuth(), { showWaiting() }, { noteReady(it) }, identity.deviceId) { joined ->
                    relayWaiting = joined
                    dropsInRow = 0
                    note("Relay joined: waiting in the room for the PC.")
                    RelayPresence.hooks?.cancelRetry()
                    RelayPresence.hooks?.release()
                }
                relayWaiting = null
                joinedAt = System.currentTimeMillis()
                relaySocket = raw
                quotaAnnounced = false
                if (stop.get()) {
                    return
                }
                note("Relay joined: PC connected.")
                pathLabel = Reachability.RELAY
                handle(raw, relay = true)
            } catch (ex: RelayQuotaException) {
                if (!pauseForQuota(ex.message ?: RelayPlans.QUOTA_MESSAGE)) return
            } catch (ex: RelayWakeException) {
                val onWifi = NetInfo.onWifi(context)
                val message = ex.message
                relayDropped(LogRedaction.redact(message ?: "relay wake failed"))
                if (peers.load() != null && listening && onWifi) {
                    if (pathLabel.isBlank() || PairStatus.isWakeText(pathLabel)) pathLabel = Reachability.DIRECT.also { directMissed = false }
                    if (PairStatus.isWakeText(lastError) || message == RelayWake.PHONE_MISSING || message == RelayWake.NO_PEER) lastError = null
                    SessionHub.emit(SessionEvent.Changed)
                    if (!relaySleep(relayBackoffMs(joinedAt))) return
                    continue
                }
                lastError = message
                pathLabel = ""
                SessionHub.emit(SessionEvent.Changed)
                if (!relaySleep(relayBackoffMs(joinedAt))) return
            } catch (ex: Exception) {
                val close = RelayLink.lastClose.getAndSet(null)
                if (!stop.get()) {
                    relayDropped("${close ?: "code=none"} ${ex.javaClass.simpleName}: ${LogRedaction.redact(ex.message ?: "closed")}")
                }
                dropped(ConnectionDrop.RELAY, ex)
                if (!relaySleep(relayBackoffMs(joinedAt))) return
            } finally {
                relayHeld.set(false)
                relayWaiting = null
                val closing = raw
                if (closing != null) {
                    runCatching { closing.close() }
                    if (relaySocket === closing) relaySocket = null
                }
            }
        }
    }

    private fun relayBackoffMs(joinedAt: Long): Long {
        val upFor = if (joinedAt == 0L) 0L else System.currentTimeMillis() - joinedAt
        return relayRejoin.delayAfterClose(upFor)
    }

    private fun currentRelayRoom(): String? {
        repairOpen()
        val peer = peers.load()
        if (peer != null) {
            val self = repair?.previous?.deviceId ?: identity.deviceId
            return RelayRoom.paired(self, peer.deviceId)
        }
        if (System.currentTimeMillis() <= pairingUntil) return RelayRoom.pairing(identity.deviceId)
        return null
    }

    private fun relayUrl(): String? = BackgroundListen.relayUrl(context)

    private fun acceptTls(socket: Socket, fingerprint: String?, allowNew: Boolean): SSLSocket {
        val ssl = TlsPins.server(identity, fingerprint, allowNew).socketFactory.createSocket(socket, null, socket.port, true) as SSLSocket
        TlsPins.configure(ssl, false)
        return ssl
    }

    private fun callCompanionStreaming(request: ServiceRequest, onOutput: (String, String) -> Unit): ServiceResult {
        repairOpen()
        val peer = peers.load() ?: return ServiceResult.fail(request.id, ErrorCodes.UNKNOWN_PEER, "Pair a device before sending.")
        return try {
            val hit = locate(peer)
            val result = connect(hit.ip, hit.port, peer.fingerprint) { ssl ->
                val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as java.security.cert.X509Certificate).publicKey.encoded)
                TransferSession.callStreaming(
                    ssl.outputStream,
                    ssl.inputStream,
                    identity,
                    peer.deviceId,
                    peer.fingerprint,
                    remoteFp,
                    request,
                    onOutput,
                    planAnnouncement()
                )
            }
            touchPeer(peer.copy(host = hit.ip, port = hit.port), confirmedHandshake = true)
            pathLabel = Reachability.DIRECT.also { directMissed = false }
            note("Terminal ${request.op}.")
            SessionHub.emit(SessionEvent.Changed)
            result
        } catch (ex: Exception) {
            if (ex is InterruptedException) throw ex
            unrecognizedResult(request.id, ex)?.let { return it }
            val url = relayUrl()
            if (url != null) {
                try {
                    pathLabel = Reachability.RELAY
                    val relayed = relaySocket(url, RelayRoom.paired(identity.deviceId, peer.deviceId), peer.fingerprint).use { ssl ->
                        val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as java.security.cert.X509Certificate).publicKey.encoded)
                        TransferSession.callStreaming(
                            ssl.outputStream,
                            ssl.inputStream,
                            identity,
                            peer.deviceId,
                            peer.fingerprint,
                            remoteFp,
                            request,
                            onOutput,
                            planAnnouncement()
                        )
                    }
                    touchPeer(peer, confirmedHandshake = true)
                    note("Terminal ${request.op}.")
                    SessionHub.emit(SessionEvent.Changed)
                    return relayed
                } catch (relay: Exception) {
                    if (relay is InterruptedException) throw relay
                    unrecognizedResult(request.id, relay)?.let { return it }
                    val quota = takeQuota()
                    if (relay is RelayQuotaException || quota != null) {
                        val message = quota ?: relay.message ?: RelayPlans.QUOTA_MESSAGE
                        noteQuota(message)
                        return ServiceResult.fail(request.id, RelayPlans.QUOTA, message)
                    }
                    if (relay is RelayWakeException) {
                        lastError = relay.message
                        SessionHub.emit(SessionEvent.Changed)
                        return ServiceResult.fail(request.id, RelayPlans.WAKE, relay.message ?: RelayWake.GAVE_UP)
                    }
                    val code = if (relay is ProtocolException) relay.code else ErrorCodes.IO
                    return ServiceResult.fail(request.id, code, Reachability.PC)
                }
            }
            val code = if (ex is ProtocolException) ex.code else ErrorCodes.IO
            ServiceResult.fail(request.id, code, LogRedaction.redact(ex.message ?: Reachability.PC))
        }
    }

    private fun callCompanion(service: String, op: String, body: JSONObject, mapIdentity: Boolean = true): ServiceResult {
        val id = UUID.randomUUID().toString()
        if (!ServiceCodec.isToken(service, 64) || !ServiceCodec.isToken(op, 32)) {
            return ServiceResult.fail(id, ErrorCodes.BAD_REQUEST, "Companion call is invalid.")
        }
        repairOpen()
        val peer = peers.load() ?: return ServiceResult.fail(id, ErrorCodes.UNKNOWN_PEER, "Pair a device before sending.")
        return try {
            val hit = locate(peer)
            val result = connect(hit.ip, hit.port, peer.fingerprint) { ssl ->
                val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as java.security.cert.X509Certificate).publicKey.encoded)
                TransferSession.call(
                    ssl.outputStream,
                    ssl.inputStream,
                    identity,
                    peer.deviceId,
                    peer.fingerprint,
                    remoteFp,
                    ServiceRequest(id, service, op, body),
                    planAnnouncement()
                )
            }
            touchPeer(peer.copy(host = hit.ip, port = hit.port), confirmedHandshake = true)
            pathLabel = Reachability.DIRECT.also { directMissed = false }
            note("Companion $service $op.")
            SessionHub.emit(SessionEvent.Notice(result.message))
            result
        } catch (ex: Exception) {
            if (ex is InterruptedException) throw ex
            if (mapIdentity) unrecognizedResult(id, ex)?.let { return it }
            else note("Companion $service $op missed Wi-Fi. ${ex.javaClass.simpleName}: ${ex.message}")
            val url = relayUrl()
            if (url != null) {
                try {
                    pathLabel = Reachability.RELAY
                    val relayed = relaySocket(url, RelayRoom.paired(identity.deviceId, peer.deviceId), peer.fingerprint).use { ssl ->
                        val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as java.security.cert.X509Certificate).publicKey.encoded)
                        TransferSession.call(
                            ssl.outputStream,
                            ssl.inputStream,
                            identity,
                            peer.deviceId,
                            peer.fingerprint,
                            remoteFp,
                            ServiceRequest(id, service, op, body),
                            planAnnouncement()
                        )
                    }
                    touchPeer(peer, confirmedHandshake = true)
                    note("Companion $service $op.")
                    SessionHub.emit(SessionEvent.Notice(relayed.message))
                    SessionHub.emit(SessionEvent.Changed)
                    return relayed
                } catch (relay: Exception) {
                    if (relay is InterruptedException) throw relay
                    if (mapIdentity) unrecognizedResult(id, relay)?.let { return it }
                    else note("Companion $service $op missed the relay. ${relay.javaClass.simpleName}: ${relay.message}")
                    val quota = takeQuota()
                    if (relay is RelayQuotaException || quota != null) {
                        val message = quota ?: relay.message ?: RelayPlans.QUOTA_MESSAGE
                        noteQuota(message)
                        return ServiceResult.fail(id, RelayPlans.QUOTA, message)
                    }
                    if (relay is RelayWakeException) {
                        lastError = relay.message
                        SessionHub.emit(SessionEvent.Changed)
                        return ServiceResult.fail(id, RelayPlans.WAKE, relay.message ?: RelayWake.GAVE_UP)
                    }
                    val code = if (relay is ProtocolException) relay.code else ErrorCodes.IO
                    return ServiceResult.fail(id, code, Reachability.PC)
                }
            }
            val code = if (ex is ProtocolException) ex.code else ErrorCodes.IO
            ServiceResult.fail(id, code, LogRedaction.redact(ex.message ?: Reachability.PC))
        }
    }

    private fun locate(peer: StoredPeer): DiscoveryHit {
        var attempt = 1
        var last = "Could not find the paired PC."
        while (true) {
            val requestPlan = discoveryRequest(peer)
            val plan = DiscoveryPlanner.plan(requestPlan)
            LanFinder.announce(UUID.fromString(identity.deviceId), ProtocolLimits.TCP_PORT, deviceName(), requestPlan.ownIp)
            val hit = LanFinder.find(context, UUID.fromString(peer.deviceId), requestPlan)
            if (hit != null) return hit
            last = DiscoveryPlanner.describeFailure(plan)
            if (attempt >= 2) throw ProtocolException(last, ErrorCodes.IO)
            attempt++
            Thread.sleep(300)
        }
    }

    private fun discoveryRequest(peer: StoredPeer) = DiscoveryRequest(
        lastKnownIp = peer.host,
        manualIp = manualHost(),
        gatewayIp = NetInfo.gateway(context),
        ownIp = NetInfo.ipv4(context),
        port = peer.port,
        allowLoopback = false,
        extraIps = peer.hosts
    )

    private fun prepare(request: SendRequest): Prepared {
        return when (request.kind) {
            PayloadKind.TEXT -> {
                val text = request.text ?: throw ProtocolException("Enter text first.")
                val bytes = text.toByteArray(Charsets.UTF_8)
                if (bytes.size > ProtocolLimits.MAX_TEXT_BYTES) throw ProtocolException("Text is too long.", ErrorCodes.LIMIT)
                Prepared("text", request.name.ifBlank { "message.txt" }, "text/plain", bytes.size.toLong(), sha256Hex(bytes)) { bytes.inputStream() }
            }
            PayloadKind.FILE -> {
                val uri = request.uri ?: throw ProtocolException("Choose a file first.")
                val cache = File(context.cacheDir, "outgoing").apply { mkdirs() }
                val file = FileNames.allocate(cache, request.name)
                copyBounded(uri, file, ProtocolLimits.MAX_FILE_BYTES)
                val hash = file.inputStream().use { sha256Hex(it) }
                Prepared("file", FileNames.sanitize(request.name), request.mimeType, file.length(), hash) { file.inputStream() }
            }
        }
    }

    private fun copyBounded(uri: Uri, target: File, max: Long) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > max) {
                        target.delete()
                        throw ProtocolException("Content exceeds the size limit.", ErrorCodes.LIMIT)
                    }
                    output.write(buffer, 0, n)
                }
            }
        } ?: throw ProtocolException("Could not read the selected file.")
    }

    private fun handleIncomingRemote(request: ServiceRequest): ServiceResult {
        if (request.service != CompanionServices.REMOTE_CONTROL) {
            return ServiceResult.fail(request.id, ErrorCodes.NOT_IMPLEMENTED, "This device does not accept companion calls.")
        }
        refreshRemoteToggle()
        val session = request.body.optString("session", remote.sessionId.orEmpty())
        val from = request.body.optString("from", RemoteControl.FROM_PC)
        if (!RemoteDesktopGate.allow(RemoteDesktopBuild.enabled(context), request.service, request.op, from)) {
            return ServiceResult.fail(request.id, ErrorCodes.NOT_IMPLEMENTED, RemoteDesktopGate.UNAVAILABLE)
        }
        return when (request.op) {
            RemoteControl.OP_STATUS -> ServiceResult.done(request.id, RemoteDesktop.READY)
            RemoteControl.OP_REQUEST -> {
                val asked = remote.request(session, from)
                if (!asked.ok) return ServiceResult.fail(request.id, asked.code, asked.message)
                val title = if (from == RemoteControl.FROM_PC) "Allow phone control?" else RemotePhrases.current.allowTitle
                val allowed = ask(title, asked.message)
                if (!allowed) {
                    val denied = remote.deny(session)
                    return ServiceResult.fail(request.id, denied.code, denied.message)
                }
                val ok = remote.allow(session)
                if (ok.ok) {
                    Notifier.beingControlled(context)
                    SessionHub.emit(SessionEvent.RemoteChanged)
                }
                if (ok.ok) ServiceResult.done(request.id, ok.message)
                else ServiceResult.fail(request.id, ok.code, ok.message)
            }
            RemoteControl.OP_DRIVE, RemoteControl.OP_WATCH -> {
                if (!remote.isActive(session.ifBlank { remote.sessionId })) {
                    ServiceResult.fail(request.id, ErrorCodes.DECLINED, RemoteControl.NEED_ALLOW)
                } else {
                    Notifier.beingControlled(context)
                    SessionHub.emit(SessionEvent.RemoteChanged)
                    ServiceResult.done(request.id, RemoteControl.ALLOWED)
                }
            }
            RemoteControl.OP_STOP -> {
                val stopped = remote.stop(session.ifBlank { null })
                Notifier.clearControlled(context)
                SessionHub.emit(SessionEvent.RemoteChanged)
                ServiceResult.done(request.id, stopped.message)
            }
            else -> ServiceResult.fail(request.id, ErrorCodes.NOT_IMPLEMENTED, "That remote control action is not available.")
        }
    }

    private fun ask(title: String, body: String): Boolean {
        val id = UUID.randomUUID().toString()
        // In-app consent dialog already covers the prompt; a HIGH heads-up on top blocks Send.
        if (!BackgroundListen.hasVisibleWindow()) {
            Notifier.consent(context, id, title, body)
        }
        SessionHub.emit(SessionEvent.Consent(id, title, body))
        val answer = ConsentBus.await(id, 180_000)
        Notifier.clear(context, id)
        return answer
    }

    private fun deviceName(): String = userSave.deviceName()

    private fun manualHost(): String? = userSave.manualHost()

    private fun copyText(text: String) {
        val manager = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(android.content.ClipData.newPlainText("FlipSend", text))
    }

    private fun connectPairing(host: String, port: Int, block: (SSLSocket) -> Unit) {
        if (!LocalAddressPolicy.isAllowedHost(host, false)) {
            throw ProtocolException("Refusing a non-local address.")
        }
        val raw = Socket()
        NetInfo.bindLan(raw, context)
        raw.connect(InetSocketAddress(host, port), 8000)
        val ssl = TlsPins.pairingClient(identity).socketFactory.createSocket(raw, host, port, true) as SSLSocket
        TlsPins.configure(ssl, true)
        ssl.startHandshake()
        ssl.use { block(it) }
    }

    private fun installed(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun publishReceived(senderName: String, id: String, name: String, kind: String, path: String) {
        val snap = ReceiveLive.edit {
            noteSender(senderName)
            noteFile(id, name, kind, path)
            noteIdle()
        }
        SessionHub.emit(SessionEvent.Receive(snap))
    }

    /**
     * Incoming files from the paired PC are already accepted. When auto-install is on,
     * APKs are installed in place with no FlipSend notification. A file that did not
     * come from the paired PC is only saved.
     */
    private fun offerReceivedApk(saved: File, id: String, fromPairedPc: Boolean) {
        val readable = ReceiveApk.remember(context, id, saved)
        if (fromPairedPc && InstallPrefs.auto(context)) {
            PcAutoInstall.onArrived(context, id)
            SessionHub.emit(SessionEvent.ReceivedChanged)
            return
        }
        if (readable) {
            ReceivedLog(context).mark(id, "saved", "Saved. Install it from the receiving screen.")
        }
        SessionHub.emit(SessionEvent.ReceivedChanged)
    }

    private fun note(message: String) {
        android.util.Log.i("StellarSend", LogRedaction.redact(message))
    }

    private fun desktopLine(rest: String) = "${RemoteControl.LABEL} $rest"

    private fun desktopNote(rest: String) = note(desktopLine(rest))

    private val notices = org.stellartimelock.send.core.NoticeGate()

    private fun dropped(kind: String, ex: Exception? = null) {
        val peer = peers.load()
        if (peer != null && ex != null && noteKeyChange(ex, peer) != null) {
            pathLabel = ""
            return
        }
        val text = ConnectionDrop.line(kind, ex?.message)
        if (!notices.allow(text, System.currentTimeMillis())) return
        lastError = text
        note(text)
        if (ex != null) note(ex.javaClass.simpleName + ": " + (ex.message ?: "closed"))
        SessionHub.emit(SessionEvent.Notice(text))
        SessionHub.emit(SessionEvent.Changed)
    }

    private fun sweepLeftovers() {
        val prefs = context.getSharedPreferences("stellarsend", Context.MODE_PRIVATE)
        fillMissingApkNames()
        val pruned = receipts.sweepOlderCopies()
        for (old in pruned) runCatching { receipts.file(old).delete() }
        if (!prefs.getBoolean("apk_leftover_sweep_1_5_14", false)) {
            receipts.sweepInboxFiles { file ->
                val info = runCatching { ApkArchives.read(context, file) }.getOrNull()
                info?.let { it.packageName to it.versionCode }
            }
            prefs.edit().putBoolean("apk_leftover_sweep_1_5_14", true).apply()
        }
        if (pruned.isNotEmpty()) SessionHub.emit(SessionEvent.ReceivedChanged)
    }

    private fun fillMissingApkNames() {
        for (entry in receipts.list()) {
            if (!org.stellartimelock.send.core.ApkBatch.isApk(entry.kind, entry.name)) continue
            if (entry.packageName.isNotBlank()) continue
            val file = receipts.file(entry)
            if (!file.isFile) continue
            val incoming = runCatching { ApkArchives.read(context, file) }.getOrNull() ?: continue
            val installed = runCatching { ApkArchives.installed(context, incoming.packageName) }.getOrNull()
            receipts.noteApp(
                entry.id,
                appName = incoming.label.ifBlank { incoming.packageName },
                packageName = incoming.packageName,
                incomingVersion = incoming.versionName.orEmpty(),
                incomingCode = incoming.versionCode,
                installedVersion = installed?.versionName.orEmpty(),
                installedCode = installed?.versionCode ?: -1,
                hasInstalled = installed != null,
                signatureMatch = ""
            )
        }
    }

    private fun planAnnouncement(): RelayAnnouncement = PlanStatus.announcement(context)

    private fun relayAuth() = PlanStatus.auth(context)

    private fun showWaiting() {
        if (peers.load() != null && listening) {
            if (PairStatus.isWakeText(lastError)) lastError = null
            if (pathLabel.isBlank() || PairStatus.isWakeText(pathLabel)) {
                pathLabel = Reachability.DIRECT.also { directMissed = false }
                SessionHub.emit(SessionEvent.Changed)
            }
            return
        }
        if (pathLabel == RelayWake.STATUS) return
        pathLabel = RelayWake.STATUS
        SessionHub.emit(SessionEvent.Changed)
    }

    private fun noteReady(reply: RelayReply) {
        PlanStatus.noteUsage(context, reply)
        if (PairStatus.isWakeText(lastError)) lastError = null
        pathLabel = Reachability.RELAY
        SessionHub.emit(SessionEvent.Changed)
    }

    private fun noteQuota(message: String?) {
        val text = message?.ifBlank { null } ?: RelayPlans.QUOTA_MESSAGE
        lastError = text
        SessionHub.emit(SessionEvent.Upgrade(text))
        SessionHub.emit(SessionEvent.Changed)
    }

    private fun takeQuota(): String? = RelayQuotaBus.message.getAndSet(null)

    private fun absorbQuota(outcome: TransferOutcome): TransferOutcome {
        val message = takeQuota() ?: return outcome
        noteQuota(message)
        return fail(RelayPlans.QUOTA, message)
    }

    private fun pauseForQuota(message: String): Boolean {
        if (!quotaAnnounced) {
            quotaAnnounced = true
            noteQuota(message)
        } else {
            lastError = message
        }
        return sleepOrStop(30_000)
    }

    private fun sleepOrStop(ms: Long): Boolean {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            return false
        }
        return !stop.get()
    }

    private fun fail(code: String, message: String) =
        TransferOutcome(TransferState.FAILED, code, message, null, null, 0)

    private fun remembered(peer: StoredPeer): StoredPeer =
        StoredPeer.from(org.stellartimelock.send.core.PeerTrust.merge(peers.load()?.snapshot(), peer.snapshot()))

    private fun commitScanned(peer: StoredPeer) {
        val existing = peers.load()
        val scanned = StoredPeer.from(org.stellartimelock.send.core.PeerTrust.scan(existing?.snapshot(), peer.snapshot()))
            .copy(acceptNext = false)
        note(
            "Scan pin before=${org.stellartimelock.send.core.TrustNewKey.shortPin(existing?.fingerprint)} " +
                "after=${org.stellartimelock.send.core.TrustNewKey.shortPin(scanned.fingerprint)} " +
                "file=${peers.location.absolutePath}"
        )
        pendingTrust = null
        acceptNextPin = false
        justTrusted = false
        lastError = null
        if (repair == null) {
            peers.savePinned(scanned)
            pairingUntil = 0
            val reloaded = peers.reload()
            note("Scan pin reloaded=${org.stellartimelock.send.core.TrustNewKey.shortPin(reloaded?.fingerprint)}")
            return
        }
        try {
            PairingCommit.commit(identities.location, identities.seal(identity), peers.location, peers.seal(scanned))
        } catch (ex: Exception) {
            lastError = "Re-pair did not finish. The previous pairing is unchanged."
            SessionHub.emit(SessionEvent.Changed)
            throw ex
        }
        repair = null
        pairingUntil = 0
        note("Paired.")
        SessionHub.emit(SessionEvent.Changed)
    }

    /**
     * Forget deletes peer.bin. A connection or companion call that started before Forget still holds the old
     * peer and used to write it straight back on success, so Forget never stuck. Only a scan/pairing
     * (commitScanned or a re-pair commit) may create peer.bin again.
     */
    private fun forgotten(peer: StoredPeer): Boolean {
        if (peers.load() != null) return false
        note("Not re-saving ${peer.name}: it was forgotten. Pair again to reconnect.")
        return true
    }

    private fun rememberTrusted(peer: StoredPeer) {
        peers.save(remembered(peer))
    }

    private fun touchPeer(peer: StoredPeer, confirmedHandshake: Boolean) {
        if (forgotten(peer)) return
        val merged = remembered(peer)
        if (confirmedHandshake && repair != null) {
            commitPeer(merged)
            return
        }
        if (repair != null) return
        rememberTrusted(merged)
    }

    private fun commitPeer(peer: StoredPeer) {
        if (repair == null && forgotten(peer)) return
        val merged = remembered(peer)
        if (repair == null) {
            peers.save(merged)
            pairingUntil = 0
            return
        }
        try {
            PairingCommit.commit(identities.location, identities.seal(identity), peers.location, peers.seal(merged))
        } catch (ex: Exception) {
            lastError = "Re-pair did not finish. The previous pairing is unchanged."
            SessionHub.emit(SessionEvent.Changed)
            throw ex
        }
        repair = null
        pairingUntil = 0
        note("Paired.")
        SessionHub.emit(SessionEvent.Changed)
    }

    private fun repairOpen(): Boolean {
        val session = repair ?: return false
        if (System.currentTimeMillis() <= session.until) return true
        rollbackRepair()
        return false
    }

    private fun rollbackRepair(silent: Boolean = false) {
        val session = repair ?: return
        identity = session.previous
        repair = null
        pairingUntil = 0
        if (!silent) {
            lastError = "Re-pair timed out. The previous pairing is unchanged."
            note(lastError ?: "Re-pair timed out.")
            SessionHub.emit(SessionEvent.Changed)
        }
    }

    private fun unrecognizedOutcome(error: Throwable): TransferOutcome? {
        val code = RepairPrompt.codeOf(error) ?: return null
        if (code == ErrorCodes.KEY_CHANGED) return null
        lastError = RepairPrompt.MESSAGE
        SessionHub.emit(SessionEvent.Changed)
        return fail(code, RepairPrompt.MESSAGE)
    }

    private fun unrecognizedResult(id: String, error: Throwable): ServiceResult? {
        val code = RepairPrompt.codeOf(error) ?: return null
        if (code == ErrorCodes.KEY_CHANGED) return null
        lastError = RepairPrompt.MESSAGE
        SessionHub.emit(SessionEvent.Changed)
        return ServiceResult.fail(id, code, RepairPrompt.MESSAGE)
    }

    private fun noteKeyChange(error: Throwable, peer: StoredPeer): ServiceResult? {
        val changed = KeyChangedException.of(error)
        val fingerprint = changed?.presentedFingerprint?.takeIf { it.isNotBlank() } ?: lastSeenPin
        val publicKey = changed?.presentedPublicKey?.takeIf { it.isNotBlank() } ?: lastSeenKey
        if (fingerprint.isNotBlank()) {
            lastSeenPin = fingerprint
            if (publicKey.isNotBlank()) lastSeenKey = publicKey
        }
        if (trustAlreadySaved(error, peer)) {
            return null
        }
        if (!TrustNewKey.applies(true, RepairPrompt.codeOf(error), error.message) &&
            changed == null &&
            !TrustNewKey.looksLikeMismatch(error)
        ) {
            return null
        }
        val live = peers.load() ?: peer
        if (!TrustNewKey.shouldPrompt(true, RepairPrompt.codeOf(error), error.message, fingerprint, live.fingerprint) ||
            !TrustNewKey.shouldOffer(fingerprint, live.fingerprint, identity.fingerprint)
        ) {
            note(
                "Trust skipped. presented=${org.stellartimelock.send.core.TrustNewKey.shortPin(fingerprint)} " +
                    "stored=${org.stellartimelock.send.core.TrustNewKey.shortPin(live.fingerprint)}"
            )
            return null
        }
        pendingTrust = org.stellartimelock.send.core.TrustOffer(
            live.name,
            live.deviceId,
            fingerprint,
            publicKey
        )
        lastError = TrustNewKey.message(live.name)
        pathLabel = ""
        SessionHub.emit(SessionEvent.Changed)
        return ServiceResult.fail("", ErrorCodes.KEY_CHANGED, TrustNewKey.message(live.name))
    }

    private fun trustAlreadySaved(error: Throwable, peer: StoredPeer): Boolean {
        val live = peers.load() ?: peer
        val presented = KeyChangedException.of(error)?.presentedFingerprint?.takeIf { it.isNotBlank() } ?: lastSeenPin
        if (presented.isNotBlank() && presented.equals(live.fingerprint, ignoreCase = true)) {
            note(
                "Trust pin ${org.stellartimelock.send.core.TrustNewKey.shortPin(live.fingerprint)} already saved. " +
                    "Not asking again."
            )
            return true
        }
        if ((justTrusted || live.acceptNext || acceptNextPin) && presented.isBlank()) {
            note(
                "Handshake closed after Trust. Saved pin " +
                    "${org.stellartimelock.send.core.TrustNewKey.shortPin(live.fingerprint)} still on disk."
            )
            return true
        }
        return false
    }

    private fun stashSeenFromSsl(ssl: SSLSocket?, peer: StoredPeer?) {
        val cert = ssl?.let { socket ->
            runCatching { socket.session.peerCertificates.first() as java.security.cert.X509Certificate }.getOrNull()
        }
        if (cert == null) {
            // No cert from this handshake. A pin from an earlier connection
            // is not a presented pin, so it can never raise Trust new key.
            lastSeenPin = ""
            lastSeenKey = ""
            return
        }
        lastSeenPin = sha256Hex(cert.publicKey.encoded)
        lastSeenKey = base64UrlEncode(cert.publicKey.encoded)
        if (peer != null) {
            note(
                "Handshake closed. seen=${org.stellartimelock.send.core.TrustNewKey.shortPin(lastSeenPin)} " +
                    "stored=${org.stellartimelock.send.core.TrustNewKey.shortPin(peer.fingerprint)}"
            )
        }
    }

    private fun adbPath(file: File): String {
        val path = file.absolutePath.replace('\\', '/')
        val marker = "/Android/data/"
        val index = path.indexOf(marker)
        return if (index >= 0) "/sdcard$marker${path.substring(index + marker.length)}" else path
    }

    private fun isThrowaway(socket: Socket): Boolean {
        val input = socket.getInputStream()
        val start = System.nanoTime()
        while (System.nanoTime() - start < 400_000_000L) {
            if (input.available() > 0) return false
            if (socket.isClosed) return true
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                return true
            }
        }
        return input.available() == 0
    }
}

internal data class Prepared(
    val kind: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val open: () -> java.io.InputStream
)
