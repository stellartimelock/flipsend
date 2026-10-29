package org.stellartimelock.send

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.stellartimelock.send.core.ApkBatch
import org.stellartimelock.send.core.ContentTypes
import org.stellartimelock.send.core.ReceiveFile
import org.stellartimelock.send.core.ReceiveSnap
import org.stellartimelock.send.core.ReceiveStatus
import org.stellartimelock.send.core.ReceivedHistory
import java.io.File

/** Shared batch state for the screen shown while files are arriving. */
object ReceiveLive {
    private val board = org.stellartimelock.send.core.ReceiveBoard()
    private val gate = Any()

    fun edit(block: org.stellartimelock.send.core.ReceiveBoard.() -> Unit): ReceiveSnap = synchronized(gate) {
        board.block()
        board.snap()
    }

    fun snap(): ReceiveSnap = synchronized(gate) { board.snap() }

    fun clear() {
        synchronized(gate) { board.clear() }
    }
}

class ReceivingActivity : AppCompatActivity() {
    private lateinit var page: ReceivePage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = ReceivePage(this)
        page.install()
        page.render(ReceiveLive.snap())
    }

    override fun onStart() {
        super.onStart()
        resumed = true
        page.bind()
        page.render(ReceiveLive.snap())
    }

    override fun onStop() {
        resumed = false
        page.unbind()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && !ApkInstallQueue.running) ReceiveLive.clear()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var resumed: Boolean = false

        fun show(context: android.content.Context) {
            val app = context.applicationContext
            val launch = Runnable {
                if (resumed) return@Runnable
                val intent = Intent(app, ReceivingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                runCatching { app.startActivity(intent) }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) launch.run() else Handler(Looper.getMainLooper()).post(launch)
        }
    }
}

/**
 * One status, one moving bar. Apps from the paired PC install themselves.
 * Photos and other non-APKs can still be opened. No Install / Clean pile.
 */
internal class ReceivePage(
    private val activity: AppCompatActivity
) {
    private var statusView: TextView? = null
    private var senderView: TextView? = null
    private var noteView: TextView? = null
    private var barView: ProgressBar? = null
    private var rowsView: LinearLayout? = null
    private val listener: (SessionEvent) -> Unit = { event ->
        if (event is SessionEvent.Receive) {
            activity.runOnUiThread { render(event.snap) }
        }
    }

    fun install() {
        SafeDrawing.prepare(activity)
        val pad = PageLook.dp(activity, 20)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(PageLook.title(activity, "Receiving"))
        statusView = PageLook.status(activity, ReceiveStatus.READY, R.id.receive_status)
        column.addView(statusView)
        senderView = PageLook.note(activity, ReceiveStatus.senderLine(""), R.id.receive_sender)
        column.addView(senderView)
        barView = PageLook.bar(activity, R.id.receive_bar)
        column.addView(barView)
        noteView = PageLook.note(activity, "Apps from your PC install themselves.")
        column.addView(noteView)
        rowsView = LinearLayout(activity).apply {
            id = R.id.receive_rows
            orientation = LinearLayout.VERTICAL
        }
        column.addView(rowsView)
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(activity.getColor(R.color.space_bg))
            isFillViewport = true
            isScrollContainer = true
            clipToPadding = false
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        activity.setContentView(scroll)
        SafeDrawing.apply(activity)
        render(ReceiveLive.snap())
    }

    fun bind() {
        SessionHub.add(listener)
    }

    fun unbind() {
        SessionHub.remove(listener)
    }

    fun render(snap: ReceiveSnap) {
        statusView?.text = snap.barStatus.ifBlank { ReceiveStatus.READY }
        senderView?.text = snap.senderLine.ifBlank { ReceiveStatus.senderLine("") }
        senderView?.visibility = if (snap.sender.isBlank() && snap.files.isEmpty() && !snap.receiving) View.GONE else View.VISIBLE
        barView?.apply {
            visibility = if (snap.showBar) View.VISIBLE else View.GONE
            isIndeterminate = false
            progress = snap.barPercent
        }
        val extras = snap.files.filter { !ApkBatch.isApk(it) }
        noteView?.visibility = if (snap.files.any { ApkBatch.isApk(it) } || snap.installing) View.VISIBLE else View.GONE
        val rows = rowsView ?: return
        rows.removeAllViews()
        extras.forEach { file -> rows.addView(fileCard(file)) }
    }

    private fun fileCard(file: ReceiveFile): LinearLayout {
        val card = PageLook.card(activity)
        val title = file.name.ifBlank { "File" }
        card.addView(PageLook.text(activity, title, ReceiveStatus.MIN_SP, bold = true, muted = false))
        val open = PageLook.pill(activity, "Open")
        open.contentDescription = "Open ${file.name}"
        open.setOnClickListener { openFile(file) }
        card.addView(open)
        return card
    }

    private fun openFile(file: ReceiveFile) {
        val disk = File(file.path)
        if (!disk.exists()) {
            Toast.makeText(activity, "That file is no longer on this phone.", Toast.LENGTH_LONG).show()
            return
        }
        if (file.kind == "text" || file.kind == "folder" || ReceivedHistory.isText(file.name)) {
            activity.startActivity(Intent(activity, TextViewerActivity::class.java).putExtra(TextViewerActivity.EXTRA_ID, file.id))
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.stellarsend.files", disk)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ContentTypes.of(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(activity.contentResolver, file.name, uri)
        }
        try {
            activity.startActivity(Intent.createChooser(view, "Open"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "Nothing on this phone can open that file.", Toast.LENGTH_LONG).show()
        }
    }
}
