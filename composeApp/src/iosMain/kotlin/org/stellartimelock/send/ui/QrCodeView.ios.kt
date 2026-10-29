package org.stellartimelock.send.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** iOS QR code view using CoreImage CIQRCodeGenerator via the Swift side. */
@Composable
actual fun QrCodeView(data: String?, modifier: Modifier) {
    // On iOS the actual QR rendering is done in Swift/UIKit.
    // The Swift side injects a UIViewRepresentable that wraps CIQRCodeGenerator.
    // For the KMP build we show the placeholder; the Swift wrapper replaces it
    // at the call site in ContentView.swift using a different approach.
    QrCodePlaceholder(data = data, modifier = modifier)
}
