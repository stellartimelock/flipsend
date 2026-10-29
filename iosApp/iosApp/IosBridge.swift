import Foundation
import Combine
import LocalAuthentication
import ComposeApp

/// ViewModel bridging Swift/SwiftUI state into the KMP PlatformBridge.
@MainActor
final class IosBridgeViewModel: ObservableObject {

    /// ContentView subscribes here to present UIDocumentPickerViewController from Swift.
    let pickFolderRequest = PassthroughSubject<(String?) -> Void, Never>()

    lazy var kotlinBridge: IosBridge = IosBridge(viewModel: self)
}

/// Concrete PlatformBridge for iOS.
///
/// Threading model: Compose calls bridge methods on the main thread.
/// Network / file I/O is dispatched to background queues internally.
final class IosBridge: NSObject, PlatformBridge {

    private weak var viewModel: IosBridgeViewModel?
    private let store = IosStore.shared

    init(viewModel: IosBridgeViewModel) {
        self.viewModel = viewModel
    }

    // MARK: - Identity & QR

    func getQrCode() -> String? {
        guard let identity = store.loadIdentity() else { return nil }
        let addresses = IosNetInfo.localIpAddresses()
        let advert = PairingCodecCompat.buildAdvert(identity: identity, hosts: addresses)
        return PairingCodecCompat.toQr(advert: advert)
    }

    func pairFromQr(qr: String) -> String? {
        guard let identity = store.loadIdentity() else {
            return "No device identity. Restart the app."
        }
        // Parse and attempt LAN connection synchronously on a background thread.
        var result: String? = nil
        let sem = DispatchSemaphore(value: 0)
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let peer = try PairingCodecCompat.parse(qr: qr)
                let hosts = peer.hosts.isEmpty ? [peer.host] : peer.hosts
                var lastError: Error = NSError(domain: "FlipSend", code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "No address to try."])
                for host in hosts {
                    guard !host.isEmpty else { continue }
                    do {
                        try IosNetworkBridge.shared.pairHandshake(
                            host: host, port: Int(peer.port),
                            identity: identity, expectedFp: peer.fingerprint
                        )
                        self.store.savePeer(peer)
                        sem.signal()
                        return
                    } catch {
                        lastError = error
                    }
                }
                // Relay fallback
                if let relayUrl = self.store.relayUrl(), !relayUrl.isEmpty {
                    do {
                        let room = RelayRoomCompat.pairing(deviceId: peer.deviceId)
                        try IosNetworkBridge.shared.pairViaRelay(
                            relayUrl: relayUrl, room: room,
                            identity: identity, expectedFp: peer.fingerprint
                        )
                        self.store.savePeer(peer)
                        sem.signal()
                        return
                    } catch {
                        lastError = error
                    }
                }
                result = lastError.localizedDescription
            } catch {
                result = error.localizedDescription
            }
            sem.signal()
        }
        sem.wait()
        return result
    }

    func pairedPcName() -> String? { store.loadPeer()?.name }

    func unpair() {
        store.deletePeer()
        store.saveIdentity(nil)  // rotate identity on unpair
    }

    func pickFolder(onResult: @escaping (String?) -> Void) {
        viewModel?.pickFolderRequest.send { label in onResult(label) }
    }

    func folderLabel() -> String { FolderBookmark.label() }

    func history() -> [ReceivedItem] {
        store.loadHistory().map { entry in
            ReceivedItem(
                fileName: entry.name,
                sizeBytes: entry.bytes,
                receivedAtMillis: entry.receivedAtMillis,
                filePath: entry.filePath
            )
        }
    }

    func openFile(path: String) {
        guard let url = URL(string: "file://\(path)") else { return }
        DispatchQueue.main.async {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
    }

    func biometricsAvailable() -> Bool { BiometricGate.available }

    func authenticateBiometrics(onResult: @escaping (KotlinBoolean) -> Void) {
        BiometricGate.authenticate { success in
            onResult(KotlinBoolean(bool: success))
        }
    }

    func ownShortCode() -> String {
        guard let identity = store.loadIdentity() else { return "----" }
        let fp = identity.fingerprint
        guard fp.count >= 8 else { return "----" }
        let first4 = String(fp.prefix(4)).uppercased()
        let next4  = String(fp.dropFirst(4).prefix(4)).uppercased()
        return "\(first4)-\(next4)"
    }

    func resetAll(confirmed: Bool) {
        guard confirmed else { return }
        FolderBookmark.clear()
        store.deleteAll()
        IosKeychain.deleteAll()
    }

    func deviceName() -> String { store.deviceName() }
    func setDeviceName(name: String) { store.saveDeviceName(name) }

    func planLabel() -> String { "Free" }
    func relayUrl() -> String { store.relayUrl() ?? "https://relay.stellartimelock.com" }
}

