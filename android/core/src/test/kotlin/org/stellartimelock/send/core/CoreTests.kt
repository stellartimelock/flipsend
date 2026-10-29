package org.stellartimelock.send.core

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import java.security.cert.X509Certificate

class ProtocolTests {
    @Test
    fun vectorsMatchTheSharedFixtures() {
        val root = repoRoot()
        val json = JSONObject(File(root, "testdata/protocol-vectors.json").readText())
        assertEquals(1, json.getInt("version"))
        assertEquals(262144, json.getInt("maxFramePayload"))
        assertEquals(65536, json.getInt("maxChunkData"))
        val frames = json.getJSONArray("frames")
        for (i in 0 until frames.length()) {
            val frame = frames.getJSONObject(i)
            val payload = hex(frame.getString("payloadHex"))
            val encoded = FrameCodec.encode(MessageType.from(frame.getInt("type")), payload, frame.getInt("flags"))
            assertArrayEquals(hex(frame.getString("frameHex")), encoded, frame.getString("name"))
            val parsed = FrameCodec.read(ByteArrayInputStream(encoded))
            assertEquals(frame.getInt("type"), parsed.type.code)
            assertArrayEquals(payload, parsed.payload)
        }
        val chunk = json.getJSONArray("chunks").getJSONObject(0)
        val encodedChunk = ChunkCodec.encode(UUID.fromString(chunk.getString("transferId")), chunk.getLong("offset"), hex(chunk.getString("dataHex")))
        assertArrayEquals(hex(chunk.getString("payloadHex")), encodedChunk)
        val decoded = ChunkCodec.decode(encodedChunk)
        assertEquals(65536L, decoded.offset)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.data)
    }

    @Test
    fun oversizedFrameAndChunkAreRejected() {
        assertThrows(ProtocolException::class.java) {
            FrameCodec.encode(MessageType.CHUNK, ByteArray(ProtocolLimits.MAX_FRAME_PAYLOAD + 1))
        }
        assertThrows(ProtocolException::class.java) {
            ChunkCodec.encode(UUID.randomUUID(), 0, ByteArray(ProtocolLimits.MAX_CHUNK_DATA + 1))
        }
        val badFlags = FrameCodec.encode(MessageType.HELLO, byteArrayOf(1)).also { it[6] = 1 }
        assertThrows(ProtocolException::class.java) { FrameCodec.read(ByteArrayInputStream(badFlags)) }
    }

    @Test
    fun deviceCertsCarryClientAndServerAuthAndUpgradeKeepsThePin() {
        val fresh = Identities.generate(name = "Phone")
        assertTrue(Identities.hasDeviceUsages(fresh.certificate))
        assertEquals(fresh.fingerprint, Identities.ensureDeviceUsages(fresh).fingerprint)

        val bare = Identities.generateWithoutUsages(name = "OldPhone")
        assertFalse(Identities.hasDeviceUsages(bare.certificate))
        val upgraded = Identities.ensureDeviceUsages(bare)
        assertTrue(Identities.hasDeviceUsages(upgraded.certificate))
        assertEquals(bare.fingerprint, upgraded.fingerprint)
        assertEquals(bare.deviceId, upgraded.deviceId)
        assertFalse(upgraded.certificateDer.contentEquals(bare.certificateDer))
    }

    @Test
    fun pinCompareIsExactAndLowercase() {
        val pin = "ab".repeat(32)
        assertTrue(pinsEqual(pin, pin))
        assertFalse(pinsEqual(pin, pin.uppercase()))
        assertFalse(pinsEqual(pin, pin.dropLast(1) + "c"))
        assertFalse(pinsEqual(pin, "zz"))
        assertFalse(isFingerprint(null))
    }

    @Test
    fun beaconDropsSecretsAndRoundTrips() {
        val id = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val encoded = BeaconCodec.encode(id, 53321, "Phone")
        val parsed = BeaconCodec.tryParse(encoded)
        assertNotNull(parsed)
        assertEquals(id.toString(), parsed!!.getString("id"))
        assertNull(BeaconCodec.tryParse("""{"v":1,"app":"stellarsend","pk":"nope","id":"$id","port":1,"name":"A"}""".toByteArray()))
        assertNull(BeaconCodec.tryParse("""{"v":1,"app":"other","id":"$id","port":1,"name":"A"}""".toByteArray()))
    }
}

class PairingTests {
    @Test
    fun fixtureQrCarriesOnlyPublicMaterial() {
        val json = JSONObject(File(repoRoot(), "testdata/pairing.json").readText())
        val advert = PairingCodec.parse(json.getString("qr"))
        assertEquals(json.getString("deviceId"), advert.deviceId.toString())
        assertEquals(json.getString("fp"), advert.fingerprint)
        assertEquals(json.getString("pk"), advert.publicKeySpkiBase64Url)
        assertEquals("192.168.43.1", advert.host)
        assertEquals(53321, advert.port)
        assertEquals(json.getString("qr"), PairingCodec.toQr(advert))
        assertFalse(json.getString("qr").contains("PRIVATE", true))
        assertFalse(advert.publicKeySpkiBase64Url.contains("secret", true))
    }

    @Test
    fun privateKeysPublicAddressesAndForbiddenFieldsAreRejected() {
        val json = JSONObject(File(repoRoot(), "testdata/pairing.json").readText())
        val qr = json.getString("qr")
        assertThrows(ProtocolException::class.java) {
            PairingCodec.parse(qr.replace("name=Phone", "name=Phone&secret=no"))
        }
        assertThrows(ProtocolException::class.java) {
            PairingCodec.parse(qr.replace("host=192.168.43.1", "host=8.8.8.8"))
        }
        assertThrows(ProtocolException::class.java) {
            PairingCodec.parse(qr.replace("pk=${json.getString("pk")}", "pk=-----BEGIN%20PRIVATE%20KEY-----"))
        }
    }

