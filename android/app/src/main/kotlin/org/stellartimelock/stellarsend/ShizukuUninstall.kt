package org.stellartimelock.stellarsend

import android.content.pm.PackageManager
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/** Runs `pm uninstall --user all` when the Shizuku binder is up. */
internal object ShizukuUninstall {
    fun available(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun allowed(): Boolean = try {
        available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun uninstallAllUsers(packageName: String): Int {
        val binder = Shizuku.getBinder() ?: return -1
        val remote = IShizukuService.Stub.asInterface(binder)
            .newProcess(RemoveOldApp.shizukuArgs(packageName), null, null)
        return remote.waitFor()
    }
}
