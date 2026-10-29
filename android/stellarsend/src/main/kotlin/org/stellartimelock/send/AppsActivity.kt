package org.stellartimelock.send

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.stellartimelock.send.core.ApkBatch
import org.stellartimelock.send.core.ApkRowActions
import org.stellartimelock.send.core.ApkSignatures
import org.stellartimelock.send.core.ReceiveFile
import org.stellartimelock.send.core.ReceiveStatus
import org.stellartimelock.send.core.ReceivedApps
import org.stellartimelock.send.core.ListWindow
import java.io.File

/**
 * Received apps, newest copy of each package. The attention list is the same screen
 * filtered to rows that are not installed, plus older copies that were hidden.
 */
class AppsActivity : AppCompatActivity() {
    private var shownLimit = ListWindow.PAGE
    private var hiddenLimit = ListWindow.PAGE
    private val replaceFlow = ApkReplaceFlow(this) { text ->
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
    private val listener: (SessionEvent) -> Unit = { event ->
        if (event is SessionEvent.Receive || event is SessionEvent.ReceivedChanged) {
            runOnUiThread { render() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SafeDrawing.prepare(this)
        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(PageLook.title(this, if (attentionOnly()) "Apps" else "Apps", R.id.apps_title))
        column.addView(PageLook.note(this, if (attentionOnly()) {
            "Apps from your computer that still need a look."
        } else {
            "Apps from your computer. Incoming ones install themselves."
        }))
        column.addView(LinearLayout(this).apply {
            id = R.id.apps_rows
            orientation = LinearLayout.VERTICAL
        })
        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.space_bg))
            isFillViewport = true
            isScrollContainer = true
            clipToPadding = false
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        SafeDrawing.apply(this)
        render()
    }

    override fun onStart() {
        super.onStart()
        SessionHub.add(listener)
        replaceFlow.attach()
        render()
        refreshInstalled()
    }

    override fun onStop() {
        replaceFlow.detach()
        SessionHub.remove(listener)
        super.onStop()
    }

    private fun attentionOnly(): Boolean = intent.getBooleanExtra(EXTRA_ATTENTION, false)

    private fun files(): List<ReceiveFile> {
        val log = ReceivedLog(this)
        return ReceivedApps.merge(log.list(), ReceiveLive.snap().files) { entry ->
            log.displayPath(entry)
        }
    }

    private fun render() {
        val rows = findViewById<LinearLayout>(R.id.apps_rows) ?: return
        rows.removeAllViews()
        val all = files()
        val offered = ApkBatch.offered(all)
        val shown = if (attentionOnly()) offered.filter { ApkBatch.status(it) != ApkBatch.INSTALLED } else offered
        val hidden = ApkBatch.hidden(all)
        if (shown.isEmpty() && hidden.isEmpty()) {
            rows.addView(body(if (attentionOnly()) {
                "Nothing needs attention. You're all set."
            } else {
                "No apps yet. When your computer sends an app, it shows up here with its name and icon."
            }))
            return
        }
        val shownPage = ListWindow.take(shown, shownLimit)
        shownPage.shown.forEach { file -> rows.addView(card(file, duplicate = false)) }
        if (shownPage.more > 0) {
            rows.addView(PageLook.pill(this, ListWindow.SHOW_MORE).apply {
                contentDescription = "${ListWindow.SHOW_MORE}, ${shownPage.more} more"
                setOnClickListener {
                    shownLimit = ListWindow.nextLimit(shownLimit)
                    render()
                }
            })
        }
        if (hidden.isNotEmpty()) {
            rows.addView(body("Older copies hidden").apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(16), 0, 0)
            })
            val hiddenPage = ListWindow.take(hidden, hiddenLimit)
            hiddenPage.shown.forEach { file -> rows.addView(card(file, duplicate = true)) }
            if (hiddenPage.more > 0) {
                rows.addView(PageLook.pill(this, ListWindow.SHOW_MORE).apply {
                    contentDescription = "${ListWindow.SHOW_MORE}, ${hiddenPage.more} older"
                    setOnClickListener {
                        hiddenLimit = ListWindow.nextLimit(hiddenLimit)
                        render()
                    }
                })
            }
        }
    }

    private fun card(file: ReceiveFile, duplicate: Boolean): LinearLayout {
        val card = PageLook.card(this)
        val name = ApkBatch.displayName(file)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        head.addView(AppFace.view(this, file))
        head.addView(body(name).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            FitButtons.prepare(this)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        })
        card.addView(head)
        card.addView(body(ApkBatch.versionLine(file)).apply { setPadding(0, dp(4), 0, 0) })
        val line = ApkBatch.statusLine(file, duplicate)
        card.addView(body(line).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        })
        val why = ApkBatch.explanation(file, duplicate)
        if (why.isNotBlank() && !line.contains(why)) {
            card.addView(body(why).apply { setPadding(0, dp(4), 0, 0) })
        }
        val actions = ApkRowActions.labels(file, packageName, duplicate)
        if (actions.isNotEmpty()) {
            val bar = LinearLayout(this)
            val buttons = actions.map { action ->
                PageLook.pill(this, action).apply {
                    contentDescription = "$action $name"
                    setOnClickListener { onAction(file, action) }
                }
            }
            FitButtons.stack(bar, 8, *buttons.toTypedArray())
            card.addView(bar)
        }
        return card
    }

    private fun onAction(file: ReceiveFile, action: String) {
        when (action) {
            ApkRowActions.ALLOW -> openUnknownApps()
            ApkRowActions.CLEAN_REPLACE -> cleanReplace(file)
            ApkRowActions.INSTALL -> install(file)
        }
    }

    private fun install(file: ReceiveFile) {
        ReceivedApkInstall.start(this, file) { render() }
    }

    private fun cleanReplace(file: ReceiveFile) {
        val disk = File(file.path)
        if (!disk.isFile) {
            Toast.makeText(this, "That file is no longer on this phone.", Toast.LENGTH_LONG).show()
            return
        }
        replaceFlow.offerCleanReplace(disk.absolutePath)
    }

    private fun openUnknownApps() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Open Settings and allow FlipSend to install unknown apps.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * If the phone already has this version or a newer one, and the signature is not different,
     * the row is Installed. A different signature stays in the attention list.
     */
    private fun refreshInstalled() {
        Thread {
            val log = ReceivedLog(this)
            var changed = false
            for (entry in log.list()) {
                if (!ApkBatch.isApk(entry.kind, entry.name) || entry.packageName.isBlank()) continue
                if (entry.signatureMatch == ApkSignatures.DIFFER) continue
                val installed = runCatching { ApkArchives.installed(this, entry.packageName) }.getOrNull() ?: continue
                val newerOrSame = entry.incomingCode >= 0 && installed.versionCode >= entry.incomingCode
                val active = entry.installStatus == "installing" ||
                    entry.installStatus == "pending" ||
                    entry.installStatus == "replacing"
                if (newerOrSame && active) {
                    if (ApkInstallMarks.settledByPackage(this, entry.id)) changed = true
                } else if (newerOrSame && entry.installStatus != "success") {
                    log.mark(entry.id, "success", "")
                    changed = true
                }
                if (installed.versionCode != entry.installedCode || entry.hasInstalled != true) {
                    log.noteApp(
                        entry.id,
                        appName = entry.appName.ifBlank { installed.label.ifBlank { entry.packageName } },
                        packageName = entry.packageName,
                        incomingVersion = entry.incomingVersion,
                        incomingCode = entry.incomingCode,
                        installedVersion = installed.versionName.orEmpty(),
                        installedCode = installed.versionCode,
                        hasInstalled = true,
                        signatureMatch = entry.signatureMatch
                    )
                    changed = true
                }
            }
            if (changed) {
                SessionHub.emit(SessionEvent.ReceivedChanged)
                runOnUiThread { render() }
            }
        }.start()
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ReceiveStatus.MIN_SP)
        setTextColor(getColor(R.color.space_text))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ATTENTION = "attention"

        fun intent(context: Context, attention: Boolean): Intent =
            Intent(context, AppsActivity::class.java).putExtra(EXTRA_ATTENTION, attention)
    }
}