    @Test
    fun qrKeepsWifiThenVpnAndDropsLinkLocalAndPublic() {
        val base = PairingCodec.parse(JSONObject(File(repoRoot(), "testdata/pairing.json").readText()).getString("qr"))
        val advert = base.copy(
            host = "10.201.100.159",
            hosts = listOf("10.201.100.159", "10.8.0.2", "169.254.1.1", "8.8.8.8")
        )
        val qr = PairingCodec.toQr(advert)
        val parsed = PairingCodec.parse(qr)
        assertEquals("10.201.100.159", parsed.host)
        assertEquals(listOf("10.201.100.159"), parsed.candidates())
        assertTrue(qr.contains("ips="))
        assertFalse(qr.contains("169.254.1.1"))
        assertFalse(qr.contains("8.8.8.8"))
    }
}

class AdapterAndRelayTests {
    @Test
    fun rankPrefersWifiAndKeepsVpnBehindVirtualAdapters() {
        val ranked = AdapterRank.rank(listOf(
            AdapterSnapshot("vEthernet (WSL)", "172.19.224.1", up = true, hasGateway = true, medium = AdapterMedium.ETHERNET, virtual = true),
            AdapterSnapshot("Surfshark WireGuard", "10.8.0.2", up = true, hasGateway = true, medium = AdapterMedium.VPN, virtual = false),
            AdapterSnapshot("Wi-Fi", "10.201.100.159", up = true, hasGateway = true, medium = AdapterMedium.WIFI, virtual = false),
            AdapterSnapshot("Ethernet", "169.254.8.1", up = true, hasGateway = false, medium = AdapterMedium.ETHERNET, virtual = false),
            AdapterSnapshot("Ethernet", "192.168.0.20", up = false, hasGateway = true, medium = AdapterMedium.ETHERNET, virtual = false),
            AdapterSnapshot("Ethernet 2", "10.0.0.4", up = true, hasGateway = false, medium = AdapterMedium.ETHERNET, virtual = false)
        ))
        assertEquals(listOf("10.201.100.159", "10.0.0.4"), ranked)
        assertTrue(AdapterRank.isVirtual("vEthernet (WSL) Hyper-V"))
        assertTrue(AdapterRank.isVpnName("OpenVPN TAP-Windows Adapter"))
        assertTrue(AdapterRank.isTunName("tun1"))
        assertTrue(AdapterRank.isVpnRange("10.14.0.2"))
        assertTrue(AdapterRank.isVpnRange("10.8.0.2"))
        assertFalse(AdapterRank.isLanCandidate("10.14.0.2"))
        assertEquals("A VPN is on. Using the relay.", VpnStatus.LINE)
        assertTrue(VpnStatus.line(true, false, true)!!.contains("bypass list"))
        assertNull(VpnStatus.line(false, true, false))
        assertTrue(RelaySelf.sameDevice("3f84d8c1-1111-4111-8111-111111111111", "3F84D8C1-1111-4111-8111-111111111111"))
        assertFalse(RelaySelf.sameDevice(null, "3f84d8c1-1111-4111-8111-111111111111"))
    }

    @Test
    fun relayRoomHidesDeviceIdsAndMatchesAKnownHash() {
        val a = "22222222-2222-2222-2222-222222222222"
        val b = "11111111-1111-1111-1111-111111111111"
        val room = RelayRoom.paired(a, b)
        assertEquals(RelayRoom.paired(b, a), room)
        assertEquals("8d691f26b6b708eb4b0a058b4ab26c3550f9b2006086aba986f692df67957a74", room)
        assertFalse(room.contains(a))
        assertEquals("1b0b8e985e76c507f6a56dd97199f4a92ea67e8b78479e84df7e13d23e96e186", RelayRoom.pairing(b))
        val endpoint = RelayAddress.parse("https://relay.stellartimelock.com/v1")
        assertNotNull(endpoint)
        assertTrue(endpoint!!.secure)
        assertEquals(443, endpoint.port)
        assertEquals("/v1", endpoint.path)
        assertNull(RelayAddress.parse("http://8.8.8.8/v1"))
        assertTrue(Reachability.PC.contains("allow local network"))
        assertFalse(Reachability.PC.contains("VPN is off"))
        assertEquals("Direct on Wi-Fi", Reachability.DIRECT)
        assertEquals("Through relay", Reachability.RELAY)
    }
}

class DiscoveryPlanTests {
    @Test
    fun homeNetworkDoesNotScanTheSubnet() {
        val plan = DiscoveryPlanner.plan(DiscoveryRequest("192.168.1.20", null, "192.168.1.1", "192.168.1.5", 53321))
        assertEquals(listOf("192.168.1.20"), plan.probes.map { it.ip })
        assertEquals(ProbeReason.LAST_KNOWN, plan.probes[0].reason)
        assertTrue(plan.probes.none { it.reason == ProbeReason.HOTSPOT_RANGE })
        assertTrue(plan.useMdns)
        assertTrue(plan.useBroadcast)
        assertEquals(4000, plan.totalBudgetMs)
    }

