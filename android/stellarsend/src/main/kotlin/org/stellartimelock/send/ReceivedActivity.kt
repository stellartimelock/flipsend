package org.stellartimelock.send

import android.content.ActivityNotFoundException
import android.os.Build
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.switchmaterial.SwitchMaterial
import org.stellartimelock.send.core.ApkBatch
import org.stellartimelock.send.core.ApkAuto
import org.stellartimelock.send.core.ApkReplacePreview
import org.stellartimelock.send.core.ApkSignatures
import org.stellartimelock.send.core.SelfUpdate
import org.stellartimelock.send.core.ContentTypes
import org.stellartimelock.send.core.ReceiveFile
import org.stellartimelock.send.core.ReceivedEntry
import org.stellartimelock.send.core.ReceivedHistory
import org.stellartimelock.send.core.ListWindow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ReceivedActivity : AppCompatActivity() {
    private var shownInstallPercent = -1
    private var fileLimit = ListWindow.PAGE
    private var lastRowKey = ""
    private val settling = AtomicBoolean(false)
    private val settleHandler = Handler(Looper.getMainLooper())
    private var settleArmed = false
    private val settleTick: Runnable = object : Runnable {
        override fun run() {
            refreshInstalled()
            if (settleArmed) settleHandler.postDelayed(this, 1000)
        }
    }
    private val listener: (SessionEvent) -> Unit = { event: SessionEvent ->
        if (event is SessionEvent.ReceivedChanged || event is SessionEvent.ApkReady) {
            shownInstallPercent = ReceiveLive.snap().installPercent
            runOnUiThread { renderRows() }
        } else if (event is SessionEvent.Receive) {
            val percent: Int = event.snap.installPercent
            if (percent != shownInstallPercent) {
                shownInstallPercent = percent
                runOnUiThread { paintInstallPercent(percent) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SafeDrawing.prepare(this)
        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        head.addView(PageLook.pill(this, "Back", PageLook.Kind.ROW, R.id.received_back).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            text = ""
            contentDescription = "Back"
            setOnClickListener { finish() }
        })
        head.addView(PageLook.title(this, "Received", R.id.received_title).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        column.addView(head)
        column.addView(PageLook.note(this, "Files from your PC land in StellarTimeLock backups/FlipSend."))
        column.addView(PageLook.note(this, ReceivedHistory.EXPLANATION))
        column.addView(PageLook.pill(this, "Install all updates", PageLook.Kind.GOLD, R.id.received_install_all).apply {
            visibility = View.GONE
            setOnClickListener { installAllUpdates() }
        })
        column.addView(PageLook.pill(this, "Install unknown apps", PageLook.Kind.ROW, R.id.install_unknown_apps).apply {
            setOnClickListener { openUnknownApps() }
        })
        column.addView(TextView(this).apply {
            id = R.id.install_unknown_apps_status
            textSize = 16f
            setTextColor(getColor(R.color.dash_muted))
            setPadding(0, dp(4), 0, dp(4))
        })
        column.addView(SwitchMaterial(this).apply {
            id = R.id.auto_install_updates
            text = "Auto-install updates from my PC"
            textSize = 16f
            maxLines = 4
            isSingleLine = false
            setTextColor(getColor(R.color.dash_cream))
            isChecked = InstallPrefs.auto(this@ReceivedActivity)
            setOnCheckedChangeListener { _, checked -> InstallPrefs.setAuto(this@ReceivedActivity, checked) }
        })
        column.addView(LinearLayout(this).apply {
            id = R.id.received_rows
            orientation = LinearLayout.VERTICAL
        })
        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.dash_bg))
            isFillViewport = true
            isScrollContainer = true
            clipToPadding = false
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        SafeDrawing.apply(this)
        refreshPermission()
        renderRows()
    }

    override fun onStart() {
        super.onStart()
        SessionHub.add(listener)
        refreshPermission()
        renderRows()
        refreshInstalled()
    }

    override fun onStop() {
        stopSettle()
        SessionHub.remove(listener)
        super.onStop()
    }

    override fun onDestroy() {
        stopSettle()
        super.onDestroy()
    }

    private fun stopSettle() {
        settleArmed = false
        settleHandler.removeCallbacks(settleTick)
    }

    private fun refreshPermission() {
        val allowed = ApkInstaller.canInstall(this)
        findViewById<TextView>(R.id.install_unknown_apps_status).text = if (allowed) {
            "Install unknown apps is allowed."
        } else {
            "Install unknown apps is off. Tap the button above and allow FlipSend."
        }
    }

    private fun openUnknownApps() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Open Settings and allow FlipSend to install unknown apps.", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderRows() {
        val rows = findViewById<LinearLayout>(R.id.received_rows) ?: return
        val installAllBtn = findViewById<Button>(R.id.received_install_all)
        val log = ReceivedLog(this)
        val items = log.list()

        val apkWithPackage = items.filter { ApkBatch.isApk(it.kind, it.name) && it.packageName.isNotBlank() }
        val apkUnknown = items.filter { ApkBatch.isApk(it.kind, it.name) && it.packageName.isBlank() }
        val nonApks = items.filter { !ApkBatch.isApk(it.kind, it.name) }

        // One entry per package — newest wins (highest incomingCode, then most recently received)
        val cmp = compareBy<ReceivedEntry> { it.incomingCode }.thenBy { it.receivedAtMillis }
        val newestPerPackage = apkWithPackage
            .groupBy { it.packageName }
            .values
            .mapNotNull { group -> group.maxWithOrNull(cmp) }

        val sortedApks = ReceivedHistory.sortedApks(newestPerPackage)
        val olderApks = sortedApks.filter { ReceivedHistory.apkTag(it) == ReceivedHistory.TAG_OLDER }
        val visibleApks = sortedApks.filter { ReceivedHistory.apkTag(it) != ReceivedHistory.TAG_OLDER }
        val filesPage = ListWindow.take(nonApks, fileLimit)
        val rowKey = visibleApks.joinToString { "${it.id}:${it.installStatus}" } +
            "|" + filesPage.shown.joinToString { it.id } +
            "|$fileLimit|${olderApks.size}"
        if (rowKey == lastRowKey && rows.childCount > 0) {
            paintInstallPercent(ReceiveLive.snap().installPercent)
            watchSettle()
            return
        }
        lastRowKey = rowKey
        rows.removeAllViews()

        val actionable = visibleApks.filter { entry ->
            val tag = ReceivedHistory.apkTag(entry)
            tag == ReceivedHistory.TAG_UPDATE || tag == ReceivedHistory.TAG_NOT_INSTALLED
        }

        if (items.isEmpty()) {
            installAllBtn?.visibility = View.GONE
            rows.addView(TextView(this).apply {
                text = "No files yet. When your computer sends something, it shows up here."
                textSize = 16f
                setTextColor(getColor(R.color.dash_cream))
            })
            watchSettle()
            return
        }

        installAllBtn?.visibility = if (actionable.isNotEmpty()) View.VISIBLE else View.GONE

        // APK rows sorted by status
        if (visibleApks.isNotEmpty() || apkUnknown.isNotEmpty()) {
            rows.addView(sectionLabel("Apps"))
            for (entry in visibleApks) rows.addView(apkRow(entry, log))
            for (entry in apkUnknown) rows.addView(row(entry))
        }

        // Older APKs hidden by default behind a toggle
        if (olderApks.isNotEmpty()) {
            val toggleLabel = "Show ${olderApks.size} older APK${if (olderApks.size == 1) "" else "s"}"
            val toggle = PageLook.pill(this, toggleLabel, PageLook.Kind.OUTLINE).apply {
                contentDescription = toggleLabel
            }
            toggle.setOnClickListener { btn ->
                val parent = (btn as View).parent as? LinearLayout ?: return@setOnClickListener
                val idx = parent.indexOfChild(btn)
                parent.removeView(btn)
                olderApks.forEachIndexed { i, old -> parent.addView(apkRow(old, log, isOlder = true), idx + i) }
            }
            rows.addView(toggle)
        }

        // Non-APK files. Page them — a long photo history used to inflate every row.
        if (nonApks.isNotEmpty()) {
            var lastDay = ""
            for (entry in filesPage.shown) {
                val day = dayLabel(entry.receivedAtMillis)
                if (day != lastDay) {
                    lastDay = day
                    rows.addView(sectionLabel(day))
                }
                rows.addView(row(entry))
            }
            if (filesPage.more > 0) {
                rows.addView(moreButton(filesPage.more) {
                    fileLimit = ListWindow.nextLimit(fileLimit)
                    lastRowKey = ""
                    renderRows()
                })
            }
        }
        watchSettle()
    }

    private fun moreButton(more: Int, click: () -> Unit): Button =
        PageLook.pill(this, ListWindow.SHOW_MORE, PageLook.Kind.OUTLINE).apply {
            contentDescription = "${ListWindow.SHOW_MORE}, $more more"
            setOnClickListener { click() }
        }

    private fun paintInstallPercent(percent: Int) {
        val rows = findViewById<LinearLayout>(R.id.received_rows) ?: return
        walk(rows) { view ->
            val desc = view.contentDescription?.toString().orEmpty()
            if (view is android.widget.ProgressBar && desc.startsWith("Install progress bar for ")) {
                view.progress = percent
            } else if (view is TextView && desc.startsWith("Install progress for ")) {
                val name = desc.removePrefix("Install progress for ")
                view.text = org.stellartimelock.send.core.ReceiveStatus.installingLine(name, percent)
            }
        }
    }

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
        }
    }

    private fun sectionLabel(title: String): TextView = TextView(this).apply {
        text = title.uppercase()
        textSize = 13f
        letterSpacing = 0.08f
        setTextColor(getColor(R.color.dash_muted))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(8))
    }

    private fun dayLabel(receivedAtMillis: Long): String {
        val zone = java.util.TimeZone.getDefault()
        val now = java.util.Calendar.getInstance(zone)
        val then = java.util.Calendar.getInstance(zone).apply { timeInMillis = receivedAtMillis }
        val start = java.util.Calendar.getInstance(zone).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val day = start.clone() as java.util.Calendar
        day.add(java.util.Calendar.DAY_OF_YEAR, -1)
        return when {
            then.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
                then.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR) -> "Today"
            then.get(java.util.Calendar.YEAR) == day.get(java.util.Calendar.YEAR) &&
                then.get(java.util.Calendar.DAY_OF_YEAR) == day.get(java.util.Calendar.DAY_OF_YEAR) -> "Yesterday"
            else -> org.stellartimelock.send.core.UsDates.format(receivedAtMillis, zone).substringBefore(" ")
        }
    }

    private fun apkRow(entry: ReceivedEntry, log: ReceivedLog, isOlder: Boolean = false): LinearLayout {
        val tag = if (isOlder) ReceivedHistory.TAG_OLDER else ReceivedHistory.apkTag(entry)
        val card = PageLook.card(this)

        // Header row: icon + name
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        head.addView(AppFace.view(this, ReceivedHistory.asReceiveFile(entry, log.displayPath(entry))))
        val displayName = entry.appName.trim().ifBlank {
            entry.packageName.trim().ifBlank {
                entry.name.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim().ifBlank { "App" }
            }
        }
        head.addView(TextView(this).apply {
            text = displayName
            textSize = 18f
            setTextColor(getColor(R.color.space_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            FitButtons.prepare(this)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        })
        card.addView(head)

        // Version line
        val verLine = buildVersionLine(entry)
        if (verLine.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = verLine
                textSize = 16f
                setTextColor(getColor(R.color.space_muted))
                setPadding(0, dp(4), 0, 0)
            })
        }

        val installing = tag == ReceivedHistory.TAG_INSTALLING
        val percent = if (installing) ReceiveLive.snap().installPercent else 0
        card.addView(TextView(this).apply {
            text = if (installing) ReceivedHistory.installProgressLine(entry, percent) else tag
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(tagColor(tag))
            setPadding(0, dp(4), 0, 0)
            contentDescription = if (installing) "Install progress for $displayName" else "Status: $tag"
        })
        if (installing) {
            card.addView(PageLook.bar(this).apply {
                visibility = View.VISIBLE
                isIndeterminate = false
                max = 100
                progress = percent
                contentDescription = "Install progress bar for $displayName"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            })
        }

        // Install detail (e.g. error reason)
        if (entry.installDetail.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = entry.installDetail
                textSize = 16f
                setTextColor(getColor(R.color.space_muted))
                setPadding(0, dp(4), 0, 0)
            })
        }

        // Action buttons
        val action = when (tag) {
            ReceivedHistory.TAG_UPDATE, ReceivedHistory.TAG_NOT_INSTALLED ->
                chipButton("Install") { startSingleInstall(entry, log) }
            ReceivedHistory.TAG_INSTALLED ->
                chipButton("Clear installed") { clearInstalled(entry, log) }
            ReceivedHistory.TAG_OLDER ->
                chipButton("Install (older version)") { confirmInstallOlder(entry, log) }
            else -> null
        }
        if (action != null) {
            val actions = LinearLayout(this)
            FitButtons.stack(actions, 8, action)
            actions.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            card.addView(actions)
        }
        return card
    }

    private fun buildVersionLine(entry: ReceivedEntry): String {
        val n = entry.incomingVersion.trim()
        val c = entry.incomingCode
        val incoming = when {
            n.isNotEmpty() && c >= 0 -> "$n ($c)"
            n.isNotEmpty() -> n
            c >= 0 -> "version code $c"
            else -> ""
        }
        if (!entry.hasInstalled) return incoming
        val ni = entry.installedVersion.trim()
        val ci = entry.installedCode
        val installed = when {
            ni.isNotEmpty() && ci >= 0 -> "$ni ($ci)"
            ni.isNotEmpty() -> ni
            ci >= 0 -> "version code $ci"
            else -> ""
        }
        return when {
            incoming.isNotEmpty() && installed.isNotEmpty() -> "$incoming · installed $installed"
            incoming.isNotEmpty() -> incoming
            installed.isNotEmpty() -> "installed $installed"
            else -> ""
        }
    }

    private fun tagColor(tag: String): Int = when (tag) {
        ReceivedHistory.TAG_UPDATE -> getColor(R.color.space_accent)
        ReceivedHistory.TAG_INSTALLING -> getColor(R.color.space_accent)
        else -> getColor(R.color.space_muted)
    }

    private fun chipButton(label: String, click: () -> Unit): Button =
        PageLook.pill(this, label).apply { setOnClickListener { click() } }

    private fun startSingleInstall(entry: ReceivedEntry, log: ReceivedLog) {
        val file = log.file(entry)
        if (!file.isFile) {
            Toast.makeText(this, "That file is no longer on this phone.", Toast.LENGTH_LONG).show()
            renderRows()
            return
        }
        installApk(entry, file)
    }

    private fun confirmInstallOlder(entry: ReceivedEntry, log: ReceivedLog) {
        val file = log.file(entry)
        if (!file.isFile) {
            Toast.makeText(this, "That file is no longer on this phone.", Toast.LENGTH_LONG).show()
            renderRows()
            return
        }
        val name = entry.appName.ifBlank { entry.packageName }
        AlertDialog.Builder(this)
            .setTitle("Install older version?")
            .setMessage(
                "$name ${buildVersionLine(entry)} is older than the version already on this phone. " +
                "Installing it will downgrade the app and may cause data problems."
            )
            .setPositiveButton("Install anyway") { _, _ -> installApk(entry, file) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearInstalled(entry: ReceivedEntry, log: ReceivedLog) {
        val removed = log.clearApkEntry(entry.id)
        if (removed != null) runCatching { log.file(removed).delete() }
        SessionHub.emit(SessionEvent.ReceivedChanged)
        renderRows()
    }

    private fun installAllUpdates() {
        val log = ReceivedLog(this)
        val items = log.list()
        val apkWithPackage = items.filter { ApkBatch.isApk(it.kind, it.name) && it.packageName.isNotBlank() }
        val cmpCode = compareBy<ReceivedEntry> { it.incomingCode }.thenBy { it.receivedAtMillis }
        val newestPerPackage = apkWithPackage
            .groupBy { it.packageName }
            .values
            .mapNotNull { group -> group.maxWithOrNull(cmpCode) }
        val actionable = newestPerPackage.filter { entry ->
            val tag = ReceivedHistory.apkTag(entry)
            tag == ReceivedHistory.TAG_UPDATE || tag == ReceivedHistory.TAG_NOT_INSTALLED
        }
        // FlipSend's own package goes last so it can finish the session
        val sorted = actionable.sortedWith(compareBy { if (it.packageName == packageName) 1 else 0 })
        if (sorted.isEmpty()) return
        if (!ApkInstaller.canInstall(this)) {
            Toast.makeText(this, "Allow FlipSend to install unknown apps, then try again.", Toast.LENGTH_LONG).show()
            openUnknownApps()
            return
        }
        for (entry in sorted) {
            if (ReceiveLive.snap().files.none { it.id == entry.id }) {
                val path = log.file(entry).absolutePath
                ReceiveLive.edit { noteFile(entry.id, entry.name, entry.kind, path) }
                ReceiveLive.edit {
                    notePackage(
                        entry.id,
                        appName = entry.appName,
                        packageName = entry.packageName,
                        incomingVersion = entry.incomingVersion,
                        incomingCode = entry.incomingCode,
                        installedVersion = entry.installedVersion,
                        installedCode = entry.installedCode,
                        hasInstalled = entry.hasInstalled,
                        signatureMatch = entry.signatureMatch
                    )
                }
            }
        }
        ApkInstallQueue.start(this, sorted.map { it.id })
    }

    /** Re-check installed versions in the background and refresh the list. */
    private fun refreshInstalled() {
        if (!settling.compareAndSet(false, true)) return
        Thread {
            try {
            val log = ReceivedLog(this)
            var changed = false
            for (entry in log.list()) {
                if (!ApkBatch.isApk(entry.kind, entry.name)) continue

                if (entry.packageName.isBlank()) {
                    // Fill in missing metadata for APKs received before package names were stored.
                    val file = log.file(entry)
                    if (file.isFile) {
                        val incoming = runCatching { ApkArchives.read(this, file) }.getOrNull()
                        if (incoming != null) {
                            val installed = runCatching { ApkArchives.installed(this, incoming.packageName) }.getOrNull()
                            log.noteApp(
                                entry.id,
                                appName = incoming.label.ifBlank { incoming.packageName },
                                packageName = incoming.packageName,
                                incomingVersion = incoming.versionName.orEmpty(),
                                incomingCode = incoming.versionCode,
                                installedVersion = installed?.versionName.orEmpty(),
                                installedCode = installed?.versionCode ?: -1,
                                hasInstalled = installed != null,
                                signatureMatch = ""
                            )
                            changed = true
                        }
                    }
                    continue
                }

                if (entry.signatureMatch == ApkSignatures.DIFFER) continue
                val installed = runCatching { ApkArchives.installed(this, entry.packageName) }.getOrNull()
                if (installed != null) {
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
                    if (installed.versionCode != entry.installedCode || !entry.hasInstalled) {
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
                } else if (entry.hasInstalled && entry.installStatus != "success") {
                    log.noteApp(
                        entry.id,
                        appName = entry.appName,
                        packageName = entry.packageName,
                        incomingVersion = entry.incomingVersion,
                        incomingCode = entry.incomingCode,
                        installedVersion = "",
                        installedCode = -1,
                        hasInstalled = false,
                        signatureMatch = entry.signatureMatch
                    )
                    changed = true
                }
            }

            // Remove older duplicate APK entries for every package and delete their files.
            // This handles both pre-1.5.2 duplicates (now with package names filled above) and
            // any leftovers that slipped past the arrival-time prune.
            val packages = log.list()
                .filter { ApkBatch.isApk(it.kind, it.name) && it.packageName.isNotBlank() }
                .map { it.packageName }
                .distinct()
            for (pkg in packages) {
                val pruned = log.pruneOlderApks(pkg)
                if (pruned.isNotEmpty()) {
                    for (old in pruned) runCatching { log.file(old).delete() }
                    changed = true
                }
            }

            if (changed) {
                SessionHub.emit(SessionEvent.ReceivedChanged)
                runOnUiThread { if (!isDestroyed) renderRows() }
            }
            } finally {
                settling.set(false)
            }
        }.start()
    }

    /** Keep asking PackageManager while a row says it is installing, so a missed callback still flips to Installed. */
    private fun watchSettle() {
        val active = ReceivedLog(this).list().any {
            val status = it.installStatus
            status == "installing" || status == "pending" || status == "replacing"
        }
        if (active && !settleArmed) {
            settleArmed = true
            settleHandler.removeCallbacks(settleTick)
            settleHandler.post(settleTick)
        } else if (!active && settleArmed) {
            stopSettle()
        }
    }

    // Non-APK file row (unchanged behavior)
    private fun row(entry: ReceivedEntry): LinearLayout {
        val apk = ApkBatch.isApk(entry.kind, entry.name)
        val title = if (apk) {
            ApkBatch.displayName(ReceivedHistory.asReceiveFile(entry, ""))
        } else {
            entry.name.ifBlank { "File" }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setOnClickListener { openEntry(entry) }
            contentDescription = "Open ${entry.name}"
        }
        val chip = android.widget.FrameLayout(this).apply {
            background = getDrawable(R.drawable.space_card)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }
        chip.addView(android.widget.ImageView(this).apply {
            setImageResource(android.R.drawable.stat_sys_download_done)
            contentDescription = null
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                gravity = android.view.Gravity.CENTER
            }
        })
        if (apk) {
            row.addView(AppFace.view(this, ReceivedHistory.asReceiveFile(entry, ReceivedLog(this).displayPath(entry))))
        } else {
            row.addView(chip)
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        copy.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(getColor(R.color.dash_cream))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            FitButtons.prepare(this)
            isSingleLine = true
        })
        copy.addView(TextView(this).apply {
            text = ReceivedHistory.summary(entry)
            textSize = 16f
            setTextColor(getColor(R.color.dash_muted))
        })
        if (entry.installDetail.isNotBlank()) {
            copy.addView(TextView(this).apply {
                text = entry.installDetail
                textSize = 16f
                setTextColor(getColor(R.color.dash_cream))
                contentDescription = "Install status for ${entry.name}"
            })
        }
        row.addView(copy)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        wrap.addView(row)
        val actions = LinearLayout(this)
        FitButtons.stack(
            actions,
            8,
            chipButton("Open") { openEntry(entry) },
            chipButton("Share") { share(entry) },
            chipButton("Show in folder") { showInFolder() }
        )
        wrap.addView(actions)
        return wrap
    }

    private fun openEntry(entry: ReceivedEntry) {
        val log = ReceivedLog(this)
        val current = log.find(entry.id) ?: entry
        val file = log.file(current)
        if (!file.exists()) {
            Toast.makeText(this, "That file is no longer on this phone.", Toast.LENGTH_LONG).show()
            renderRows()
            return
        }
        when {
            current.kind == "apk" || ApkReplacePreview.isApk(current.name) -> installApk(current, file)
            current.kind == "text" || current.kind == "folder" || ReceivedHistory.isText(current.name) -> {
                startActivity(Intent(this, TextViewerActivity::class.java).putExtra(TextViewerActivity.EXTRA_ID, current.id))
            }
            else -> viewExternal(current, file)
        }
    }

    private fun installApk(entry: ReceivedEntry, file: File) {
        if (!ApkInstaller.canInstall(this)) {
            Toast.makeText(this, "Allow FlipSend to install unknown apps, then try again.", Toast.LENGTH_LONG).show()
            openUnknownApps()
            return
        }
        val info = runCatching { ApkArchives.read(this, file) }.getOrNull()
        if (info == null) {
            ReceivedLog(this).mark(entry.id, "failed", "Could not read the package name from this APK.")
            renderRows()
            return
        }
        val installed = runCatching { ApkArchives.installed(this, info.packageName) }.getOrNull()
        val bySelf = installed != null && runCatching { ApkArchives.installedBySelf(this, info.packageName) }.getOrDefault(false)
        val match = ApkSignatures.compare(installed?.certificates.orEmpty(), info.certificates)
        val self = info.packageName == packageName
        val eligible = ApkAuto.canUpdateSilently(
            Build.VERSION.SDK_INT,
            alreadyInstalled = installed != null,
            installedBySelf = bySelf,
            signatureMatch = match,
            targetSdk = info.targetSdk,
            updatingSelf = installed != null && self
        )
        val silent = if (self) SelfUpdate.requestSilent(eligible, installerInForeground = true) else eligible
        if (self) {
            SelfUpdateStore.remember(
                this,
                ReceiveFile(
                    id = entry.id,
                    name = file.name,
                    kind = "apk",
                    path = file.absolutePath,
                    appName = info.label.ifBlank { info.packageName },
                    packageName = info.packageName,
                    incomingVersion = info.versionName.orEmpty(),
                    incomingCode = info.versionCode,
                    installedVersion = installed?.versionName.orEmpty(),
                    installedCode = installed?.versionCode ?: -1,
                    hasInstalled = installed != null,
                    signatureMatch = match,
                    targetSdk = info.targetSdk,
                    installedBySelf = bySelf
                )
            )
        }
        val started = runCatching {
            ApkInstaller.commit(
                this,
                file,
                info.packageName,
                historyId = entry.id,
                silentUpdate = silent,
                dontKillApp = self && Build.VERSION.SDK_INT >= 34
            )
        }
        val log = ReceivedLog(this)
        if (started.isFailure) {
            log.mark(entry.id, "failed", started.exceptionOrNull()?.message ?: "Could not start the install.")
        } else {
            val detail = "Installing."
            log.mark(entry.id, "pending", detail, started.getOrThrow())
        }
        renderRows()
    }

    private fun share(entry: ReceivedEntry) {
        val file = ReceivedLog(this).file(entry)
        if (!file.isFile) {
            Toast.makeText(this, "Open a file inside the folder to share it.", Toast.LENGTH_LONG).show()
            return
        }
        val uri = fileUri(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(entry)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, entry.name, uri)
        }
        try {
            startActivity(Intent.createChooser(send, "Share"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Nothing on this phone can share that file.", Toast.LENGTH_LONG).show()
        }
    }

    private fun viewExternal(entry: ReceivedEntry, file: File) {
        val uri = fileUri(file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(entry))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, entry.name, uri)
        }
        try {
            startActivity(Intent.createChooser(view, "Open"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Nothing on this phone can open that file.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showInFolder() {
        val tree = InboxFolder(this).tree()
        if (tree.isNullOrBlank()) {
            Toast.makeText(this, "Files are in ${org.stellartimelock.send.core.PhoneSaveFolder.LABEL}.", Toast.LENGTH_LONG).show()
            return
        }
        val parsed = Uri.parse(tree)
        val document = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(parsed, DocumentsContract.getTreeDocumentId(parsed))
        }.getOrNull()
        if (document == null) {
            Toast.makeText(this, "That received folder is no longer available.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(document, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No files app can open that folder.", Toast.LENGTH_LONG).show()
        }
    }

    private fun fileUri(file: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.stellarsend.files", file)

    private fun mimeFor(entry: ReceivedEntry): String = when {
        ApkReplacePreview.isApk(entry.name) -> "application/vnd.android.package-archive"
        entry.kind == "text" || ReceivedHistory.isText(entry.name) -> "text/plain"
        else -> ContentTypes.of(entry.name)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

class TextViewerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val log = ReceivedLog(this)
        val entry = id?.let { log.find(it) }
        val title = entry?.name ?: "Received file"
        val file = entry?.let { log.file(it) }
        if (entry == null) {
            showPage(title, "That received file is no longer in the list.")
            return
        }
        if (file != null && file.isFile && file.length() <= 32_000L) {
            showPage(title, log.readText(entry).ifBlank { "(This file is empty.)" })
            return
        }
        showPage(title, "Opening…")
        Thread {
            val body = log.readText(entry).ifBlank { "(This file is empty.)" }
            runOnUiThread {
                if (!isDestroyed) findViewById<TextView>(R.id.text_viewer_body)?.text = body
            }
        }.start()
    }

    private fun showPage(title: String, body: String) {
        SafeDrawing.prepare(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTextColor(getColor(R.color.space_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        column.addView(TextView(this).apply {
            id = R.id.text_viewer_body
            text = body
            textSize = 16f
            setTextColor(getColor(R.color.space_text))
            setPadding(0, pad, 0, 0)
            setTextIsSelectable(true)
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
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
