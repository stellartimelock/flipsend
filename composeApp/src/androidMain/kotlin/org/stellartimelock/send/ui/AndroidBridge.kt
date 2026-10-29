package org.stellartimelock.send.ui

import android.content.Context

/**
 * Android implementation of PlatformBridge.
 *
 * Needs from a maintainer PC: wire up to the existing Engine in android/stellarsend
 * once the migration from android/app/ to composeApp/ is done.
 * For now, the stub lets the KMP app compile and run on Android.
 */
class AndroidBridge(private val context: Context) : PlatformBridge {
    override fun getQrCode(): String? = null // TODO: call Engine.openPairingWindow()
    override fun pairFromQr(qr: String): String? = "Not yet wired up"
    override fun pairedPcName(): String? = null
    override fun unpair() {}
    override fun pickFolder(onResult: (String?) -> Unit) { onResult(null) }
    override fun folderLabel(): String = "App inbox"
    override fun history(): List<ReceivedItem> = emptyList()
    override fun openFile(path: String) {}
    override fun biometricsAvailable(): Boolean = false
    override fun authenticateBiometrics(onResult: (Boolean) -> Unit) { onResult(false) }
    override fun ownShortCode(): String = "0000-0000"
    override fun resetAll(confirmed: Boolean) {}
    override fun deviceName(): String = "Android"
    override fun setDeviceName(name: String) {}
    override fun planLabel(): String = "Free"
    override fun relayUrl(): String = "https://relay.stellartimelock.com"
}