    @Test
    fun hotspotAddsGatewayAndABoundedRange() {
        val plan = DiscoveryPlanner.plan(DiscoveryRequest(null, null, "192.168.43.1", "192.168.43.8", 53321))
        assertEquals(ProbeReason.HOTSPOT_GATEWAY, plan.probes[0].reason)
        assertEquals("192.168.43.1", plan.probes[0].ip)
        val range = plan.probes.filter { it.reason == ProbeReason.HOTSPOT_RANGE }.map { it.ip }
        assertEquals(DiscoveryPlanner.MAX_RANGE_PROBES, range.size)
        assertTrue(range.all { it.startsWith("192.168.43.") })
        assertFalse(range.contains("192.168.43.1"))
        assertFalse(range.contains("192.168.43.0"))
        assertFalse(range.contains("192.168.43.255"))
        assertFalse(range.contains("192.168.43.8"))
    }

    @Test
    fun hotspotKeepsTheLastKnownHostOctetInsideTheCap() {
        val plan = DiscoveryPlanner.plan(DiscoveryRequest("192.168.1.50", null, "192.168.137.1", null, 53321))
        assertTrue(plan.probes.any { it.ip == "192.168.1.50" && it.reason == ProbeReason.LAST_KNOWN })
        assertTrue(plan.probes.any { it.ip == "192.168.137.1" && it.reason == ProbeReason.HOTSPOT_GATEWAY })
        assertTrue(plan.probes.any { it.ip == "192.168.137.50" && it.reason == ProbeReason.HOTSPOT_RANGE })
        assertEquals(DiscoveryPlanner.MAX_RANGE_PROBES, plan.probes.count { it.reason == ProbeReason.HOTSPOT_RANGE })
    }

    @Test
    fun publicAndDuplicateAddressesAreNotProbed() {
        val empty = DiscoveryPlanner.plan(DiscoveryRequest("8.8.8.8", "8.8.8.8", "192.168.1.1", null, 53321))
        assertTrue(empty.probes.isEmpty())
        val manual = DiscoveryPlanner.plan(DiscoveryRequest("10.0.0.5", "10.0.0.5", null, "10.0.0.9", 53321))
        assertEquals(1, manual.probes.size)
        assertEquals(ProbeReason.MANUAL, manual.probes[0].reason)
    }

    @Test
    fun failureMessageMentionsHotspotFallback() {
        val plan = DiscoveryPlanner.plan(DiscoveryRequest("192.168.43.10", null, "192.168.43.1", null, 53321))
        val message = DiscoveryPlanner.describeFailure(plan)
        assertTrue(message.contains("mDNS"))
        assertTrue(message.contains("hotspot", true))
        assertTrue(message.contains("USB"))
        assertTrue(message.contains("allow local network"))
        assertFalse(message.contains("VPN is off"))
        assertFalse(message.contains("turn the VPN off"))
    }

    @Test
    fun onlyLocalAddressesAreAllowed() {
        assertTrue(LocalAddressPolicy.isAllowedHost("10.1.2.3", false))
        assertTrue(LocalAddressPolicy.isAllowedHost("192.168.43.1", false))
        assertTrue(LocalAddressPolicy.isAllowedHost("172.16.0.1", false))
        assertFalse(LocalAddressPolicy.isAllowedHost("8.8.8.8", false))
        assertFalse(LocalAddressPolicy.isAllowedHost("127.0.0.1", false))
        assertTrue(LocalAddressPolicy.isAllowedHost("127.0.0.1", true))
        assertFalse(LocalAddressPolicy.isAllowedHost("0.0.0.1", false))
    }
}

class MdnsTests {
    @Test
    fun announcementRoundTripsAndQueryIsRecognized() {
        val id = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val packet = MdnsPackets.buildAnnouncement(id, 53321, InetAddress.getByName("192.168.43.12"))
        val parsed = MdnsPackets.tryParseAnnouncement(packet)
        assertNotNull(parsed)
        assertEquals(id, parsed!!.deviceId)
        assertEquals(53321, parsed.port)
        assertEquals("192.168.43.12", parsed.address)
        val query = MdnsPackets.buildQuery()
        assertTrue(MdnsPackets.isQueryForUs(query))
        assertFalse(MdnsPackets.isQueryForUs(packet))
    }
}

class SafetyTests {
    @Test
    fun filenamesDropTraversalAndReservedNames() {
        assertEquals("note.txt", FileNames.sanitize("../secret/note.txt"))
        assertEquals("file", FileNames.sanitize(".."))
        assertEquals("_CON.txt", FileNames.sanitize("CON.txt"))
        assertEquals("a_b.txt", FileNames.sanitize("a:b.txt"))
        assertEquals("file", FileNames.sanitize("   "))
        val dir = java.nio.file.Files.createTempDirectory("names").toFile()
        val first = FileNames.allocate(dir, "note.txt")
        first.writeText("a")
        val second = FileNames.allocate(dir, "note.txt")
        assertEquals("note (1).txt", second.name)
    }

    @Test
    fun chooserHidesMethodsThatDoNotApply() {
        val fileMethods = SendMethodRegistry.available(PayloadKind.FILE, setOf(SendMethodRegistry.GMAIL_PACKAGE), smsAvailable = true)
        assertEquals(
            listOf("To my PC (Wi-Fi)", "USB", "Gmail", "Text message", "Share sheet"),
            fileMethods.map { it.title }
        )
        assertTrue(fileMethods.none { it.id == MethodIds.MEMO })
        val text = SendMethodRegistry.available(PayloadKind.TEXT, setOf(SendMethodRegistry.TEXTPIPE_PACKAGE), smsAvailable = false)
        assertEquals(
            listOf("To my PC (Wi-Fi)", "USB", "Stellar memo (text only)", "AI Text Manager", "Share sheet"),
            text.map { it.title }
        )
        assertTrue(text.none { it.id == MethodIds.GMAIL || it.id == MethodIds.SMS })
    }

