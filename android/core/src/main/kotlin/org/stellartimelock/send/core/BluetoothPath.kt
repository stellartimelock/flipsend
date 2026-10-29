package org.stellartimelock.send.core

/**
 * File sends try Wi-Fi, then Bluetooth, then the relay.
 * Bluetooth is classic RFCOMM. The bytes are still the paired TLS session.
 * BLE L2CAP is faster on a phone, but Windows does not offer a reliable
 * L2CAP socket for this app, so both sides use RFCOMM.
 */
object BluetoothService {
    const val NAME = "FlipSend"
    const val UUID_TEXT = "f17b5e1d-4c0a-4e7a-9b21-a35f0000b101"
    const val CHANNEL = 25
    const val ATTEMPT_MS = 4_000
    private val mac = Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")

    fun isMac(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (!mac.matches(text)) return false
        return !text.equals("02:00:00:00:00:00", ignoreCase = true)
    }
}

object SendRoute {
    const val WIFI = "wifi"
    const val BLUETOOTH = "bluetooth"
    const val RELAY = "relay"
    val order = listOf(WIFI, BLUETOOTH, RELAY)

    fun choose(lan: Boolean, bluetooth: Boolean, forceRelay: Boolean): String = when {
        forceRelay -> RELAY
        lan -> WIFI
        bluetooth -> BLUETOOTH
        else -> RELAY
    }
}

object BluetoothSetup {
    const val ASK = "ask"
    const val APP_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val BLUETOOTH_SETTINGS = "android.settings.BLUETOOTH_SETTINGS"
}

data class BluetoothSetupStep(val action: String, val label: String, val detail: String)

object BluetoothChecklist {
    fun step(
        permissionGranted: Boolean,
        radioOn: Boolean,
        askedAlready: Boolean,
        shouldShowRationale: Boolean
    ): BluetoothSetupStep = when {
        !permissionGranted && (!askedAlready || shouldShowRationale) -> BluetoothSetupStep(
            BluetoothSetup.ASK,
            "Allow Bluetooth",
            "FlipSend needs Nearby devices so this phone and your PC can send files over Bluetooth."
        )
        !permissionGranted -> BluetoothSetupStep(
            BluetoothSetup.APP_SETTINGS,
            "Open app settings",
            "Allow Nearby devices for FlipSend, then come back."
        )
        !radioOn -> BluetoothSetupStep(
            BluetoothSetup.BLUETOOTH_SETTINGS,
            "Turn on Bluetooth",
            "Open Bluetooth settings, turn Bluetooth on, and pair this phone with your PC."
        )
        else -> BluetoothSetupStep(
            BluetoothSetup.BLUETOOTH_SETTINGS,
            "Open Bluetooth settings",
            "Bluetooth is on. Pair this phone with your PC if they are not paired yet."
        )
    }
}
