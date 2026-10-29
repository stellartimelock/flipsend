package org.stellartimelock.send

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Starts the foreground service when the phone boots or after FlipSend itself updates.
 * Only runs if "Stay connected in background" is on (the default).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (BackgroundListen.enabled(context)) {
                    runCatching {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, TransferService::class.java)
                        )
                    }
                }
            }
        }
    }
}