    @Test
    fun memoStubRejectsFilesAndDoesNoWork() {
        assertTrue(StellarMemoStub().sendText("hello") is MemoResult.NotImplemented)
        assertTrue(StellarMemoStub().sendText("") is MemoResult.Rejected)
        assertTrue(memoForFile() is MemoResult.Rejected)
        assertTrue(MEMO_NOT_IMPLEMENTED.contains("not available"))
        assertFalse(MEMO_NOT_IMPLEMENTED.contains("time-lock submitted", true))
    }

    @Test
    fun shareTargetsUseTheRequestedPackages() {
        val gmail = ShareRequests.gmail(file = true)
        assertEquals("android.intent.action.SEND", gmail.action)
        assertEquals("com.google.android.gm", gmail.packageName)
        assertFalse(gmail.chooser)
        assertTrue(gmail.grantRead)
        val sheet = ShareRequests.shareSheet(file = false)
        assertTrue(sheet.chooser)
        assertNull(sheet.packageName)
        assertEquals("app.textpipe", ShareRequests.textPipe(false).packageName)
    }

    @Test
    fun retryRunsUpToFiveTimesForATransientMiss() {
        assertTrue(RetryPolicy.shouldRetry(remoteCompleted = false, cancelled = false, attempt = 1, transientFailure = true))
        assertTrue(RetryPolicy.shouldRetry(remoteCompleted = false, cancelled = false, attempt = 4, transientFailure = true))
        assertFalse(RetryPolicy.shouldRetry(remoteCompleted = false, cancelled = false, attempt = RetryPolicy.MAX_ATTEMPTS, transientFailure = true))
        assertFalse(RetryPolicy.shouldRetry(remoteCompleted = true, cancelled = false, attempt = 1, transientFailure = true))
        assertFalse(RetryPolicy.shouldRetry(remoteCompleted = false, cancelled = true, attempt = 1, transientFailure = true))
        assertFalse(RetryPolicy.shouldRetry(remoteCompleted = false, cancelled = false, attempt = 1, transientFailure = false))
        assertEquals(400L, RetryPolicy.delayMs(1))
        assertEquals(800L, RetryPolicy.delayMs(2))
    }

    @Test
    fun manifestRegistersConsentReceiver() {
        val manifest = File(repoRoot(), "android/stellarsend/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".ConsentReceiver\""))
    }

    @Test
    fun logsDropPairingPayloads() {
        assertEquals("[redacted]", LogRedaction.redact("code stellarsend://pair?pk=abc"))
        assertEquals("[redacted]", LogRedaction.redact("BEGIN PRIVATE KEY"))
        assertEquals("[redacted]", LogRedaction.redact("{\"password\":\"secret\"}"))
        assertEquals("Copied to the PC clipboard. It will clear in 45 seconds.", LogRedaction.redact("Copied to the PC clipboard. It will clear in 45 seconds."))
        assertEquals("Listening", LogRedaction.redact("Listening"))
    }
}

class TransferTests {
    @Test
    fun pinnedTlsMovesTextAndAFile() {
        val phone = Identities.generate(name = "Phone")
        val desk = Identities.generate(name = "Desk")
        val payload = "hello from the phone".toByteArray()
        val received = exchange(phone, desk, "text", "note", "text/plain", payload, confirm = { _, _ -> true })
        assertEquals(TransferState.SUCCEEDED, received.sent.state)
        assertEquals("hello from the phone", received.outcome.text)
        val fileBytes = ByteArray(70_000) { (it % 251).toByte() }
        val file = exchange(phone, desk, "file", "wide.bin", "application/octet-stream", fileBytes, confirm = { _, _ -> true })
        assertEquals(TransferState.SUCCEEDED, file.sent.state)
        assertTrue(file.sent.chunks >= 2)
        assertArrayEquals(fileBytes, File(file.outcome.savedPath!!).readBytes())
        assertFalse(File(file.outcome.savedPath + ".partial").exists())
    }

    @Test
    fun pinnedTlsMovesTextAndAFileFromThePcToo() {
        val phone = Identities.generate(name = "Phone")
        val desk = Identities.generate(name = "Desk")
        val payload = "hello from the pc".toByteArray()
        val received = exchange(desk, phone, "text", "note", "text/plain", payload, confirm = { _, _ -> true })
        assertEquals(TransferState.SUCCEEDED, received.sent.state)
        assertEquals("hello from the pc", received.outcome.text)
        val fileBytes = ByteArray(32_000) { (it % 199).toByte() }
        val file = exchange(desk, phone, "file", "from-pc.bin", "application/octet-stream", fileBytes, confirm = { _, _ -> true })
        assertEquals(TransferState.SUCCEEDED, file.sent.state)
        assertArrayEquals(fileBytes, File(file.outcome.savedPath!!).readBytes())
    }

    @Test
    fun handshakeDoesNotClaimKeyChangedWhenTheTlsPinHasNotBeenRead() {
        val phone = Identities.generate(name = "Phone")
        val desk = Identities.generate(name = "Desk")
        val hello = JSONObject()
            .put("v", 1)
            .put("deviceId", phone.deviceId)
            .put("name", "Phone")
            .put("fingerprint", phone.fingerprint)
        val input = ByteArrayInputStream(FrameCodec.encode(MessageType.HELLO, hello.toString().toByteArray()))
        val output = java.io.ByteArrayOutputStream()
        val error = assertThrows(ProtocolException::class.java) {
            TransferSession.handshake(
                output, input, desk, phone.fingerprint, phone.deviceId, "",
                allowNew = false, confirmPeer = null
            )
        }
        assertTrue(error.code != ErrorCodes.KEY_CHANGED)
        assertFalse(error.message?.contains("key changed", ignoreCase = true) == true)
        val frames = ByteArrayInputStream(output.toByteArray())
        assertEquals(MessageType.HELLO, FrameCodec.read(frames).type)
        val reply = FrameCodec.read(frames)
        assertEquals(MessageType.ERROR, reply.type)
        val json = JSONObject(reply.payload.toString(Charsets.UTF_8))
        assertEquals(ErrorCodes.BAD_REQUEST, json.optString("code"))
        assertTrue(json.optString("message").contains("hello", ignoreCase = true))
    }

