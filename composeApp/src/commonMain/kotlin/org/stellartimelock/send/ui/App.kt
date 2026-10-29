package org.stellartimelock.send.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Root composable for FlipSend — shared between Android and iOS.
 *
 * Navigation is intentionally simple: a single enum state avoids a
 * navigation library dependency for now.
 */
@Composable
fun FlipSendApp(bridge: PlatformBridge) {
    MaterialTheme(
        colorScheme = flipSendColorScheme()
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var screen by remember { mutableStateOf(Screen.PAIR) }
            when (screen) {
                Screen.PAIR -> PairScreen(bridge = bridge, onPaired = { screen = Screen.SEND })
                Screen.SEND -> SendScreen(bridge = bridge, onShowHistory = { screen = Screen.HISTORY }, onShowSettings = { screen = Screen.SETTINGS })
                Screen.HISTORY -> HistoryScreen(bridge = bridge, onBack = { screen = Screen.SEND })
                Screen.SETTINGS -> SettingsScreen(bridge = bridge, onBack = { screen = Screen.SEND }, onReset = { screen = Screen.RESET })
                Screen.RESET -> ResetScreen(bridge = bridge, onDone = { screen = Screen.PAIR })
            }
        }
    }
}

enum class Screen { PAIR, SEND, HISTORY, SETTINGS, RESET }

/**
 * Platform bridge — each platform provides a concrete implementation
 * that wires into its OS services (networking, file picker, biometrics).
 *
 * The composables only call these functions; they never touch platform APIs directly.
 */
interface PlatformBridge {
    /** Return the current QR code string for pairing, or null if not ready. */
    fun getQrCode(): String?
    /** Attempt to pair using the scanned QR payload. Returns error string on failure. */
    fun pairFromQr(qr: String): String?
    /** The name of the currently paired PC, or null. */
    fun pairedPcName(): String?
    /** Forget the paired device. */
    fun unpair()
    /** Pick the destination folder (document-picker on iOS, SAF on Android). */
    fun pickFolder(onResult: (folderLabel: String?) -> Unit)
    /** Current folder label (e.g. "Chosen folder" / "App inbox"). */
    fun folderLabel(): String
    /** All received-file records. */
    fun history(): List<ReceivedItem>
    /** Open the file at [path] in the OS viewer. */
    fun openFile(path: String)
    /** True if biometrics are enrolled and enabled in the app. */
    fun biometricsAvailable(): Boolean
    /** Authenticate via biometrics. [onResult] called with success flag. */
    fun authenticateBiometrics(onResult: (Boolean) -> Unit)
    /** The device's own short-code (first 8 hex chars of fingerprint). */
    fun ownShortCode(): String
    /** Wipe all app data. [confirmed] must be true (caller checked RESET text). */
    fun resetAll(confirmed: Boolean)
    /** Current device name shown in the QR code. */
    fun deviceName(): String
    /** Update the device name. */
    fun setDeviceName(name: String)
    /** Relay plan label for the UI. */
    fun planLabel(): String
    /** Relay URL for outbound fallback connections. */
    fun relayUrl(): String
}

data class ReceivedItem(
    val fileName: String,
    val sizeBytes: Long,
    val receivedAtMillis: Long,
    val filePath: String?
)
