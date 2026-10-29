package org.stellartimelock.stellarsend

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stellartimelock.send.ReceivedLog
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class PlayScreenshotTest {
    @Test
    fun launcherAndStoreIconsAreTrueBlackAndWhite() {
        assertTrueBlackAndWhite(File(repoRoot(), "docs/play-assets/icon-512.png"), 512)
        assertTrueBlackAndWhite(File(repoRoot(), "docs/play-assets/icon-1024-bw.png"), 1024)
        assertTrueBlackAndWhite(
            File(repoRoot(), "android/stellarsend/src/main/res/mipmap-xxxhdpi/ic_launcher.png"),
            192
        )
        assertTrueBlackAndWhite(
            File(repoRoot(), "android/stellarsend/src/main/res/drawable/ic_launcher_foreground.png"),
            432
        )
        assertTrueBlackAndWhite(
            File(repoRoot(), "desktop/src/StellarSend.App/Assets/icon.png"),
            256
        )
    }

    @Test
    fun committedPlayPngsHaveStoreSizes() {
        val dir = File(repoRoot(), "docs/play-assets")
        assertPng(File(dir, "icon-512.png"), 512, 512)
        assertPng(File(dir, "feature-1024x500.png"), 1024, 500)
        assertPng(File(dir, "01-pairing.png"), 1080, 1920)
        assertPng(File(dir, "02-sending.png"), 1080, 1920)
        assertPng(File(dir, "03-relay-plans.png"), 1080, 1920)
        assertPng(File(dir, "04-history.png"), 1080, 1920)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun renderPhoneScreensFromTheRealLayouts() {
        if (System.getenv("PLAY_SCREENSHOTS") != "1") return
        val dir = File(repoRoot(), "docs/play-assets")
        dir.mkdirs()
        val main = Robolectric.buildActivity(MainActivity::class.java).create().get()
        main.findViewById<TextView>(R.id.status).text = "Paired with Windows PC"
        main.findViewById<TextView>(R.id.detail).text = "Listening on this phone. Direct on Wi-Fi"
        main.findViewById<ImageView>(R.id.qr).apply {
            setImageBitmap(qr())
            visibility = View.VISIBLE
        }
        main.findViewById<TextView>(R.id.shortCode).apply {
            text = "K7NP-4QWM"
            visibility = View.VISIBLE
        }
        val devices = main.findViewById<LinearLayout>(R.id.devices)
        devices.removeAllViews()
        devices.addView(TextView(main).apply {
            text = "Windows PC"
            setTextColor(main.getColor(org.stellartimelock.send.R.color.space_text))
        })
        shoot(main.window.decorView, File(dir, "01-pairing.png"))

        main.findViewById<View>(R.id.homeSendCard).visibility = View.VISIBLE
        main.findViewById<View>(R.id.sendClipboard).visibility = View.GONE
        main.findViewById<View>(R.id.sendFolder).visibility = View.GONE
        main.findViewById<EditText>(R.id.message).setText("Notes for the PC")
        main.findViewById<TextView>(R.id.progress).text = "notes.txt · 64 KB · sending"
        main.findViewById<ProgressBar>(R.id.bar).apply {
            visibility = View.VISIBLE
            progress = 62
        }
        val files = main.findViewById<LinearLayout>(R.id.files)
        files.addView(TextView(main).apply {
            text = "notes.txt"
            setTextColor(main.getColor(org.stellartimelock.send.R.color.space_text))
        })
        layout(main.window.decorView)
        val sendCard = main.findViewById<View>(R.id.message).parent as View
        shoot(main.window.decorView, File(dir, "02-sending.png"), (sendCard.top - 48).coerceAtLeast(0))

        val app = RuntimeEnvironment.getApplication()
        val inbox = File(app.filesDir, "inbox")
        inbox.mkdirs()
        val note = File(inbox, "notes.txt")
        note.writeText("hello from the PC")
        val photo = File(inbox, "photo.bin")
        photo.writeBytes(ByteArray(24000))
        ReceivedLog(app).add(note, "text", 1_759_500_000_000)
        ReceivedLog(app).add(photo, "file", 1_759_510_000_000)
        val history = Robolectric.buildActivity(org.stellartimelock.send.ReceivedActivity::class.java).create().get()
        shoot(history.window.decorView, File(dir, "04-history.png"))
    }

    private fun qr(): Bitmap {
        val matrix = QRCodeWriter().encode("stellarsend://pair?v=1&name=Windows%20PC", BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix[x, y]) 0xFF0B1020.toInt() else 0xFFE8EEF9.toInt())
            }
        }
        return bitmap
    }

    private fun shoot(view: View, file: File, scrollY: Int = 0) {
        layout(view)
        val scroller = findScroller(view)
        if (scrollY > 0 && scroller != null) {
            scroller.scrollTo(0, scrollY)
            scroller.invalidate()
        }
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF0B1020.toInt())
        view.draw(canvas)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun findScroller(view: View): ScrollView? {
        if (view is ScrollView) return view
        if (view !is android.view.ViewGroup) return null
        for (i in 0 until view.childCount) {
            findScroller(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun layout(view: View) {
        val width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        view.measure(width, height)
        view.layout(0, 0, 1080, 1920)
    }

    private fun assertTrueBlackAndWhite(file: File, size: Int) {
        assertPng(file, size, size)
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        assertEquals(size, bitmap.width)
        assertEquals(size, bitmap.height)
        var sawBlack = false
        var sawWhite = false
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val px = bitmap.getPixel(x, y)
                val a = android.graphics.Color.alpha(px)
                val r = android.graphics.Color.red(px)
                val g = android.graphics.Color.green(px)
                val b = android.graphics.Color.blue(px)
                assertTrue("$file ${x}x${y} has gray or mixed alpha ($r,$g,$b,$a)", a == 0 || a == 255)
                if (a == 0) continue
                assertTrue("$file ${x}x${y} is not pure black or white ($r,$g,$b)", r == g && g == b && (r == 0 || r == 255))
                if (r == 0) sawBlack = true else sawWhite = true
            }
        }
        assertTrue("$file has no white mark", sawWhite)
        if (file.name != "ic_launcher_foreground.png") {
            assertTrue("$file has no black fill", sawBlack)
        }
    }

    private fun assertPng(file: File, width: Int, height: Int) {
        assertTrue(file.absolutePath, file.isFile && file.length() > 32)
        val header = ByteArray(24)
        file.inputStream().use { stream -> assertEquals(24, stream.read(header)) }
        assertEquals(0x89.toByte(), header[0])
        val w = ((header[16].toInt() and 0xFF) shl 24) or ((header[17].toInt() and 0xFF) shl 16) or
            ((header[18].toInt() and 0xFF) shl 8) or (header[19].toInt() and 0xFF)
        val h = ((header[20].toInt() and 0xFF) shl 24) or ((header[21].toInt() and 0xFF) shl 16) or
            ((header[22].toInt() and 0xFF) shl 8) or (header[23].toInt() and 0xFF)
        assertEquals(width, w)
        assertEquals(height, h)
    }

    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (true) {
            if (File(dir, "relay/tiers.json").isFile) return dir
            dir = dir.parentFile ?: error("repo root")
        }
    }
}