    @Test
    fun cancelRemovesThePartialFile() {
        val phone = Identities.generate(name = "Phone")
        val desk = Identities.generate(name = "Desk")
        val result = exchange(phone, desk, "file", "big.bin", "application/octet-stream", ByteArray(80_000), confirm = { _, _ -> true }, cancelImmediately = true)
        assertEquals(TransferState.CANCELLED, result.sent.state)
        assertEquals(TransferState.CANCELLED, result.outcome.state)
        assertTrue(result.outcome.savedPath == null || !File(result.outcome.savedPath).exists())
    }

    @Test
    fun sinkFailureRejectsInsteadOfDroppingTheSocket() {
        val phone = Identities.generate(name = "Phone")
        val desk = Identities.generate(name = "Desk")
        val result = exchange(
            phone,
            desk,
            "file",
            "wide.bin",
            "application/octet-stream",
            ByteArray(1200) { it.toByte() },
            confirm = { _, _ -> true },
            openFile = { throw java.io.IOException("disk is read-only") }
        )
        assertEquals(TransferState.FAILED, result.sent.state)
        assertEquals(ErrorCodes.DECLINED, result.sent.code)
        assertEquals("disk is read-only", result.sent.message)
        assertEquals(TransferState.FAILED, result.outcome.state)
        assertEquals(ErrorCodes.IO, result.outcome.code)
        assertEquals("disk is read-only", result.outcome.message)
    }

    @Test
    fun declineAndUnknownPeerAndChangedKeyFailClearly() {
        val phone = Identities.generate(name = "Phone")
        val desk = Identities.generate(name = "Desk")
        val declined = exchange(phone, desk, "text", "note", "text/plain", "no".toByteArray(), confirm = { _, _ -> false })
        assertEquals(TransferState.FAILED, declined.sent.state)
        assertEquals(ErrorCodes.DECLINED, declined.sent.code)
        assertThrows(ProtocolException::class.java) {
            handshakeOnly(desk, phone, expectedFingerprint = null, allowNew = false, confirm = null)
        }
        val changed = assertThrows(ProtocolException::class.java) {
            handshakeOnly(desk, phone, expectedFingerprint = "b".repeat(64), allowNew = false, confirm = null)
        }
        assertTrue(changed.message!!.contains("key", true))
    }

    @Test
    fun tlsFinishesSoThePeerCertIsOnTheWireEvenWhenThePinIsWrong() {
        val phone = Identities.generate(name = "Phone")
        val desk = Identities.generate(name = "Desk")
        val server = TlsPins.server(phone, expectedFingerprint = "a".repeat(64), allowNew = false)
        val client = TlsPins.client(desk, "b".repeat(64), allowNew = false)
        ServerSocket(0).use { listen ->
            val seen = AtomicReference<String>()
            val failure = AtomicReference<Throwable>()
            val thread = Thread {
                try {
                    val accepted = listen.accept()
                    val ssl = server.socketFactory.createSocket(accepted, null, accepted.port, true) as SSLSocket
                    TlsPins.configure(ssl, false)
                    ssl.startHandshake()
                    val cert = ssl.session.peerCertificates[0] as X509Certificate
                    seen.set(org.stellartimelock.send.core.sha256Hex(cert.publicKey.encoded))
                    ssl.close()
                } catch (ex: Throwable) {
                    failure.set(ex)
                }
            }
            thread.start()
            val raw = Socket()
            raw.connect(InetSocketAddress("127.0.0.1", listen.localPort), 5000)
            val ssl = client.socketFactory.createSocket(raw, "127.0.0.1", listen.localPort, true) as SSLSocket
            TlsPins.configure(ssl, true)
            ssl.startHandshake()
            val cert = ssl.session.peerCertificates[0] as X509Certificate
            assertTrue(phone.fingerprint.equals(sha256Hex(cert.publicKey.encoded), ignoreCase = true))
            thread.join(10000)
            assertNull(failure.get())
            assertTrue(desk.fingerprint.equals(seen.get(), ignoreCase = true))
            ssl.close()
        }
    }

    @Test
    fun tlsAllowNewAcceptsANewPinWhenTheStoredPinIsStale() {
        val phone = Identities.generate(name = "Phone")
        val desk = Identities.generate(name = "Desk")
        val stale = "a".repeat(64)
        val server = TlsPins.server(phone, expectedFingerprint = stale, allowNew = true)
        val client = TlsPins.pairingClient(desk)
        ServerSocket(0).use { listen ->
            val failure = AtomicReference<Throwable>()
            val thread = Thread {
                try {
                    val accepted = listen.accept()
                    val ssl = server.socketFactory.createSocket(accepted, null, accepted.port, true) as SSLSocket
                    TlsPins.configure(ssl, false)
                    ssl.startHandshake()
                } catch (ex: Throwable) {
                    failure.set(ex)
                }
            }
            thread.start()
            val raw = Socket()
            raw.connect(InetSocketAddress("127.0.0.1", listen.localPort), 5000)
            val ssl = client.socketFactory.createSocket(raw, "127.0.0.1", listen.localPort, true) as SSLSocket
            TlsPins.configure(ssl, true)
            ssl.startHandshake()
            thread.join(10000)
            assertNull(failure.get())
            ssl.close()
        }
    }

