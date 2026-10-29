import SwiftUI

/// Root of the FlipSend iOS app.
///
/// All business logic lives in the Kotlin/Native ComposeApp framework.
/// Swift/SwiftUI handles only what iOS requires:
///   - Document picker (UIDocumentPickerViewController)
///   - Security-scoped bookmarks for the StellarTimeLock folder
///   - Biometrics (LAContext)
///   - QR code scanner (AVFoundation / DataScannerViewController)
///   - App lifecycle
@main
struct iOSApp: App {

    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(appState)
        }
    }
}

/// Shared observable state wired between Swift and the Kotlin bridge.
@MainActor
final class AppState: ObservableObject {
    @Published var showDocumentPicker = false
    @Published var folderPickerCallback: ((URL?) -> Void)? = nil

    /// Present the document picker and deliver the selected URL via callback.
    func pickFolder(completion: @escaping (String?) -> Void) {
        folderPickerCallback = { url in
            guard let url = url else { completion(nil); return }
            completion(url.lastPathComponent)
        }
        showDocumentPicker = true
    }
}
