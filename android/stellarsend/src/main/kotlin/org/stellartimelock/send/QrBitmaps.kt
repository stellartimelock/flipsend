package org.stellartimelock.send

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

internal object QrBitmaps {
    private const val DARK = 0xFF07060F.toInt()
    private const val CREAM = 0xFFF6F1E7.toInt()

    @Volatile private var lastText = ""
    @Volatile private var lastSize = 0
    @Volatile private var lastBitmap: Bitmap? = null

    fun encode(text: String, size: Int): Bitmap {
        val hit = lastBitmap
        if (hit != null && !hit.isRecycled && text == lastText && size == lastSize) {
            return hit
        }
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        var i = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[i++] = if (matrix.get(x, y)) DARK else CREAM
            }
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        lastText = text
        lastSize = size
        lastBitmap = bitmap
        return bitmap
    }
}
