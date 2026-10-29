import Foundation

/// Handles file reception and saving for FlipSend on iOS.
///
/// Layout inside the user's chosen security-scoped folder:
///   StellarTimeLock/FlipSend/images/
///   StellarTimeLock/FlipSend/documents/
///   StellarTimeLock/FlipSend/media/
///   StellarTimeLock/FlipSend/apps/
///   StellarTimeLock/FlipSend/other/
///
/// Atomic saves: temp file → fsync → rename (same volume = atomic on APFS).
/// Last 5 versions of each file are kept as .bak1 … .bak5.
/// A save that would replace a file with an empty or much-smaller one is blocked
/// unless allowShrink = true (mirrors DurableFile.write in android/core).
enum IosFileReceiver {

    /// Subfolder routing — mirrors FolderLayout in shared/commonMain/FileNames.kt.
    static func subfolder(for fileName: String) -> String {
        let ext = (fileName as NSString).pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff": return "images"
        case "mp4", "mov", "avi", "mkv", "m4v", "mp3", "m4a", "aac", "flac", "wav", "ogg": return "media"
        case "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv": return "documents"
        case "apk", "ipa": return "apps"
        default: return "other"
        }
    }

    /// Save received file data to the FlipSend folder.
    ///
    /// - Parameters:
    ///   - data: The received file bytes.
    ///   - name: Sanitized file name (use `FileNames.sanitize()` from KMP).
    ///   - allowShrink: Pass true only when the caller explicitly confirmed replacement.
    /// - Returns: Absolute path where the file was saved, or nil on failure.
    @discardableResult
    static func save(data: Data, name: String, allowShrink: Bool = false) -> String? {
        guard let baseFolder = resolveBaseFolder() else {
            // Fallback to app inbox when no user-chosen folder
            return saveToAppInbox(data: data, name: name, allowShrink: allowShrink)
        }
        let root = baseFolder
            .appendingPathComponent("StellarTimeLock", isDirectory: true)
            .appendingPathComponent("FlipSend", isDirectory: true)
            .appendingPathComponent(subfolder(for: name), isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let target = uniqueUrl(in: root, name: name)
        let written = IosAtomicSave.write(url: target, contents: data, allowShrink: allowShrink)
        guard written else { return nil }
        return target.path
    }

    /// Save text content (returned as a .txt file).
    @discardableResult
    static func saveText(_ text: String, name: String) -> String? {
        let fileName = name.hasSuffix(".txt") ? name : (name + ".txt")
        return save(data: Data(text.utf8), name: fileName, allowShrink: true)
    }

    // MARK: - Private

    private static func resolveBaseFolder() -> URL? {
        guard let root = FolderBookmark.resolve() else { return nil }
        _ = root.startAccessingSecurityScopedResource()
        return root
    }

    private static func saveToAppInbox(data: Data, name: String, allowShrink: Bool) -> String? {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let inbox = support
            .appendingPathComponent("FlipSend", isDirectory: true)
            .appendingPathComponent("inbox", isDirectory: true)
            .appendingPathComponent(subfolder(for: name), isDirectory: true)
        try? FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let target = uniqueUrl(in: inbox, name: name)
        let written = IosAtomicSave.write(url: target, contents: data, allowShrink: allowShrink)
        return written ? target.path : nil
    }

    private static func uniqueUrl(in directory: URL, name: String) -> URL {
        let sanitized = sanitize(name)
        var candidate = directory.appendingPathComponent(sanitized)
        var index = 1
        while FileManager.default.fileExists(atPath: candidate.path) && index < 1000 {
            candidate = directory.appendingPathComponent(insertSuffix(sanitized, " (\(index))"))
            index += 1
        }
        return candidate
    }

    private static func insertSuffix(_ name: String, _ suffix: String) -> String {
        let ns = name as NSString
        let ext = ns.pathExtension
        if ext.isEmpty { return name + suffix }
        let stem = ns.deletingPathExtension
        return "\(stem)\(suffix).\(ext)"
    }

    /// Minimal file name sanitiser matching FileNames.sanitize() in shared/commonMain.
    private static func sanitize(_ raw: String) -> String {
        if raw.isEmpty { return "file" }
        let slash = raw.lastIndex(of: "/") ?? raw.lastIndex(of: "\\")
        var name = slash.map { String(raw[raw.index(after: $0)...]) } ?? raw
        let reserved: Set<String> = ["CON","PRN","AUX","NUL",
            "COM1","COM2","COM3","COM4","COM5","COM6","COM7","COM8","COM9",
            "LPT1","LPT2","LPT3","LPT4","LPT5","LPT6","LPT7","LPT8","LPT9"]
        name = name.map { c -> Character in
            let code = c.asciiValue.map { Int($0) } ?? 1000
            return (code < 32 || "<>:\"/\\|?*".contains(c)) ? "_" : c
        }.reduce("", { $0 + String($1) }).trimmingCharacters(in: .init(charactersIn: ". "))
        if name.isEmpty { return "file" }
        let stem = (name as NSString).deletingPathExtension.uppercased()
        if reserved.contains(stem) { name = "_" + name }
        if name.count > 240 {
            let ext = (name as NSString).pathExtension
            let budget = 240 - (ext.isEmpty ? 0 : ext.count + 1)
            name = String(name.prefix(budget)) + (ext.isEmpty ? "" : ".\(ext)")
        }
        return name
    }
}
