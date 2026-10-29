package org.stellartimelock.send

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.stellartimelock.send.core.ApkBatch
import org.stellartimelock.send.core.ReceiveFile
import java.io.File
import java.util.concurrent.Executors

/**
 * App icon, or a letter on the FlipSend card color when the icon cannot be read.
 *
 * Icons are loaded asynchronously so that calling code never blocks the main thread on
 * APK disk I/O or package-manager IPC. A small LruCache keeps recently-seen drawables in
 * memory so repeated renderRows() calls are instant after the first load.
 */
internal object AppFace {
    private val cache = LruCache<String, Drawable>(20)
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "appface-icon").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Drop a cached drawable (e.g. after an install changes the icon). */
    fun invalidate(packageName: String) {
        if (packageName.isNotBlank()) cache.remove(packageName)
    }

    fun view(context: android.content.Context, file: ReceiveFile): View {
        val size = (48 * context.resources.displayMetrics.density).toInt()
        val frame = FrameLayout(context)
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = ApkBatch.displayName(file)
            visibility = View.GONE
        }
        val letter = TextView(context).apply {
            gravity = Gravity.CENTER
            text = ApkBatch.displayName(file).firstOrNull()?.uppercaseChar()?.toString() ?: "A"
            setTextColor(context.getColor(R.color.space_text))
            textSize = 20f
            contentDescription = null
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(R.color.space_quiet))
            }
        }
        frame.addView(icon, FrameLayout.LayoutParams(size, size))
        frame.addView(letter, FrameLayout.LayoutParams(size, size))
        frame.layoutParams = android.widget.LinearLayout.LayoutParams(size, size)

        val cacheKey = file.packageName.ifBlank { file.path }
        if (cacheKey.isBlank()) return frame

        val cached = cache.get(cacheKey)
        if (cached != null) {
            icon.setImageDrawable(cached)
            icon.visibility = View.VISIBLE
            letter.visibility = View.GONE
            return frame
        }

        val appContext = context.applicationContext
        val apkPath = file.path
        val packageName = file.packageName
        executor.execute {
            val drawable = runCatching {
                ApkArchives.icon(appContext, packageName, File(apkPath).takeIf { it.isFile })
            }.getOrNull()
            if (drawable != null) cache.put(cacheKey, drawable)
            mainHandler.post {
                if (drawable != null) {
                    icon.setImageDrawable(drawable)
                    icon.visibility = View.VISIBLE
                    letter.visibility = View.GONE
                }
            }
        }

        return frame
    }
}
