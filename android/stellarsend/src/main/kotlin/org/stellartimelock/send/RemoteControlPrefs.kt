package org.stellartimelock.send

import android.content.Context
import android.provider.Settings

/**
 * Control this phone is its own switch. Remote desktop does not read it.
 * Missing means off. The old `remote_control` key is left in place and ignored.
 */
object RemoteControlPrefs {
    private const val PREFS = "stellarsend"

    fun enabled(context: Context): Boolean = phoneControl(context)

    fun phoneControl(context: Context): Boolean =
        prefs(context).getBoolean(org.stellartimelock.send.core.RemoteDesktop.PHONE_CONTROL_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) = setPhoneControl(context, enabled)

    fun setPhoneControl(context: Context, enabled: Boolean) {
        prefs(context.applicationContext).edit()
            .putBoolean(org.stellartimelock.send.core.RemoteDesktop.PHONE_CONTROL_KEY, enabled)
            .apply()
        Runtime.engine(context).refreshRemoteToggle()
        SessionHub.emit(SessionEvent.RemoteChanged)
    }

    fun accessibilityOn(context: Context): Boolean {
        val expected = context.packageName + "/" + RemoteControlService::class.java.name
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) || it.endsWith(RemoteControlService::class.java.name) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
