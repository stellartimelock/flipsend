package org.stellartimelock.send

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.stellartimelock.send.core.ScreenShare
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

internal object ScreenShareHub {
    private val jpeg = AtomicReference<ByteArray?>(null)
    @Volatile
    var running: Boolean = false
        private set

    fun publish(bytes: ByteArray?) {
        jpeg.set(bytes)
    }

    fun latest(): ByteArray? = jpeg.get()

    fun mark(value: Boolean) {
        running = value
        if (!value) jpeg.set(null)
    }
}

class ScreenShareService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        releaseCapture()
        val notification = notice()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTICE_ID, notification)
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // The consent result is one-shot. A second getMediaProjection on the same Intent throws.
        val next = runCatching { mgr.getMediaProjection(code, data) }.getOrNull()
        if (next == null) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        projection = next
        next.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                shutdown()
                stopSelf()
            }
        }, main)
        startCapture(next)
        ScreenShareHub.mark(true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCapture(projection: MediaProjection) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val jpeg = runCatching { jpegOf(image, width, height) }.getOrNull()
            image.close()
            if (ScreenShare.acceptJpeg(jpeg)) ScreenShareHub.publish(jpeg)
        }, main)
        display = projection.createVirtualDisplay(
            "flipsend-screen",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            main
        )
    }

    private fun jpegOf(image: Image, width: Int, height: Int): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        val cropped = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } else {
            bitmap
        }
        val scale = ScreenShare.MAX_WIDTH.toFloat() / cropped.width.toFloat()
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(cropped, ScreenShare.MAX_WIDTH, (cropped.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            cropped
        }
        var quality = 70
        var bytes: ByteArray
        do {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 15
        } while (bytes.size > ScreenShare.MAX_JPEG_BYTES && quality >= 25)
        if (scaled !== cropped) scaled.recycle()
        if (cropped !== bitmap) cropped.recycle()
        bitmap.recycle()
        return bytes
    }

    private fun releaseCapture() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null
        reader = null
        projection = null
    }

    private fun shutdown() {
        ScreenShareHub.mark(false)
        releaseCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notice(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen share", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("FlipSend")
            .setContentText("Showing this phone on the PC.")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_START = "org.stellartimelock.send.action.SHARE_SCREEN"
        const val ACTION_STOP = "org.stellartimelock.send.action.STOP_SHARE_SCREEN"
        const val EXTRA_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "screen_share"
        private const val NOTICE_ID = 96

        /** Whole-display consent. The default dialog can limit capture to one app window. */
        fun consentIntent(context: Context): Intent {
            val projection = context.getSystemService(MediaProjectionManager::class.java)
            return if (Build.VERSION.SDK_INT >= 34) {
                projection.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                projection.createScreenCaptureIntent()
            }
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val app = context.applicationContext
            val intent = Intent(app, ScreenShareService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            app.startService(Intent(app, ScreenShareService::class.java).setAction(ACTION_STOP))
        }
    }
}
