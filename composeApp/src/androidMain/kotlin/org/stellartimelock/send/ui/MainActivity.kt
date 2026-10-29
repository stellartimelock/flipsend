package org.stellartimelock.send.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Android entry point for the Compose Multiplatform app.
 *
 * NOTE: The existing FlipSend Android app (android/app/) is kept intact and
 * continues to ship. This activity is for the new KMP-built variant that
 * will eventually replace it once the migration is complete.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val bridge = AndroidBridge(this)
        setContent {
            FlipSendApp(bridge = bridge)
        }
    }
}
