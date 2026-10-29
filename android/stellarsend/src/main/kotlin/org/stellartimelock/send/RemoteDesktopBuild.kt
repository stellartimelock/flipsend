package org.stellartimelock.send

import android.content.Context
import android.content.pm.PackageManager

/**
 * True when this build includes phone → PC remote desktop.
 * The app manifest sets [META]. Production Play sets it false.
 */
object RemoteDesktopBuild {
    const val META = "org.stellartimelock.stellarsend.REMOTE_DESKTOP"

    fun enabled(context: Context): Boolean {
        val info = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        }.getOrNull() ?: return false
        val meta = info.metaData ?: return false
        return meta.containsKey(META) && meta.getBoolean(META, false)
    }
}