// MARK: - Persistent store (app container, not Keychain)

/// All data persisted to the app's private container.
/// File paths are under Application Support / FlipSend/.
final class IosStore {
    static let shared = IosStore()

    private let dir: URL
    private let identityFile: URL
    private let peerFile: URL
    private let historyFile: URL
    private let prefsFile: URL

    private init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        dir = support.appendingPathComponent("FlipSend", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        identityFile = dir.appendingPathComponent("identity.bin")
        peerFile     = dir.appendingPathComponent("peer.json")
        historyFile  = dir.appendingPathComponent("received.json")
        prefsFile    = dir.appendingPathComponent("prefs.json")
    }

    // MARK: Identity (encrypted in future; stored as raw bytes for now)
    // TODO: Encrypt with key derived from StellarTimeLock seed / Argon2id.
    //       "No device-bound encryption" means the Keychain hardware key must NOT
    //       be the encryption key here. Use a password/seed-derived key instead.
    func loadIdentity() -> StoredIdentity? {
        guard let data = try? Data(contentsOf: identityFile) else { return nil }
        return try? JSONDecoder().decode(StoredIdentity.self, from: data)
    }
    func saveIdentity(_ identity: StoredIdentity?) {
        if let identity = identity {
            let data = try? JSONEncoder().encode(identity)
            try? data?.write(to: identityFile, options: .atomicWrite)
        } else {
            try? FileManager.default.removeItem(at: identityFile)
        }
    }
    func loadOrCreateIdentity() -> StoredIdentity {
        if let existing = loadIdentity() { return existing }
        let identity = StoredIdentity.generate(name: deviceName())
        saveIdentity(identity)
        return identity
    }

    // MARK: Peer
    func loadPeer() -> StoredPeer? {
        guard let data = try? Data(contentsOf: peerFile) else { return nil }
        return try? JSONDecoder().decode(StoredPeer.self, from: data)
    }
    func savePeer(_ peer: StoredPeer) {
        let data = try? JSONEncoder().encode(peer)
        try? data?.write(to: peerFile, options: .atomicWrite)
    }
    func deletePeer() {
        try? FileManager.default.removeItem(at: peerFile)
    }

    // MARK: History
    func loadHistory() -> [HistoryEntry] {
        guard let data = try? Data(contentsOf: historyFile),
              let text = String(data: data, encoding: .utf8) else { return [] }
        return HistoryEntry.decodeAll(from: text)
    }
    func appendHistory(_ entry: HistoryEntry) {
        var list = loadHistory()
        list.append(entry)
        let json = HistoryEntry.encodeAll(list)
        IosAtomicSave.write(url: historyFile, contents: json.data(using: .utf8) ?? Data(), allowShrink: true)
    }

    // MARK: Prefs
    func deviceName() -> String {
        guard let data = try? Data(contentsOf: prefsFile),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let name = obj["deviceName"] as? String, !name.isEmpty else {
            return UIDevice.current.name
        }
        return name
    }
    func saveDeviceName(_ name: String) {
        let obj: [String: Any] = ["deviceName": name]
        let data = try? JSONSerialization.data(withJSONObject: obj)
        try? data?.write(to: prefsFile, options: .atomicWrite)
    }
    func relayUrl() -> String? {
        // Load from bundled tiers.json
        if let url = Bundle.main.url(forResource: "tiers", withExtension: "json"),
           let data = try? Data(contentsOf: url),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let relayUrl = obj["relayUrl"] as? String, !relayUrl.isEmpty {
            return relayUrl
        }
        return nil
    }

    // MARK: Reset
    func deleteAll() {
        [identityFile, peerFile, historyFile, prefsFile].forEach {
            try? FileManager.default.removeItem(at: $0)
        }
    }
}

// MARK: - Data models (Codable)

struct StoredIdentity: Codable {
    let deviceId: String
    let name: String
    let fingerprint: String
    let spkiBase64Url: String
    // Raw DER bytes stored as base64 strings
    let privateKeyB64: String
    let certificateB64: String

