package org.stellartimelock.stellarsend

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Clears a leftover install record for another Android user.
 *
 * Play refuses StellarTimeLock when it says another user already has an
 * incompatible version. The package id is the wallet applicationId
 * `com.stellartimelock` (stellartimelock-wallet).
 */
internal object RemoveOldApp {
    const val NOT_VISIBLE = "not visible in this profile"
    const val CLEARED = "Cleared - install from Play now."
    const val INSTALLED = "Installed in this profile"

    /**
     * Same value as Intent.EXTRA_UNINSTALL_ALL_USERS. The public SDK hides that
     * SystemApi field. AOSP's UninstallerActivity still reads this extra.
     */
    const val EXTRA_UNINSTALL_ALL_USERS = "android.intent.extra.UNINSTALL_ALL_USERS"

    /** Same id as [StellarTimeLockPromo.PACKAGE]. */
    const val DEFAULT_PACKAGE = StellarTimeLockPromo.PACKAGE

    private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isPackageName(value: String): Boolean = packageName.matches(value)

    /**
     * System uninstall sheet. [Intent.EXTRA_UNINSTALL_ALL_USERS] is what AOSP's
     * UninstallerActivity reads for an admin user; it then looks the package up
     * with MATCH_ANY_USER.
     */
    @Suppress("DEPRECATION")
    fun uninstallIntent(packageName: String, action: String = Intent.ACTION_UNINSTALL_PACKAGE): Intent {
        return Intent(action).apply {
            data = Uri.parse("package:$packageName")
            putExtra(EXTRA_UNINSTALL_ALL_USERS, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }

    /**
     * Shizuku fallback. No `-k`: keeping the old data can keep the signature conflict.
     */
    fun shizukuArgs(packageName: String): Array<String> =
        arrayOf("pm", "uninstall", "--user", "all", packageName)

    fun visibleInThisProfile(packageManager: PackageManager, packageName: String): Boolean {
        return runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess
    }

    fun visibilityStatus(visible: Boolean): String = if (visible) INSTALLED else NOT_VISIBLE
}
