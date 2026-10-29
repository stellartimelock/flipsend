package org.stellartimelock.send

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.stellartimelock.send.core.ErrorCodes
import org.stellartimelock.send.core.RemoteControl
import org.stellartimelock.send.core.TrustNewKey
import org.stellartimelock.send.core.RemoteDesktop
import org.stellartimelock.send.core.RemoteInput
import org.stellartimelock.send.core.RemotePointer
import org.stellartimelock.send.core.RemoteTouch
import org.stellartimelock.send.core.RemoteViewport
import org.stellartimelock.send.core.TouchStep
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class PcScreenActivity : AppCompatActivity() {
    private val client by lazy { StellarSendClient(this) }
    private val running = AtomicBoolean(true)
    private val viewport = RemoteViewport()
    private val touch = RemoteTouch()
    private var session = ""
    private var imageW = 0
    private var imageH = 0

    private val longPress = Runnable {
        val image = findViewById<ImageView>(R.id.pcScreenImage) ?: return@Runnable
        dispatch(image, touch.hold(SystemClock.uptimeMillis()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RemoteDesktopBuild.enabled(this)) {
            finish()
            return
        }
        setContentView(R.layout.activity_pc_screen)
        SafeDrawing.prepare(this)
        SafeDrawing.pinRemote(
            findViewById(R.id.pcScreenRoot),
            findViewById(R.id.pcScreenTop),
            findViewById(R.id.pcKeyboardBar)
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                disconnect()
            }
        })
        findViewById<Button>(R.id.pcScreenStop).setOnClickListener { disconnect() }
        findViewById<Button>(R.id.pcKeyboard).setOnClickListener { toggleKeyboard() }
        findViewById<Button>(R.id.pcScreenSendText).setOnClickListener { sendText() }
        findViewById<EditText>(R.id.pcScreenType).setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendText()
                true
            } else {
                false
            }
        }
        val image = findViewById<ImageView>(R.id.pcScreenImage)
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (imageW <= 0 || image.width <= 0) return false
                viewport.pinch(
                    detector.scaleFactor.toDouble(),
                    detector.focusX.toDouble(),
                    detector.focusY.toDouble(),
                    image.width.toDouble(),
                    image.height.toDouble(),
                    imageW.toDouble(),
                    imageH.toDouble()
                )
                applyMatrix(image)
                return true
            }
        })
        image.setOnTouchListener { view, event ->
            scale.onTouchEvent(event)
            val picture = view as ImageView
            val (cx, cy) = centroid(event)
            val zoom = viewport.zoom
            val steps = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.postDelayed(longPress, RemotePointer.LONG_PRESS_MS)
                    touch.down(cx, cy, event.eventTime)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    view.removeCallbacks(longPress)
                    if (scale.isInProgress) touch.pinch() else touch.extraFinger(cx, cy)
                }
                MotionEvent.ACTION_MOVE ->
                    if (scale.isInProgress) touch.pinch() else touch.move(cx, cy, event.pointerCount, zoom, event.eventTime)
                MotionEvent.ACTION_POINTER_UP ->
                    touch.pointerUp(event.pointerCount - 1, cx, cy)
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    touch.up(cx, cy, event.eventTime, zoom)
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPress)
                    touch.cancel()
                }
                else -> emptyList()
            }
            dispatch(picture, steps)
            true
        }
        Thread {
            var attempt = 0
            while (running.get() && !isFinishing) {
                val opened = client.openDesktop({ !running.get() }, { id ->
                    session = id
                    runOnUiThread {
                        if (!isFinishing) findViewById<TextView>(R.id.pcScreenStatus).text = RemoteDesktop.WAITING
                    }
                }) { jpeg ->
                    if (session.isEmpty()) session = client.remoteSessionId().orEmpty()
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@openDesktop
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        imageW = bitmap.width
                        imageH = bitmap.height
                        val view = findViewById<ImageView>(R.id.pcScreenImage)
                        view.setImageBitmap(bitmap)
                        view.post { applyMatrix(view) }
                        findViewById<TextView>(R.id.pcScreenStatus).text = RemoteDesktop.SHOWING
                    }
                }
                if (!running.get() || isFinishing) return@Thread
                if (opened.ok) {
                    val done = RemoteDesktop.explain(opened.code, opened.message)
                    runOnUiThread {
                        if (!isFinishing) findViewById<TextView>(R.id.pcScreenStatus).text = done
                    }
                    return@Thread
                }
                val reason = RemoteDesktop.explain(opened.code, opened.message)
                val offer = client.pendingTrust()
                if (offer != null && offer.fingerprint.isNotBlank() &&
                    TrustNewKey.shouldPrompt(true, opened.code, opened.message, offer.fingerprint, null)
                ) {
                    offerTrust()
                    return@Thread
                }
                if (RemoteDesktop.autoRetry(attempt, !running.get(), opened.code, opened.message) && running.get()) {
                    attempt++
                    continue
                }
                showReason(reason)
                return@Thread
            }
        }.start()
    }

    private fun offerTrust() {
        val name = client.pendingTrust()?.name
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            val line = TrustNewKey.message(name)
            findViewById<TextView>(R.id.pcScreenStatus).text = line
            MaterialAlertDialogBuilder(this)
                .setTitle(TrustNewKey.TITLE)
                .setMessage(line)
                .setPositiveButton(TrustNewKey.ACTION) { _, _ ->
                    if (client.trustNewKey() && running.get() && !isFinishing) {
                        retryDesktop()
                    }
                }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    private fun retryDesktop() {
        Thread {
            if (!running.get() || isFinishing) return@Thread
            val opened = client.openDesktop({ !running.get() }, { id ->
                session = id
                runOnUiThread {
                    if (!isFinishing) findViewById<TextView>(R.id.pcScreenStatus).text = RemoteDesktop.WAITING
                }
            }) { jpeg ->
                if (session.isEmpty()) session = client.remoteSessionId().orEmpty()
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@openDesktop
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    imageW = bitmap.width
                    imageH = bitmap.height
                    val view = findViewById<ImageView>(R.id.pcScreenImage)
                    view.setImageBitmap(bitmap)
                    view.post { applyMatrix(view) }
                    findViewById<TextView>(R.id.pcScreenStatus).text = RemoteDesktop.SHOWING
                }
            }
            if (!running.get() || isFinishing) return@Thread
            val reason = RemoteDesktop.explain(opened.code, opened.message)
            runOnUiThread {
                if (!isFinishing) findViewById<TextView>(R.id.pcScreenStatus).text = if (opened.ok) reason else reason
            }
        }.start()
    }

    private fun showReason(reason: String) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            findViewById<TextView>(R.id.pcScreenStatus).text = reason
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        running.set(false)
        findViewById<ImageView>(R.id.pcScreenImage)?.removeCallbacks(longPress)
        client.stopRemote()
        super.onDestroy()
    }

    private fun disconnect() {
        running.set(false)
        client.stopRemote()
        finish()
    }

    private fun toggleKeyboard() {
        val bar = findViewById<LinearLayout>(R.id.pcKeyboardBar)
        val box = findViewById<EditText>(R.id.pcScreenType)
        val show = bar.visibility != android.view.View.VISIBLE
        bar.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        val imm = getSystemService(InputMethodManager::class.java)
        if (show) {
            box.requestFocus()
            box.post { imm.showSoftInput(box, InputMethodManager.SHOW_IMPLICIT) }
        } else {
            imm.hideSoftInputFromWindow(box.windowToken, 0)
        }
    }

    private fun sendText() {
        val box = findViewById<EditText>(R.id.pcScreenType)
        val text = box.text?.toString().orEmpty()
        if (text.isBlank()) return
        push(RemoteInput("text", session.ifBlank { UUID.randomUUID().toString() }, text = text.take(RemoteControl.MAX_TEXT_CHARS)))
        box.text = null
    }

    private fun mapPoint(x: Float, y: Float): Pair<Double, Double>? {
        val view = findViewById<ImageView>(R.id.pcScreenImage)
        if (imageW <= 0 || view.width <= 0) return null
        return viewport.point(x.toDouble(), y.toDouble(), view.width.toDouble(), view.height.toDouble(), imageW.toDouble(), imageH.toDouble())
    }

    private fun applyMatrix(view: ImageView) {
        val place = viewport.placement(view.width.toDouble(), view.height.toDouble(), imageW.toDouble(), imageH.toDouble()) ?: return
        val matrix = Matrix()
        val sx = (place.drawnW / imageW).toFloat()
        val sy = (place.drawnH / imageH).toFloat()
        matrix.postScale(sx, sy)
        matrix.postTranslate(place.left.toFloat(), place.top.toFloat())
        view.imageMatrix = matrix
        view.scaleType = ImageView.ScaleType.MATRIX
    }

    private fun centroid(event: MotionEvent): Pair<Double, Double> {
        var sx = 0.0
        var sy = 0.0
        val count = event.pointerCount.coerceAtLeast(1)
        for (i in 0 until count) {
            sx += event.getX(i)
            sy += event.getY(i)
        }
        return sx / count to sy / count
    }

    private fun dispatch(view: ImageView, steps: List<TouchStep>) {
        for (step in steps) {
            if (step.kind == "pan") {
                viewport.panBy(
                    step.dx,
                    step.dy,
                    view.width.toDouble(),
                    view.height.toDouble(),
                    imageW.toDouble(),
                    imageH.toDouble()
                )
                applyMatrix(view)
                continue
            }
            val mapped = mapPoint(step.x.toFloat(), step.y.toFloat()) ?: continue
            val event = if (step.kind == "scroll") {
                RemoteInput("scroll", session, mapped.first, mapped.second, dy = step.dy)
            } else {
                RemoteInput(step.kind, session, mapped.first, mapped.second)
            }
            push(event)
        }
    }

    private fun push(event: RemoteInput) {
        val id = session.ifBlank { event.session }
        client.enqueueRemoteInput(event.copy(session = id))
    }
}