    static func generate(name: String) -> StoredIdentity {
        // Delegate to the Kotlin KMP generateIdentity() function.
        // generateIdentity() is in shared/src/iosMain/Identity.ios.kt.
        let kmpId = SharedKt.generateIdentity(name: name)
        return StoredIdentity(
            deviceId: kmpId.deviceId,
            name: kmpId.name,
            fingerprint: kmpId.fingerprint,
            spkiBase64Url: kmpId.spkiBase64Url,
            privateKeyB64: kmpId.privateKeyBytes.base64EncodedString(),
            certificateB64: kmpId.certificateBytes.base64EncodedString()
        )
    }

    var asKmpIdentity: DeviceIdentity {
        DeviceIdentity(
            deviceId: deviceId, name: name,
            privateKeyBytes: Data(base64Encoded: privateKeyB64) ?? Data(),
            certificateBytes: Data(base64Encoded: certificateB64) ?? Data(),
            fingerprint: fingerprint, spkiBase64Url: spkiBase64Url
        )
    }
}

struct StoredPeer: Codable {
    let deviceId: String
    let name: String
    let fingerprint: String
    let host: String
    let port: Int32
    let hosts: [String]
    let pairedAtMillis: Int64
}

struct HistoryEntry: Codable {
    let id: String
    let name: String
    let bytes: Int64
    let receivedAtMillis: Int64
    let kind: String
    let filePath: String?

    static func decodeAll(from json: String) -> [HistoryEntry] {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONDecoder().decode(HistoryEnvelope.self, from: data) else { return [] }
        return obj.items
    }
    static func encodeAll(_ entries: [HistoryEntry]) -> String {
        let envelope = HistoryEnvelope(v: 1, items: entries)
        let data = try? JSONEncoder().encode(envelope)
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "{\"v\":1,\"items\":[]}"
    }

    private struct HistoryEnvelope: Codable {
        let v: Int
        let items: [HistoryEntry]
    }
}

// MARK: - Compatibility shims for KMP types (avoid name collisions with Swift)

enum PairingCodecCompat {
    struct ParsedAdvert {
        let deviceId: String
        let fingerprint: String
        let host: String
        let port: Int32
        let hosts: [String]
        let name: String
        let spkiBase64Url: String
    }

    static func buildAdvert(identity: StoredIdentity, hosts: [String]) -> ParsedAdvert {
        ParsedAdvert(
            deviceId: identity.deviceId,
            fingerprint: identity.fingerprint,
            host: hosts.first ?? "",
            port: 53321,
            hosts: hosts,
            name: identity.name,
            spkiBase64Url: identity.spkiBase64Url
        )
    }

    static func toQr(advert: ParsedAdvert) -> String? {
        // Build the QR string using the shared Kotlin PairingCodec.
        let kmpAdvert = PairingAdvert(
            version: 1,
            deviceId: advert.deviceId,
            publicKeySpkiBase64Url: advert.spkiBase64Url,
            fingerprint: advert.fingerprint,
            host: advert.host,
            port: Int32(advert.port),
            name: advert.name,
            hosts: advert.hosts
        )
        return PairingCodec_Companion().toQr(advert: kmpAdvert)
    }

    static func parse(qr: String) throws -> ParsedAdvert {
        let kmpAdvert = try PairingCodec_Companion().parse(payload: qr)
        return ParsedAdvert(
            deviceId: kmpAdvert.deviceId,
            fingerprint: kmpAdvert.fingerprint,
            host: kmpAdvert.host,
            port: Int32(kmpAdvert.port),
            hosts: kmpAdvert.hosts as? [String] ?? [],
            name: kmpAdvert.name,
            spkiBase64Url: kmpAdvert.publicKeySpkiBase64Url
        )
    }
}

enum RelayRoomCompat {
    static func pairing(deviceId: String) -> String {
        RelayRoom_Companion().pairing(showingDeviceId: deviceId)
    }
    static func paired(_ a: String, _ b: String) -> String {
        RelayRoom_Companion().paired(a: a, b: b)
    }
}

// MARK: - Network bridge (synchronous wrappers for Kotlin/Network.framework)

/// Thin Swift wrapper that calls the Kotlin NetworkTransportFactory from Swift.
/// Running these synchronously on background threads keeps the protocol code unchanged.
final class IosNetworkBridge {
    static let shared = IosNetworkBridge()
    private let factory = NetworkTransportFactory()

