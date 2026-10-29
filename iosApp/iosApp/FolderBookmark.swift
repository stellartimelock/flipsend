import Foundation

/// Manages the security-scoped bookmark for the user's chosen
/// StellarTimeLock/FlipSend receive folder.
///
/// Layout inside the chosen folder:
///   StellarTimeLock/FlipSend/images/
///   StellarTimeLock/FlipSend/documents/
///   StellarTimeLock/FlipSend/media/
///   StellarTimeLock/FlipSend/apps/
///   StellarTimeLock/FlipSend/other/
///
/// The bookmark is stored in the app's private container (not in the
/// StellarTimeLock folder itself) so we never write outside the sandbox
/// without the user's explicit consent.
///
/// Note: "No device-bound encryption" rule applies here.
/// The bookmark bytes are NOT encrypted by Apple Keychain hardware keys.
/// If a password/seed-derived key is later needed to protect the bookmark,
/// apply the HKDF layer before storing it with UserDefaults.
enum FolderBookmark {
    private static let key = "stellarsend.folder.bookmark.v1"

    /// Persist a security-scoped bookmark for [url].
    static func save(url: URL) {
        guard url.startAccessingSecurityScopedResource() else { return }
        defer { url.stopAccessingSecurityScopedResource() }
        do {
            let data = try url.bookmarkData(
                options: .minimalBookmark,
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
            UserDefaults.standard.set(data, forKey: key)
        } catch {
            print("[FolderBookmark] Failed to save bookmark: \(error)")
        }
    }

    /// Resolve the persisted bookmark. Returns nil if none exists or is stale.
    static func resolve() -> URL? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        var isStale = false
        do {
            let url = try URL(
                resolvingBookmarkData: data,
                options: .withoutUI,
                relativeTo: nil,
                bookmarkDataIsStale: &isStale
            )
            if isStale {
                // Re-save with fresh bookmark
                save(url: url)
            }
            return url
        } catch {
            print("[FolderBookmark] Bookmark stale/invalid: \(error)")
            UserDefaults.standard.removeObject(forKey: key)
            return nil
        }
    }

    /// The human-readable label shown in the UI.
    static func label() -> String {
        guard let url = resolve() else { return "App inbox" }
        return url.lastPathComponent
    }

    /// Delete the persisted bookmark (called on reset).
    static func clear() {
        UserDefaults.standard.removeObject(forKey: key)
    }

    /// Open or create the StellarTimeLock/FlipSend subfolder inside the
    /// bookmarked root. Returns nil if no bookmark is set.
    static func flipsendFolder() -> URL? {
        guard let root = resolve() else { return nil }
        guard root.startAccessingSecurityScopedResource() else { return nil }
        let folder = root
            .appendingPathComponent("StellarTimeLock", isDirectory: true)
            .appendingPathComponent("FlipSend", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        } catch {
            root.stopAccessingSecurityScopedResource()
            return nil
        }
        // Caller must call root.stopAccessingSecurityScopedResource() when done.
        return folder
    }
}
