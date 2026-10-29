package org.stellartimelock.send.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Android QR code view using ZXing. */
@Composable
actual fun QrCodeView(data: String?, modifier: Modifier) {
    // TODO: Use com.journeyapps:zxing-android-embedded or
    //       com.google.zxing:core to render the QR bitmap.
    // For now, show a placeholder.
    QrCodePlaceholder(data = data, modifier = modifier)
}