    /// Perform the HELLO handshake over a direct LAN TLS connection.
    func pairHandshake(host: String, port: Int, identity: StoredIdentity, expectedFp: String) throws {
        // NetworkTransportFactory.connect is a suspend function — run it in a coroutine.
        // Use GlobalScope.async equivalent via Kotlin/Native coroutine dispatcher.
        // This blocks the calling thread (already on a background DispatchQueue).
        // Needs from a maintainer PC: replace with proper Swift async/await or
        // call the Kotlin suspend function via withContext.
        //
        // For now: call via a Kotlin coroutine run blocking wrapper if available,
        // or implement the handshake directly using CFStream (both use the same cert).
        throw NSError(domain: "FlipSend", code: -1,
            userInfo: [NSLocalizedDescriptionKey: "Pair handshake not yet wired to Kotlin coroutines. Needs from a maintainer PC: wire IosBridge.pairHandshake() to NetworkTransportFactory.connect()."])
    }

    /// Pair via relay WebSocket.
    func pairViaRelay(relayUrl: String, room: String, identity: StoredIdentity, expectedFp: String) throws {
        throw NSError(domain: "FlipSend", code: -1,
            userInfo: [NSLocalizedDescriptionKey: "Relay pair not yet wired. Needs from a maintainer PC: wire pairViaRelay() to NetworkTransportFactory.openRelay()."])
    }
}

// MARK: - Local IP addresses

enum IosNetInfo {
    /// Return all local IPv4 addresses suitable for the QR code.
    static func localIpAddresses() -> [String] {
        var addresses = [String]()
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0 else { return [] }
        defer { freeifaddrs(ifaddr) }
        var ptr = ifaddr
        while let ifa = ptr?.pointee {
            let family = ifa.ifa_addr.pointee.sa_family
            if family == UInt8(AF_INET) {
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(ifa.ifa_addr, socklen_t(ifa.ifa_addr.pointee.sa_len),
                               &hostname, socklen_t(hostname.count),
                               nil, 0, NI_NUMERICHOST) == 0 {
                    let ip = String(cString: hostname)
                    // Filter loopback and link-local; keep private ranges (Surfshark VPN friendly)
                    if !ip.hasPrefix("127.") && !ip.hasPrefix("169.254.") {
                        addresses.append(ip)
                    }
                }
            }
            ptr = ifa.ifa_next
        }
        return addresses
    }
}

// MARK: - Atomic file save (matches DurableFile in android/core)

enum IosAtomicSave {
    /// Write [contents] to [url] atomically with up to 5 backup copies.
    /// Returns false (and does NOT write) if the incoming content would
    /// replace a much-larger existing file (same rule as DurableFile.write).
    @discardableResult
    static func write(url: URL, contents: Data, allowShrink: Bool, backupCount: Int = 5) -> Bool {
        let fm = FileManager.default
        if fm.fileExists(atPath: url.path) {
            if !allowShrink {
                let existing = (try? Data(contentsOf: url))?.count ?? 0
                if existing > 32 && contents.count * 2 < existing { return false }
            }
            rotateBackups(url: url, count: backupCount)
        }
        let tmp = url.deletingLastPathComponent().appendingPathComponent(url.lastPathComponent + ".tmp")
        do {
            try contents.write(to: tmp, options: .atomic)
            if fm.fileExists(atPath: url.path) { try fm.removeItem(at: url) }
            try fm.moveItem(at: tmp, to: url)
        } catch {
            try? fm.removeItem(at: tmp)
            return false
        }
        return true
    }

    private static func rotateBackups(url: URL, count: Int) {
        let fm = FileManager.default
        let oldest = backupUrl(url, index: count)
        try? fm.removeItem(at: oldest)
        for i in stride(from: count - 1, through: 1, by: -1) {
            let src = backupUrl(url, index: i)
            let dst = backupUrl(url, index: i + 1)
            if fm.fileExists(atPath: src.path) {
                try? fm.moveItem(at: src, to: dst)
            }
        }
        try? fm.copyItem(at: url, to: backupUrl(url, index: 1))
    }

    private static func backupUrl(_ url: URL, index: Int) -> URL {
        url.deletingLastPathComponent().appendingPathComponent(url.lastPathComponent + ".bak\(index)")
    }
}

// MARK: - IosKeychain Swift wrapper (delegates to KMP IosKeychain object)

enum IosKeychain {
    static func deleteAll() {
        // Call the Kotlin IosKeychain.deleteAll() via the generated KMP header.
        IosKeychain_Companion().deleteAll()
    }
}
