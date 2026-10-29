package org.stellartimelock.send.ui

import androidx.compose.ui.window.ComposeUIViewController

/**
 * Factory function called from Swift to create the root UIViewController.
 *
 * Usage in Swift (ContentView.swift):
 *   ComposeView().ignoresSafeArea(.keyboard)
 *   where ComposeView wraps MainViewController().
 */
fun MainViewController(bridge: PlatformBridge) = ComposeUIViewController {
    FlipSendApp(bridge = bridge)
}
