package org.stellartimelock.send.core

/**
 * Runtime and special permissions FlipSend actually uses. Ask them together
 * when the app opens, not one at a time later and not in the middle of an
 * install. Screen capture is an Android MediaProjection grant and can only
 * be asked when the user starts Share screen.
 *
 * NEARBY_WIFI_DEVICES (Android 13+) is also the grant Android 16 uses for
 * local-network access: LAN sockets, multicast discovery, and mDNS. The
 * relay and other internet traffic do not need it. A later release may add
 * another permission in the same Nearby devices group.
 */
object StartupPermissions {
    const val CAMERA = "android.permission.CAMERA"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"

    fun runtime(sdk: Int): List<String> {
        val needed = ArrayList<String>()
        needed += CAMERA
        if (sdk >= 31) {
            needed += BLUETOOTH_CONNECT
            needed += BLUETOOTH_SCAN
        }
        if (sdk >= 33) {
            needed += POST_NOTIFICATIONS
            needed += NEARBY_WIFI_DEVICES
        }
        if (sdk <= 32) needed += READ_EXTERNAL_STORAGE
        if (sdk <= 28) needed += WRITE_EXTERNAL_STORAGE
        return needed
    }

    fun missing(sdk: Int, granted: (String) -> Boolean): List<String> =
        runtime(sdk).filterNot(granted)

    /** Unknown-apps is a Settings page, not a runtime dialog. The Play flavor does not install APKs. */
    fun needsUnknownApps(sdk: Int, canRequestPackageInstalls: Boolean): Boolean =
        sdk >= 26 && !canRequestPackageInstalls
}