    @Test
    fun tlsDeviceIdComesFromTheCertificateCn() {
        val id = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")
        val desk = Identities.generate(deviceId = id, name = "Desk")
        assertEquals(id.toString(), TlsPins.deviceId(desk.certificate))
    }
}

private data class Exchange(val sent: TransferOutcome, val outcome: TransferOutcome)

private fun exchange(
    sender: DeviceMaterial,
    receiver: DeviceMaterial,
    kind: String,
    name: String,
    mime: String,
    payload: ByteArray,
    confirm: (String, Long) -> Boolean,
    cancelImmediately: Boolean = false,
    openFile: ((String) -> File)? = null
): Exchange {
    val server = TlsPins.server(receiver, sender.fingerprint, allowNew = false)
    val client = TlsPins.client(sender, receiver.fingerprint)
    ServerSocket(0).use { listen ->
        val outcome = AtomicReference<TransferOutcome>()
        val failure = AtomicReference<Throwable>()
        val thread = Thread {
            try {
                val accepted = listen.accept()
                val ssl = server.socketFactory.createSocket(accepted, null, accepted.port, true) as SSLSocket
                TlsPins.configure(ssl, false)
                ssl.startHandshake()
                val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as X509Certificate).publicKey.encoded)
                val inbox = java.nio.file.Files.createTempDirectory("inbox").toFile()
                outcome.set(
                    TransferSession.receive(
                        ssl.outputStream,
                        ssl.inputStream,
                        receiver,
                        sender.fingerprint,
                        sender.deviceId,
                        remoteFp,
                        allowNew = false,
                        confirmPeer = null,
                        confirmOffer = confirm,
                        openFile = openFile ?: { FileNames.allocate(inbox, it) }
                    )
                )
                ssl.close()
            } catch (ex: Throwable) {
                failure.set(ex)
            }
        }
        thread.start()
        val raw = Socket()
        raw.connect(InetSocketAddress("127.0.0.1", listen.localPort), 5000)
        val ssl = client.socketFactory.createSocket(raw, "127.0.0.1", listen.localPort, true) as SSLSocket
        TlsPins.configure(ssl, true)
        ssl.startHandshake()
        val remoteFp = sha256Hex((ssl.session.peerCertificates[0] as X509Certificate).publicKey.encoded)
        val sent = TransferSession.send(
            ssl.outputStream,
            ssl.inputStream,
            sender,
            receiver.deviceId,
            receiver.fingerprint,
            remoteFp,
            kind,
            name,
            mime,
            payload.size.toLong(),
            sha256Hex(payload),
            ByteArrayInputStream(payload),
            cancelled = { cancelImmediately }
        )
        ssl.close()
        thread.join(15000)
        if (failure.get() != null && outcome.get() == null) throw failure.get()
        return Exchange(sent, outcome.get())
    }
}

private fun handshakeOnly(
    local: DeviceMaterial,
    remote: DeviceMaterial,
    expectedFingerprint: String?,
    allowNew: Boolean,
    confirm: ((String, String, String) -> Boolean)?
) {
    ServerSocket(0).use { listen ->
        val failure = AtomicReference<Throwable>()
        val thread = Thread {
            try {
                val accepted = listen.accept()
                accepted.getOutputStream()
                val input = accepted.getInputStream()
                TransferSession.handshake(
                    accepted.getOutputStream(),
                    input,
                    remote,
                    local.fingerprint,
                    local.deviceId,
                    local.fingerprint,
                    allowNew = false,
                    confirmPeer = null
                )
            } catch (ex: Throwable) {
                failure.set(ex)
            }
        }
        thread.start()
        val socket = Socket("127.0.0.1", listen.localPort)
        try {
            TransferSession.handshake(
                socket.getOutputStream(),
                socket.getInputStream(),
                local,
                expectedFingerprint,
                if (expectedFingerprint == null) null else remote.deviceId,
                remote.fingerprint,
                allowNew,
                confirm
            )
        } finally {
            socket.close()
            thread.join(10000)
        }
        if (failure.get() != null && expectedFingerprint == null) {
            // The listener may also fail once this side rejects it.
        }
    }
}

private fun repoRoot(): File {
    var dir = File(System.getProperty("user.dir"))
    while (true) {
        if (File(dir, "testdata/pairing.json").isFile) return dir
        dir = dir.parentFile ?: error("testdata not found from ${System.getProperty("user.dir")}")
    }
}

