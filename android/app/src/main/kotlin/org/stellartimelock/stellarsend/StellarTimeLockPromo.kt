package org.stellartimelock.stellarsend

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Opens the StellarTimeLock app, or its Play listing when it is not installed. */
object StellarTimeLockPromo {
    const val PACKAGE = "com.stellartimelock"
    const val PLAY = "https://play.google.com/store/apps/details?id=$PACKAGE"
    const val SITE = "https://stellartimelock.com"

    fun intent(packageManager: PackageManager): Intent {
        packageManager.getLaunchIntentForPackage(PACKAGE)?.let { return it }
        return Intent(Intent.ACTION_VIEW, Uri.parse(PLAY))
    }

    fun siteIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(SITE))
}