private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class CompanionTests {
    @Test
    fun serviceFramesUseTypesTenAndEleven() {
        val service = FrameCodec.encode(MessageType.SERVICE, "{}".toByteArray())
        val result = FrameCodec.encode(MessageType.SERVICE_RESULT, "{}".toByteArray())
        assertEquals(10, service[5].toInt())
        assertEquals(11, result[5].toInt())
        val frame = FrameCodec.encode(MessageType.SCREEN_FRAME, byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        assertEquals(13, frame[5].toInt())
        val input = FrameCodec.encode(MessageType.REMOTE_INPUT, "{}".toByteArray())
        assertEquals(14, input[5].toInt())
        val ack = FrameCodec.encode(MessageType.ACK, "{}".toByteArray())
        assertEquals(15, ack[5].toInt())
    }

    @Test
    fun passwordIsRejectedWhenEmptyOrTooLong() {
        val clip = MemoryClipboard()
        val handoff = ClipboardHandoff(clip, MemoryNotice())
        val empty = handoff.put(UUID.randomUUID().toString(), "", 0)
        assertFalse(empty.ok)
        assertEquals(ErrorCodes.BAD_REQUEST, empty.code)
        assertNull(clip.value)
        val secret = "p".repeat(CompanionServices.MAX_PASSWORD_CHARS + 1)
        val longOne = handoff.put(UUID.randomUUID().toString(), secret, 0)
        assertFalse(longOne.ok)
        assertFalse(longOne.message.contains(secret))
        assertNull(clip.value)
    }

    @Test
    fun clipboardClearsAfterFortyFiveSecondsOnlyWhenUnchanged() {
        val clip = MemoryClipboard()
        val notices = MemoryNotice()
        val handoff = ClipboardHandoff(clip, notices)
        val secret = "vault-secret"
        val placed = handoff.put(UUID.randomUUID().toString(), secret, 0)
        assertEquals("Copied to the PC clipboard. It will clear in 45 seconds.", placed.message)
        assertFalse(placed.message.contains(secret))
        assertEquals(secret, clip.value)
        assertFalse(handoff.tick(44_000))
        assertEquals(secret, clip.value)
        assertTrue(handoff.tick(45_000))
        assertEquals("", clip.value)
    }

    @Test
    fun aReplacedClipboardIsLeftAloneAndASecondCopyResetsTheDeadline() {
        val clip = MemoryClipboard()
        val handoff = ClipboardHandoff(clip, MemoryNotice())
        handoff.put(UUID.randomUUID().toString(), "vault-secret", 0)
        clip.value = "user typed this"
        assertFalse(handoff.tick(45_000))
        assertEquals("user typed this", clip.value)

        val second = MemoryClipboard()
        val reset = ClipboardHandoff(second, MemoryNotice())
        reset.put(UUID.randomUUID().toString(), "one", 0)
        reset.put(UUID.randomUUID().toString(), "two", 20_000)
        assertFalse(reset.tick(60_000))
        assertEquals("two", second.value)
        assertTrue(reset.tick(65_000))
        assertEquals("", second.value)
    }

    @Test
    fun onlyHttpLinksOpenAndTheResultDoesNotEchoTheUrl() {
        val surface = FakeBrowser()
        val plugin = RemoteBrowserPlugin(surface)
        val opened = plugin.handle(request("open", JSONObject().put("url", "https://example.com/private-path")))
        assertTrue(opened.ok)
        assertEquals("Opened on this PC.", opened.message)
        assertFalse(opened.message.contains("private-path"))
        assertEquals("https://example.com/private-path", surface.opened)
        assertFalse(plugin.handle(request("open", JSONObject().put("url", "file:///etc/passwd"))).ok)
        assertFalse(plugin.handle(request("open", JSONObject().put("url", "javascript:alert(1)"))).ok)
        assertFalse(plugin.handle(request("open", JSONObject().put("url", "https://user:pass@example.com/a"))).ok)
        assertFalse(plugin.handle(request("open", JSONObject().put("url", "https://example.com/\u0001"))).ok)
        assertEquals("https://example.com/private-path", surface.opened)
    }

    @Test
    fun urlOnlySurfaceDoesNotCaptureOrAcceptInput() {
        val surface = FakeBrowser(canView = false, canInput = false)
        val plugin = RemoteBrowserPlugin(surface)
        val frame = plugin.handle(request("frame", JSONObject()))
        val input = plugin.handle(request("input", JSONObject().put("kind", "tap").put("x", 0.2).put("y", 0.4)))
        assertEquals(RemoteBrowserPlugin.VIEW_UNAVAILABLE, frame.code)
        assertEquals(RemoteBrowserPlugin.INPUT_UNAVAILABLE, input.code)
        assertFalse(surface.captured)
        assertNull(surface.lastInput)
        assertTrue(frame.message.contains("desktop", ignoreCase = true))
    }

    @Test
    fun anAttachedSurfaceReceivesTapAndText() {
        val surface = FakeBrowser(canView = true, canInput = true, frame = byteArrayOf(1, 2, 3))
        val plugin = RemoteBrowserPlugin(surface)
        val tap = plugin.handle(request("input", JSONObject().put("kind", "tap").put("x", 0.25).put("y", 0.5)))
        assertTrue(tap.ok)
        assertEquals("tap", surface.lastInput)
        assertEquals(0.25, surface.x)
        assertEquals(0.5, surface.y)
        val text = plugin.handle(request("input", JSONObject().put("kind", "text").put("text", "hi").put("x", 2.0).put("y", -1)))
        assertTrue(text.ok)
        assertEquals("text", surface.lastInput)
        assertEquals("hi", surface.text)
        assertEquals(0.0, surface.x)
        assertEquals(0.0, surface.y)
        assertEquals("Browser view updated.", plugin.handle(request("frame", JSONObject())).message)
    }

    @Test
    fun anOversizedViewIsRefusedWithoutEchoingBytes() {
        val marker = "A".repeat(32)
        val surface = FakeBrowser(canView = true, frame = ByteArray(CompanionServices.MAX_FRAME_BYTES + 1) { 'A'.code.toByte() })
        val result = RemoteBrowserPlugin(surface).handle(request("frame", JSONObject()))
        assertEquals(ErrorCodes.LIMIT, result.code)
        assertFalse(result.message.contains(marker))
    }

    @Test
    fun unknownCompanionCallsAreNotImplemented() {
        val host = CompanionHost(MemoryClipboard(), MemoryNotice(), UrlOnlyBrowserSurface()) { 0 }
        val result = host.handle(request("move", JSONObject(), "desktop.control"))
        assertEquals(ErrorCodes.NOT_IMPLEMENTED, result.code)
        val status = host.handle(request("status", JSONObject()))
        assertTrue(status.message.contains("Live view: off"))
        assertTrue(status.message.contains("Typing: off"))
    }

    @Test
    fun serviceJsonRoundTripsWithoutKeepingASecretInTheResult() {
        val id = "11111111-1111-1111-1111-111111111111"
        val secret = "not-in-the-result"
        val request = ServiceRequest(id, CompanionServices.VAULT_CLIPBOARD, "put", JSONObject().put("password", secret))
        val parsed = ServiceCodec.parseRequest(request.toJson().toString().toByteArray())
        assertEquals(secret, parsed.body.getString("password"))
        val result = ServiceResult.done(id, "Copied to the PC clipboard. It will clear in 45 seconds.")
        val back = ServiceCodec.parseResult(result.toJson().toString().toByteArray())
        assertEquals(result, back)
        assertFalse(back.message.contains(secret))
    }

    private fun request(op: String, body: JSONObject, service: String = CompanionServices.REMOTE_BROWSER) =
        ServiceRequest(UUID.randomUUID().toString(), service, op, body)

    private class MemoryClipboard : SecretClipboard {
        var value: String? = null
        override fun setText(text: String) {
            value = text
        }
        override fun getText(): String? = value
    }

    private class MemoryNotice : UserNotice {
        override fun show(text: String) = Unit
    }

    private class FakeBrowser(
        override val canView: Boolean = false,
        override val canInput: Boolean = false,
        private val frame: ByteArray? = null
    ) : BrowserSurface {
        override val canOpen = true
        var opened: String? = null
        var captured = false
        var lastInput: String? = null
        var x = 0.0
        var y = 0.0
        var text: String? = null

        override fun open(url: String) {
            opened = url
        }

        override fun capture(): ByteArray? {
            captured = true
            if (!canView) error("capture")
            return frame
        }

        override fun input(kind: String, x: Double, y: Double, text: String?) {
            if (!canInput) error("input")
            lastInput = kind
            this.x = x
            this.y = y
            this.text = text
        }
    }
}

class MemoCommandTests {
    private val now = 1_700_000_000L

    @Test
    fun anUnverifiedCommandDoesNotRun() {
        val secret = "https://example.com/hidden-memo"
        val decision = MemoCommandPolicy.decide(body("open_url", "url" to secret), now, senderVerified = false) { false }
        assertFalse(decision.ok)
        assertEquals(MemoCodes.UNVERIFIED_SENDER, decision.code)
        assertFalse(decision.message.contains("hidden-memo"))
    }

    @Test
    fun expiryReplayAndUnknownOpsAreRejected() {
        val seen = mutableSetOf<String>()
        val id = UUID.randomUUID().toString()
        val fresh = MemoCommandPolicy.decide(command("ping", now + 60, id), now, true) { seen.contains(it) }
        assertTrue(fresh.ok)
        seen += id
        val replay = MemoCommandPolicy.decide(command("ping", now + 60, id), now, true) { seen.contains(it) }
        assertEquals(MemoCodes.REPLAY, replay.code)
        assertEquals(MemoCodes.EXPIRED, MemoCommandPolicy.decide(command("ping", now - 121), now, true) { false }.code)
        assertEquals(ErrorCodes.BAD_REQUEST, MemoCommandPolicy.decide(command("ping", now + MemoCommandLimits.MAX_LIFETIME_SECONDS + 1), now, true) { false }.code)
        val shell = MemoCommandPolicy.decide(command("shell", now + 60), now, true) { false }
        assertEquals(ErrorCodes.NOT_IMPLEMENTED, shell.code)
        assertTrue(shell.message.contains("desktop", ignoreCase = true))
    }

    @Test
    fun openAndCopyStayInsideTheAllowedCommands() {
        val opened = MemoCommandPolicy.decide(body("open_url", "url" to "https://example.com/private-path"), now, true) { false }
        assertTrue(opened.ok)
        assertEquals("open_url", opened.op)
        assertFalse(opened.message.contains("private-path"))
        assertFalse(MemoCommandPolicy.decide(body("open_url", "url" to "file:///etc/passwd"), now, true) { false }.ok)
        assertFalse(MemoCommandPolicy.decide(body("open_url", "url" to "javascript:alert(1)"), now, true) { false }.ok)
        val copy = MemoCommandPolicy.decide(body("copy_text", "text" to "memo-secret"), now, true) { false }
        assertTrue(copy.ok)
        assertFalse(copy.message.contains("memo-secret"))
        assertEquals("Text is empty.", MemoCommandPolicy.decide(body("copy_text", "text" to ""), now, true) { false }.message)
    }

    @Test
    fun theStubDoesNotUnlockOrContactTheNetwork() {
        val decision = UnconfiguredMemoCrypto.open("""{"seed":"SSECRET"}""".toByteArray().size)
        assertEquals(MemoCodes.NOT_CONFIGURED, decision.code)
        assertFalse(decision.message.contains("SSECRET"))
        assertFalse(DisconnectedMemoLedger.CONTACTS_NETWORK)
        assertEquals("[redacted]", LogRedaction.redact("""{"seed":"SSECRET"}"""))
        assertEquals("[redacted]", LogRedaction.redact("""{"mnemonic":"word word"}"""))
        assertEquals(ErrorCodes.LIMIT, UnconfiguredMemoCrypto.open(0).code)
    }

    private fun body(op: String, extra: Pair<String, String>? = null): ByteArray {
        val json = JSONObject()
            .put("v", 1)
            .put("id", UUID.randomUUID().toString())
            .put("exp", now + 60)
            .put("op", op)
        if (extra != null) json.put(extra.first, extra.second)
        return json.toString().toByteArray()
    }

    private fun command(op: String, exp: Long, id: String = UUID.randomUUID().toString()): ByteArray =
        JSONObject().put("v", 1).put("id", id).put("exp", exp).put("op", op).toString().toByteArray()
}
