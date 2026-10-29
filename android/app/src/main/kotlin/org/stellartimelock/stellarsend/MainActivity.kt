package org.stellartimelock.stellarsend

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.core.content.FileProvider
import org.stellartimelock.send.AppsActivity
import org.stellartimelock.send.ApkReplaceFlow
import org.stellartimelock.send.FitButtons
import org.stellartimelock.send.BackgroundListen
import org.stellartimelock.send.ReceiveLive
import org.stellartimelock.send.ReceivedActivity
import org.stellartimelock.send.SafeDrawing
import org.stellartimelock.send.SendChooser
import org.stellartimelock.send.SendRequest
import org.stellartimelock.send.showInSafeArea
import org.stellartimelock.send.showRepairPrompt
import org.stellartimelock.send.showTrustPrompt
import org.stellartimelock.send.core.RepairPrompt
import org.stellartimelock.send.core.TrustNewKey
import org.stellartimelock.send.RelayUpgrade
import org.stellartimelock.send.SessionEvent
import org.stellartimelock.send.core.RelayPlans
import org.stellartimelock.send.core.RelayQuotaException
import org.stellartimelock.send.SessionHub
import org.stellartimelock.send.ConsentPrompt
import org.stellartimelock.send.PhoneInbox
import org.stellartimelock.send.StellarSendClient
import org.stellartimelock.send.TreeZip
import org.stellartimelock.send.ChosenFile
import org.stellartimelock.send.ChosenItem
import org.stellartimelock.send.core.PhoneSaveFolder
import org.stellartimelock.send.core.ApkReplacePreview
import org.stellartimelock.send.core.ContentTypes
import org.stellartimelock.send.core.FolderArchive
import org.stellartimelock.send.core.ConnectionInput
import org.stellartimelock.send.core.ConnectionMark
import org.stellartimelock.send.core.ConnectionMethod
import org.stellartimelock.send.core.ConnectionMethods
import org.stellartimelock.send.core.ConnectionState
import org.stellartimelock.send.core.HomeApps
import org.stellartimelock.send.core.LiveSend
import org.stellartimelock.send.core.ReceiveFile
import org.stellartimelock.send.core.ReceivedApps
import org.stellartimelock.send.core.TransferQueue
import org.stellartimelock.send.ReceivedApkInstall
import org.stellartimelock.send.ReceivedLog
import org.stellartimelock.send.core.OverwriteKind
import org.stellartimelock.send.core.PayloadKind
import org.stellartimelock.send.core.ShortCode
import org.stellartimelock.send.core.TerminalPolicy
import org.stellartimelock.send.core.TransferState
import java.io.File

class MainActivity : AppCompatActivity() {
    private enum class Page { HOME, SETTINGS, TERMINAL }

    private val client by lazy { StellarSendClient(this) }
    private var liveSend: LiveSend? = null
    private var savedCommands = listOf<String>()
    private var terminalRunning = false
    private var currentPage = Page.HOME
    private var trustPromptShown = false
    private val apkReplace = ApkReplaceFlow(this) { text ->
        findViewById<TextView>(R.id.progress).text = text
    }
    private val files = linkedMapOf<String, ChosenItem>()
    private val listener: (SessionEvent) -> Unit = { event -> runOnUiThread { onEvent(event) } }
    private val tvCast by lazy {
        org.stellartimelock.send.TvCastSession(this) { status -> runOnUiThread { renderCast(status) } }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val chosen = ChosenFile.fromUri(this, uri)
            files[chosen.name] = chosen
            renderFiles()
        }
    }

    private val scan = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        Thread {
            val error = runCatching { client.pairFromCode(contents) }.exceptionOrNull()
            runOnUiThread {
                toastUnlessQuota(error)
                renderStatus()
            }
        }.start()
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askInstallPermission()
        renderBluetoothChecklist()
    }

    private var folderPromptOpen = false
    private val pickInbox = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        folderPromptOpen = false
        if (uri == null) {
            PhoneInbox.markPrompted(this)
            return@registerForActivityResult
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        PhoneInbox.acceptPickedTree(this, uri)
        applyInbox(uri.toString())
    }

    private val pickSendFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val cache = File(cacheDir, "outgoing").apply { mkdirs() }
            val zip = File(cache, "folder.stellarsend-folder.zip")
            val error = runCatching { TreeZip.zip(this, uri, zip) }.exceptionOrNull()
            runOnUiThread {
                if (error != null || !zip.exists()) {
                    Toast.makeText(this, error?.message ?: "Could not read that folder.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val content = FileProvider.getUriForFile(this, "$packageName.stellarsend.files", zip)
                SendChooser.show(
                    this,
                    client,
                    SendRequest(PayloadKind.FILE, "Folder.stellarsend-folder.zip", uri = content, mimeType = FolderArchive.MIME)
                )
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SafeDrawing.prepare(this)
        setContentView(R.layout.activity_main)
        SafeDrawing.apply(this)
        findViewById<Button>(R.id.showCode).setOnClickListener { enlargeQr() }
        findViewById<Button>(R.id.sendToIphone).setOnClickListener { showWeb() }
        findViewById<Button>(R.id.shareFlipSend).setOnClickListener {
            runCatching { startActivity(org.stellartimelock.send.InviteShare.compose(this)) }
        }
        findViewById<Button>(R.id.enterInvite).setOnClickListener { enterInvite() }
        findViewById<Button>(R.id.scanCode).setOnClickListener { scanCode() }
        findViewById<Button>(R.id.pasteCode).setOnClickListener { pasteCode() }
        findViewById<Button>(R.id.unpair).setOnClickListener { confirmForget() }
        findViewById<Button>(R.id.rotate).setOnClickListener { startRepairFromPrompt() }
        findViewById<Button>(R.id.sendText).setOnClickListener { sendText() }
        findViewById<Button>(R.id.addFile).setOnClickListener { pickFile.launch(ContentTypes.PICK) }
        findViewById<Button>(R.id.shareScreen).setOnClickListener { toggleScreenShare() }
        findViewById<Button>(R.id.castToTv).setOnClickListener { tvCast.toggle() }
        findViewById<Button>(R.id.saveHost).setOnClickListener { saveHost() }
        val openReceived = {
            startActivity(Intent(this, ReceivedActivity::class.java))
        }
        findViewById<Button>(R.id.openReceived).setOnClickListener { openReceived() }
        findViewById<Button>(R.id.openReceivedSettings).setOnClickListener { openReceived() }
        wireTile(R.id.tileSend, R.id.addFile)
        wireTile(R.id.tileApps, R.id.tabApps)
        wireTile(R.id.tileReceived, R.id.openReceived)
        findViewById<Button>(R.id.homeAppsMore).setOnClickListener {
            appsShown = if (homeAppsMoreCount == 0 && appsShown > HomeApps.PREVIEW) {
                HomeApps.PREVIEW
            } else {
                org.stellartimelock.send.core.ListWindow.nextLimit(appsShown, HomeApps.PAGE, HomeApps.PREVIEW)
            }
            lastHomeAppsKey = ""
            renderHomeApps()
        }
        findViewById<Button>(R.id.sendClipboard).setOnClickListener { sendClipboard() }
        findViewById<Button>(R.id.sendFolder).setOnClickListener { pickSendFolder.launch(null) }
        findViewById<Button>(R.id.restoreSave).setOnClickListener { restoreSave() }
        KeyboardScroll.attach(findViewById(R.id.scroller))
        findViewById<Button>(R.id.openOnPc).setOnClickListener { openOnPc() }
        val listen = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.listenBackground)
        listen.isChecked = BackgroundListen.enabled(this)
        listen.setOnCheckedChangeListener { _, checked -> BackgroundListen.setEnabled(this, checked) }
        val phoneControl = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.phoneControl)
        phoneControl.isChecked = org.stellartimelock.send.RemoteControlPrefs.phoneControl(this)
        phoneControl.setOnCheckedChangeListener { _, checked ->
            org.stellartimelock.send.RemoteControlPrefs.setPhoneControl(this, checked)
            applyRemoteAccess()
        }
        findViewById<Button>(R.id.openAccessibility).setOnClickListener { openPhoneControlSettings() }
        findViewById<Button>(R.id.setupBluetooth).setOnClickListener { openBluetoothSetup() }
        findViewById<Button>(R.id.openRestricted).setOnClickListener { openRestrictedSettings() }
        val openDesktop = {
            if (BuildConfig.REMOTE_DESKTOP) {
                startActivity(Intent(this, org.stellartimelock.send.PcScreenActivity::class.java))
            }
        }
        findViewById<Button>(R.id.controlPc).setOnClickListener { openDesktop() }
        findViewById<Button>(R.id.openRemoteDesktop).setOnClickListener { openDesktop() }
        findViewById<Button>(R.id.remoteBannerStop).setOnClickListener {
            client.stopRemote()
            applyRemoteAccess()
        }
        applyRemoteAccess()
        findViewById<Button>(R.id.hideListeningNotification).setOnClickListener {
            BackgroundListen.openChannelSettings(this)
        }
        findViewById<Button>(R.id.batteryOptButton).setOnClickListener { requestBatteryOptExemption() }
        applyStoreGates()
        setupOneplusGuidance()
        findViewById<EditText>(R.id.relayUrl).setText(BackgroundListen.relayUrl(this).orEmpty())
        findViewById<EditText>(R.id.relayAccount).setText(org.stellartimelock.send.PlanStatus.accountEmail(this))
        findViewById<EditText>(R.id.relayAccountKey).setText(org.stellartimelock.send.PlanStatus.accountSecret(this))
        findViewById<Button>(R.id.saveRelay).setOnClickListener {
            BackgroundListen.setRelayUrl(this, findViewById<EditText>(R.id.relayUrl).text.toString())
            org.stellartimelock.send.PlanStatus.rememberAccount(
                this,
                findViewById<EditText>(R.id.relayAccount).text.toString(),
                findViewById<EditText>(R.id.relayAccountKey).text.toString()
            )
            Toast.makeText(this, "Relay address saved.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.tabTransfer).setOnClickListener { showPage(Page.SETTINGS) }
        findViewById<Button>(R.id.tabTerminal).setOnClickListener { showPage(Page.TERMINAL) }
        findViewById<Button>(R.id.tabApps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        findViewById<Button>(R.id.openSettings).setOnClickListener { showPage(Page.SETTINGS) }
        findViewById<android.view.View>(R.id.stlPromo).setOnClickListener {
            startActivity(StellarTimeLockPromo.intent(packageManager))
        }
        findViewById<android.view.View>(R.id.stlPromoSite).setOnClickListener {
            startActivity(StellarTimeLockPromo.siteIntent())
        }
        findViewById<Button>(R.id.settingsBack).setOnClickListener { showPage(Page.HOME) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentPage) {
                    Page.HOME -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                    Page.SETTINGS -> showPage(Page.HOME)
                    Page.TERMINAL -> showPage(Page.SETTINGS)
                }
            }
        })
        renderAppsBadge()
        findViewById<Button>(R.id.terminalRun).setOnClickListener { runTerminal() }
        findViewById<Button>(R.id.terminalSave).setOnClickListener { saveTerminalCommand() }
        savedCommands = SavedCommands.load(this)
        renderSavedCommands()
        watchKeyboard()
        requestUsefulPermissions()
        BackgroundListen.attach(this)
        promptBatteryOptOnce()
        showConsentFrom(intent)
        renderConnectionRows(ConnectionInput.offline())
        renderRelayMeter()
        renderQueue()
        renderHomeApps()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showConsentFrom(intent)
    }

    override fun onStart() {
        super.onStart()
        SessionHub.add(listener)
        apkReplace.attach()
        renderStatus()
        renderFiles()
        applyRemoteAccess()
        tvCast.onResume()
        promptMissingFolderIfNeeded()
        org.stellartimelock.send.CloudPull.onOpened(this, org.stellartimelock.send.CloudPull.fromIntent(intent))
    }

    override fun onStop() {
        apkReplace.detach()
        SessionHub.remove(listener)
        super.onStop()
    }

    private var qrRefresh = false
    private var lastQrText = ""

    private fun wireTile(tileId: Int, buttonId: Int) {
        findViewById<View>(tileId).setOnClickListener {
            findViewById<View>(buttonId).performClick()
        }
    }

    private fun bindQr(qr: String) {
        if (qr != lastQrText || findViewById<ImageView>(R.id.qr).drawable == null) {
            findViewById<ImageView>(R.id.qr).setImageBitmap(client.qrBitmap(qr, 512))
            lastQrText = qr
        }
        findViewById<TextView>(R.id.qrText).apply {
            text = qr
            visibility = View.GONE
        }
        findViewById<TextView>(R.id.shortCode).apply {
            text = runCatching { client.ownShortCode() }.getOrDefault("")
            visibility = View.GONE
        }
    }

    private fun showCode() {
        val qr = runCatching { client.showPairingCode() }.getOrElse {
            Toast.makeText(this, it.message ?: "Could not open pairing.", Toast.LENGTH_LONG).show()
            return
        }
        bindQr(qr)
        findViewById<TextView>(R.id.webHint).visibility = View.GONE
        renderStatus()
    }

    /** Live code while unpaired. While paired, paint the same advert without moving the relay room. */
    private fun ensureHomeQr(snap: org.stellartimelock.send.SessionSnapshot? = null) {
        if (qrRefresh) return
        val image = findViewById<ImageView>(R.id.qr)
        val state = snap ?: client.snapshot()
        if (image.drawable != null && lastQrText.isNotBlank() && (state.paired || state.pairingWindowOpen)) return
        qrRefresh = true
        try {
            val qr = if (!state.paired) {
                runCatching { client.showPairingCode() }.getOrNull()
            } else {
                runCatching { client.pairingQrText() }.getOrNull()
            } ?: return
            bindQr(qr)
        } finally {
            qrRefresh = false
        }
    }

    private fun enlargeQr() {
        val qr = runCatching { client.showPairingCode() }.getOrElse {
            Toast.makeText(this, it.message ?: "Could not open pairing.", Toast.LENGTH_LONG).show()
            return
        }
        bindQr(qr)
        val plate = ImageView(this).apply {
            setImageBitmap(client.qrBitmap(qr, 720))
            adjustViewBounds = true
            contentDescription = "Pairing code"
            setBackgroundColor(getColor(R.color.dash_cream))
        }
        val code = TextView(this).apply {
            text = runCatching { client.ownShortCode() }.getOrDefault("")
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(getColor(R.color.dash_cream))
            setPadding(0, dp(12), 0, 0)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(plate)
            if (code.text.isNotBlank()) addView(code)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Show code to PC")
            .setView(ScrollView(this).apply { addView(column) })
            .setPositiveButton("Close", null)
            .showInSafeArea()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun showWeb() {
        showPage(Page.HOME)
        showCode()
        findViewById<TextView>(R.id.webHint).apply {
            text = "On the iPhone, open https://flipsend.stellartimelock.com and tap Connect with iPhone. Scan this code. You can paste the code if the camera is unavailable."
            visibility = View.VISIBLE
        }
        findViewById<ScrollView>(R.id.scroller).post {
            val card = findViewById<View>(R.id.pairingCard)
            findViewById<ScrollView>(R.id.scroller).smoothScrollTo(0, card.top)
        }
    }

    private fun hideCode() {
        findViewById<ImageView>(R.id.qr).setImageDrawable(null)
        findViewById<TextView>(R.id.qrText).visibility = View.GONE
        findViewById<TextView>(R.id.shortCode).visibility = View.GONE
        findViewById<TextView>(R.id.webHint).visibility = View.GONE
    }

    private fun scanCode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(Manifest.permission.CAMERA))
            Toast.makeText(this, "Camera is used only to scan a pairing code. You can paste the code instead.", Toast.LENGTH_LONG).show()
            return
        }
        scan.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the pairing code shown on your computer")
            setBeepEnabled(false)
        })
    }

    private fun pasteCode() {
        val input = EditText(this).apply {
            hint = "Code or stellarsend://pair?..."
            contentDescription = "Pairing code"
            setTextColor(getColor(org.stellartimelock.send.R.color.space_text))
        }
        val scroller = ScrollView(this).apply { addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Pair with computer")
            .setView(scroller)
            .setPositiveButton("Pair") { _, _ ->
                val code = input.text.toString().trim()
                Thread {
                    val error = runCatching {
                        if (ShortCode.looksLike(code)) client.pairFromShortCode(code) else client.pairFromCode(code)
                    }.exceptionOrNull()
                    runOnUiThread {
                        toastUnlessQuota(error)
                        renderStatus()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .showInSafeArea()
    }

    private fun enterInvite() {
        val input = EditText(this).apply {
            hint = "12-character invite"
            contentDescription = "Invite code"
            setTextColor(getColor(org.stellartimelock.send.R.color.space_text))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Enter invite")
            .setView(input)
            .setPositiveButton("Install") { _, _ ->
                val message = org.stellartimelock.send.InviteShare.redeem(this, input.text.toString())
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .showInSafeArea()
    }

    private fun openOnPc() {
        val url = findViewById<EditText>(R.id.browserUrl).text.toString()
        Thread {
            val result = runCatching { client.openBrowserOnPc(url) }.getOrElse {
                org.stellartimelock.send.core.ServiceResult.fail("", "io", it.message ?: "Could not reach the paired PC.")
            }
            runOnUiThread {
                findViewById<TextView>(R.id.remoteStatus).text = result.message
                if (showRepairPrompt(result.code, result.message) { startRepairFromPrompt() }) return@runOnUiThread
                if (result.code != RelayPlans.QUOTA) {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun sendText() {
        val text = findViewById<EditText>(R.id.message).text.toString()
        if (text.isEmpty()) {
            Toast.makeText(this, "Enter text first.", Toast.LENGTH_SHORT).show()
            return
        }
        SendChooser.sendToPc(this, client, SendRequest(PayloadKind.TEXT, "message.txt", text = text))
    }

    private fun sendClipboard() {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "The clipboard is empty.", Toast.LENGTH_SHORT).show()
            return
        }
        SendChooser.sendToPc(this, client, SendRequest(PayloadKind.TEXT, "clipboard.txt", text = text))
    }

    private fun watchKeyboard() {
        val scroller = findViewById<ScrollView>(R.id.scroller)
        val focus = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) view.post { scroller.smoothScrollTo(0, view.bottom) }
        }
        findViewById<EditText>(R.id.message).onFocusChangeListener = focus
        findViewById<EditText>(R.id.browserUrl).onFocusChangeListener = focus
        findViewById<EditText>(R.id.manualHost).onFocusChangeListener = focus
        findViewById<EditText>(R.id.terminalCommand).onFocusChangeListener = focus
    }

    private fun showPage(page: Page) {
        currentPage = page
        findViewById<View>(R.id.transferPage).visibility = if (page == Page.HOME) View.VISIBLE else View.GONE
        findViewById<View>(R.id.settingsPage).visibility = if (page == Page.SETTINGS) View.VISIBLE else View.GONE
        findViewById<View>(R.id.terminalPage).visibility = if (page == Page.TERMINAL) View.VISIBLE else View.GONE
        findViewById<ScrollView>(R.id.scroller).post {
            findViewById<ScrollView>(R.id.scroller).scrollTo(0, 0)
        }
    }

    private fun startRepairFromPrompt() {
        client.rotate()
        showPage(Page.HOME)
        showCode()
        renderStatus()
        Toast.makeText(
            this,
            "Scan this code on the PC. The current pairing stays until the computer confirms.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun saveTerminalCommand() {
        val command = findViewById<EditText>(R.id.terminalCommand).text.toString().trim()
        val invalid = TerminalPolicy.rejectReason(command)
        if (invalid != null) {
            Toast.makeText(this, invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (savedCommands.any { it.equals(command, ignoreCase = true) }) {
            Toast.makeText(this, "That command is already saved.", Toast.LENGTH_SHORT).show()
            return
        }
        savedCommands = (listOf(command) + savedCommands).take(30)
        SavedCommands.save(this, savedCommands)
        renderSavedCommands()
    }

    private fun renderSavedCommands() {
        val container = findViewById<LinearLayout>(R.id.terminalSaved)
        container.removeAllViews()
        if (savedCommands.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No saved commands yet. Type one above and tap Save."
                setTextColor(getColor(org.stellartimelock.send.R.color.space_muted))
                textSize = 16f
            })
            return
        }
        savedCommands.forEach { command ->
            val row = LinearLayout(this)
            val use = Button(this).apply {
                text = command
                textSize = 16f
                contentDescription = "Use saved command $command"
                setOnClickListener {
                    findViewById<EditText>(R.id.terminalCommand).setText(command)
                }
            }
            val remove = Button(this).apply {
                text = "Remove"
                textSize = 16f
                contentDescription = "Remove saved command"
                setOnClickListener {
                    savedCommands = savedCommands.filterNot { it == command }
                    SavedCommands.save(this@MainActivity, savedCommands)
                    renderSavedCommands()
                }
            }
            FitButtons.stack(row, 8, use, remove)
            container.addView(row)
        }
    }

    private fun runTerminal() {
        if (terminalRunning) return
        val command = findViewById<EditText>(R.id.terminalCommand).text.toString().trim()
        val invalid = TerminalPolicy.rejectReason(command)
        if (invalid != null) {
            Toast.makeText(this, invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (TerminalPolicy.isDestructive(command)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Run this on the PC?")
                .setMessage("$command\n\nThis looks destructive (del, rm, rmdir, format, or shutdown).")
                .setPositiveButton("Run") { _, _ -> startTerminal(command, confirmed = true) }
                .setNegativeButton("Cancel", null)
                .showInSafeArea()
            return
        }
        startTerminal(command, confirmed = false)
    }

    private fun startTerminal(command: String, confirmed: Boolean) {
        terminalRunning = true
        val output = findViewById<TextView>(R.id.terminalOutput)
        val path = findViewById<TextView>(R.id.terminalPath)
        output.text = ""
        path.text = "Running…"
        Thread {
            val builder = StringBuilder()
            val result = runCatching {
                client.runTerminal(command, confirmed) { _, text ->
                    if (builder.length < TerminalPolicy.MAX_OUTPUT_CHARS) {
                        val room = TerminalPolicy.MAX_OUTPUT_CHARS - builder.length
                        builder.append(text.take(room))
                        val shown = builder.toString()
                        runOnUiThread { output.text = shown }
                    }
                }
            }.getOrElse {
                org.stellartimelock.send.core.ServiceResult.fail("", "io", it.message ?: "Could not reach the paired PC.")
            }
            runOnUiThread {
                terminalRunning = false
                if (result.code == TerminalPolicy.NEEDS_CONFIRM) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Run this on the PC?")
                        .setMessage("$command\n\nThis looks destructive (del, rm, rmdir, format, or shutdown).")
                        .setPositiveButton("Run") { _, _ -> startTerminal(command, confirmed = true) }
                        .setNegativeButton("Cancel", null)
                        .showInSafeArea()
                    path.text = result.message
                    return@runOnUiThread
                }
                if (showRepairPrompt(result.code, result.message) { startRepairFromPrompt() }) {
                    path.text = RepairPrompt.MESSAGE
                    if (builder.isEmpty()) output.text = RepairPrompt.MESSAGE
                    return@runOnUiThread
                }
                val snap = client.snapshot()
                path.text = listOf(snap.pathLabel, result.message).filter { it.isNotBlank() }.joinToString(" · ")
                if (builder.isEmpty()) output.text = result.message
            }
        }.start()
    }

    private fun renderFiles() {
        val container = findViewById<LinearLayout>(R.id.files)
        container.removeAllViews()
        if (files.isEmpty()) return
        files.forEach { (name, chosen) ->
            val row = LinearLayout(this)
            val label = TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(getColor(org.stellartimelock.send.R.color.space_text))
            }
            val send = Button(this).apply {
                text = "Send"
                textSize = 16f
                contentDescription = "Send $name"
                setOnClickListener {
                    SendChooser.show(
                        this@MainActivity,
                        client,
                        SendRequest(PayloadKind.FILE, name, uri = chosen.uri, mimeType = chosen.mime)
                    )
                }
            }
            val extra = if (ApkReplacePreview.isApk(name)) {
                Button(this).apply {
                    text = "Update this app"
                    textSize = 16f
                    contentDescription = "Update the installed app with $name"
                    setOnClickListener { apkReplace.offerUri(chosen.uri, name) }
                }
            } else {
                null
            }
            if (extra != null) {
                FitButtons.stack(row, 8, label, send, extra)
            } else {
                FitButtons.stack(row, 8, label, send)
            }
            container.addView(row)
        }
    }

    private var appsExpanded = false
    private var appsShown = HomeApps.PREVIEW
    private var homeAppsMoreCount = 0
    private var lastHomeAppsKey = ""
    private var homeAppFiles: Map<String, ReceiveFile> = emptyMap()

    private fun renderStatus() {
        val snap = client.snapshot()
        val input = client.connectionInput()
        val face = ConnectionState.face(input)
        val header = when (face.headline) {
            org.stellartimelock.send.core.HomeStatus.CONNECTED ->
                org.stellartimelock.send.core.HomeStatus.LINKED
            org.stellartimelock.send.core.HomeStatus.KEYS_DONT_MATCH ->
                org.stellartimelock.send.core.HomeStatus.KEYS_DONT_MATCH_SHORT
            else -> face.headline
        }
        findViewById<TextView>(R.id.status).text = header
        findViewById<TextView>(R.id.detail).apply {
            text = ""
            visibility = View.GONE
            setOnClickListener(null)
        }
        findViewById<TextView>(R.id.statusHint).apply {
            text = if (face.headline == org.stellartimelock.send.core.HomeStatus.KEYS_DONT_MATCH) "" else face.hint
            visibility = View.GONE
        }
        snap.lastError?.let { android.util.Log.i("StellarSend", it) }
        val linked = face.connected
        val mismatch = face.headline == org.stellartimelock.send.core.HomeStatus.KEYS_DONT_MATCH
        findViewById<TextView>(R.id.status).setTextColor(
            getColor(
                when {
                    mismatch -> R.color.dash_gold
                    linked -> R.color.dash_teal
                    else -> R.color.dash_muted
                }
            )
        )
        findViewById<View>(R.id.statusBar).visibility = View.GONE
        findViewById<View>(R.id.statusDot).background?.mutate()?.setTint(
            getColor(
                when {
                    mismatch -> R.color.dash_gold
                    linked -> R.color.dash_teal
                    else -> R.color.dash_muted
                }
            )
        )
        findViewById<TextView>(R.id.pathLabel).visibility = View.GONE
        renderConnectionRows(input)
        renderBluetoothChecklist()
        renderRelayMeter()
        if (mismatch) {
            findViewById<TextView>(R.id.relayUsage).text =
                org.stellartimelock.send.core.HomeStatus.KEYS_DONT_MATCH
        }
        findViewById<TextView>(R.id.inboxLabel).text = "Saving into ${PhoneSaveFolder.LABEL}."
        findViewById<TextView>(R.id.homeHint).visibility = View.GONE
        renderQueue()
        renderHomeApps()
        ensureHomeQr(snap)
        renderAppsBadge()
        applyRemoteAccess()
        renderDevices()
        if (findViewById<EditText>(R.id.manualHost).text.isNullOrBlank()) {
            findViewById<EditText>(R.id.manualHost).setText(snap.manualHost.orEmpty())
        }
        offerTrustIfNeeded(input.lastError)
    }

    private fun offerTrustIfNeeded(lastError: String?) {
        val offer = client.pendingTrust()
        if (offer == null || offer.fingerprint.isBlank()) {
            trustPromptShown = false
            return
        }
        if (trustPromptShown) return
        if (showTrustPrompt(client, org.stellartimelock.send.core.ErrorCodes.KEY_CHANGED, lastError) { confirmed ->
                if (confirmed) trustPromptShown = false
                renderStatus()
            }) {
            trustPromptShown = true
        }
    }

    private fun renderRelayMeter() {
        val snap = org.stellartimelock.send.PlanStatus.snapshot(this)
        val fallback = org.stellartimelock.send.PlanStatus.table(this)?.fairUseBytes ?: org.stellartimelock.send.core.RelayMeter.FREE_BYTES
        val sending = TransferQueue.rows(ReceiveLive.snap(), liveSend)
            .firstOrNull { it.section == TransferQueue.IN_PROGRESS }
            ?.title
            .orEmpty()
        findViewById<TextView>(R.id.relayUsage).text = org.stellartimelock.send.core.RelayMeter.homeMeta(
            used = snap.used,
            limit = if (snap.unlimited) 0 else if (snap.limit > 0) snap.limit else fallback,
            unlimited = snap.unlimited,
            sending = sending,
            vpnLine = org.stellartimelock.send.VpnMeter.line(this)
        )
    }

    private fun renderConnectionRows(input: org.stellartimelock.send.core.ConnectionInput) {
        val host = findViewById<LinearLayout>(R.id.connectionRows)
        host.removeAllViews()
        ConnectionMethods.rows(input).forEach { method -> host.addView(connectionRow(method)) }
    }

    private fun connectionRow(method: ConnectionMethod): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            contentDescription = if (method.canSwitch) {
                "${method.name}, ${method.label}, tap to switch"
            } else {
                "${method.name}, ${method.label}"
            }
            if (method.canSwitch) {
                setOnClickListener { client.useRoute(method.route.orEmpty()) }
            }
        }
        row.addView(ImageView(this).apply {
            setImageResource(connectionIcon(method.icon))
            contentDescription = null
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })
        row.addView(TextView(this).apply {
            text = method.name
            textSize = 16f
            setTextColor(getColor(R.color.dash_cream))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(8)
            }
        })
        row.addView(TextView(this).apply {
            text = method.label
            textSize = 16f
            setTextColor(getColor(connectionBadgeColor(method.mark)))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = ContextCompat.getDrawable(this@MainActivity, connectionBadgeBackground(method.mark))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        })
        return row
    }

    private fun connectionIcon(icon: String): Int = when (icon) {
        "direct" -> R.drawable.ic_link_direct
        "relay" -> R.drawable.ic_link_relay
        "usb" -> R.drawable.ic_link_usb
        "bluetooth" -> R.drawable.ic_link_bluetooth
        else -> R.drawable.ic_link_wifi
    }

    private fun connectionBadgeColor(mark: ConnectionMark): Int = when (mark) {
        ConnectionMark.CONNECTED -> R.color.glass_ok
        ConnectionMark.AVAILABLE -> R.color.stl_accent
        else -> R.color.stl_muted
    }

    private fun connectionBadgeBackground(mark: ConnectionMark): Int = when (mark) {
        ConnectionMark.CONNECTED -> R.drawable.glass_badge_ok
        ConnectionMark.AVAILABLE -> R.drawable.glass_badge_available
        else -> R.drawable.glass_badge_off
    }

    private fun renderQueue() {
        val rows = TransferQueue.rows(ReceiveLive.snap(), liveSend)
            .filter { row -> !(row.apk && row.section == TransferQueue.FINISHED) }
        val host = findViewById<LinearLayout>(R.id.queueRows)
        host.removeAllViews()
        findViewById<View>(R.id.transfersCard).visibility =
            if (rows.isEmpty()) View.GONE else View.VISIBLE
        if (rows.isEmpty()) {
            return
        }
        var section = ""
        rows.forEach { row ->
            if (row.section != section) {
                section = row.section
                host.addView(queueText(section, muted = false))
            }
            host.addView(queueText("${row.title} · ${row.detail}", muted = true))
            if (row.showBar) {
                host.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = row.percent
                    progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.dash_gold))
                })
            }
        }
    }

    private fun queueText(value: String, muted: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(getColor(if (muted) R.color.dash_muted else R.color.dash_cream))
        setPadding(0, 8, 0, 0)
    }

    private fun renderHomeApps() {
        val log = ReceivedLog(this)
        val entries = log.list()
        val times = entries.associate { it.id to it.receivedAtMillis }
        val files = ReceivedApps.merge(entries, ReceiveLive.snap().files) { entry ->
            log.displayPath(entry)
        }
        homeAppFiles = files.associateBy { it.id }
        val page = HomeApps.list(files, times, appsShown > HomeApps.PREVIEW, limit = appsShown)
        appsExpanded = appsShown > HomeApps.PREVIEW
        homeAppsMoreCount = page.more
        val key = "$appsShown|" + page.shown.joinToString { "${it.id}:${it.action}:${it.received}" }
        if (key == lastHomeAppsKey) {
            findViewById<Button>(R.id.homeAppsMore).apply {
                val extra = page.more > 0 || appsShown > HomeApps.PREVIEW
                visibility = if (extra) View.VISIBLE else View.GONE
                text = if (page.more == 0 && appsShown > HomeApps.PREVIEW) HomeApps.SHOW_LESS else HomeApps.SHOW_MORE
            }
            return
        }
        lastHomeAppsKey = key
        findViewById<View>(R.id.homeAppsCard).visibility = View.GONE
        val host = findViewById<LinearLayout>(R.id.homeAppRows)
        host.removeAllViews()
        if (page.shown.isEmpty()) {
            host.addView(queueText(HomeApps.EMPTY, muted = true))
        } else {
            page.shown.forEach { row -> host.addView(homeAppRow(row)) }
        }
        findViewById<Button>(R.id.homeAppsMore).apply {
            val extra = page.more > 0 || appsShown > HomeApps.PREVIEW
            visibility = if (extra) View.VISIBLE else View.GONE
            text = if (page.more == 0 && appsShown > HomeApps.PREVIEW) HomeApps.SHOW_LESS else HomeApps.SHOW_MORE
        }
    }

    private fun homeAppRow(row: org.stellartimelock.send.core.HomeAppRow): LinearLayout {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        copy.addView(TextView(this).apply {
            this.text = row.name
            textSize = 16f
            setTextColor(getColor(R.color.stl_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        copy.addView(TextView(this).apply {
            this.text = "${row.version} · ${row.received}"
            textSize = 16f
            setTextColor(getColor(R.color.stl_muted))
        })
        line.addView(copy)
        if (HomeApps.isButton(row.action)) {
            line.addView(androidx.appcompat.widget.AppCompatButton(this).apply {
                text = row.action
                textSize = 16f
                contentDescription = "${row.action} ${row.name}"
                setTextColor(getColor(R.color.stl_accent))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.glass_chip)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                isAllCaps = false
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { installHomeApp(row.id) }
            })
        } else if (row.action.isNotBlank()) {
            line.addView(TextView(this).apply {
                text = row.action
                textSize = 16f
                setTextColor(getColor(R.color.stl_muted))
            })
        }
        return line
    }

    private fun installHomeApp(id: String) {
        val file = homeAppFiles[id] ?: return
        ReceivedApkInstall.start(this, file) { renderHomeApps() }
    }

    private fun renderAppsBadge() {
        findViewById<Button>(R.id.tabApps).apply {
            text = "Apps"
            contentDescription = "Apps"
        }
    }

    private fun applyStoreGates() {
        applyRemoteDesktop()
    }

    private fun applyRemoteDesktop() {
        val on = BuildConfig.REMOTE_DESKTOP
        val label = if (on) getString(R.string.remote_desktop_label) else ""
        val line = if (on) getString(R.string.remote_desktop_line) else ""
        findViewById<View>(R.id.remoteDesktopCard).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.remoteDesktopHome).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.controlPc).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.remoteDesktopBeta).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.remoteDesktopSettingsBeta).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.settingsLead).text = getString(R.string.settings_lead)
        findViewById<TextView>(R.id.remoteDesktopTitle).text = label
        findViewById<TextView>(R.id.remoteDesktopLine).text = line
        findViewById<Button>(R.id.controlPc).apply {
            text = label
            contentDescription = label
            if (!on) isEnabled = false
        }
        findViewById<Button>(R.id.openRemoteDesktop).apply {
            text = label
            contentDescription = label
            if (!on) isEnabled = false
        }
    }

    private fun applyRemoteAccess() {
        applyRemoteDesktop()
        val active = runCatching { client.remoteActive() }.getOrDefault(false)
        findViewById<View>(R.id.remoteBanner).visibility = if (active) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.remoteBannerText).text = org.stellartimelock.send.core.RemoteControl.BANNER
        val serviceOn = org.stellartimelock.send.RemoteControlPrefs.accessibilityOn(this)
        val phoneOn = org.stellartimelock.send.RemoteControlPrefs.phoneControl(this)
        val row = org.stellartimelock.send.core.PhoneControlChecklist.rows(
            packageName,
            org.stellartimelock.send.RemoteControlService::class.java.name,
            serviceOn,
            Build.VERSION.SDK_INT,
            BuildConfig.REMOTE_DESKTOP
        ).first()
        findViewById<TextView>(R.id.phoneControlStatus).text = if (phoneOn && serviceOn) "On" else if (serviceOn) "On" else "Off"
        findViewById<TextView>(R.id.accessibilityHint).text = row.detail
        findViewById<Button>(R.id.openAccessibility).text = row.actionLabel
        val restricted = Build.VERSION.SDK_INT >= 33
        findViewById<View>(R.id.restrictedLine).visibility = if (restricted) View.VISIBLE else View.GONE
        findViewById<View>(R.id.openRestricted).visibility = if (restricted) View.VISIBLE else View.GONE
    }

    private fun renderBluetoothChecklist() {
        val snap = org.stellartimelock.send.BluetoothLink.snapshot(this)
        val asked = getSharedPreferences("flipsend_bluetooth", MODE_PRIVATE).getBoolean("asked", false)
        val showRationale = Build.VERSION.SDK_INT >= 31 &&
            shouldShowRequestPermissionRationale(android.Manifest.permission.BLUETOOTH_CONNECT)
        val step = org.stellartimelock.send.core.BluetoothChecklist.step(
            snap.permitted,
            snap.radioOn,
            asked,
            showRationale
        )
        findViewById<TextView>(R.id.bluetoothPermissionStatus).text = if (snap.permitted) "Nearby devices: On" else "Nearby devices: Off"
        findViewById<TextView>(R.id.bluetoothRadioStatus).text = if (snap.radioOn) "On" else "Off"
        findViewById<TextView>(R.id.bluetoothHint).text = step.detail
        findViewById<Button>(R.id.setupBluetooth).text = "Bluetooth"
        findViewById<Button>(R.id.setupBluetooth).contentDescription = step.label
    }

    private fun openBluetoothSetup() {
        val snap = org.stellartimelock.send.BluetoothLink.snapshot(this)
        val prefs = getSharedPreferences("flipsend_bluetooth", MODE_PRIVATE)
        val showRationale = Build.VERSION.SDK_INT >= 31 &&
            shouldShowRequestPermissionRationale(android.Manifest.permission.BLUETOOTH_CONNECT)
        val step = org.stellartimelock.send.core.BluetoothChecklist.step(
            snap.permitted,
            snap.radioOn,
            prefs.getBoolean("asked", false),
            showRationale
        )
        when (step.action) {
            org.stellartimelock.send.core.BluetoothSetup.ASK -> {
                prefs.edit().putBoolean("asked", true).apply()
                val missing = org.stellartimelock.send.core.StartupPermissions.runtime(Build.VERSION.SDK_INT)
                    .filter { it == org.stellartimelock.send.core.StartupPermissions.BLUETOOTH_CONNECT || it == org.stellartimelock.send.core.StartupPermissions.BLUETOOTH_SCAN }
                if (missing.isEmpty()) {
                    renderBluetoothChecklist()
                } else {
                    permissions.launch(missing.toTypedArray())
                }
            }
            org.stellartimelock.send.core.BluetoothSetup.APP_SETTINGS -> {
                val intent = Intent(
                    step.action,
                    android.net.Uri.parse("package:$packageName")
                )
                runCatching { startActivity(intent) }
            }
            else -> runCatching { startActivity(Intent(step.action)) }
        }
    }

    private fun openPhoneControlSettings() {
        val serviceOn = org.stellartimelock.send.RemoteControlPrefs.accessibilityOn(this)
        if (!org.stellartimelock.send.core.PhoneControlChecklist.shouldOpen(serviceOn, userTapped = true)) {
            Toast.makeText(this, "Phone control is on.", Toast.LENGTH_SHORT).show()
            return
        }
        val target = org.stellartimelock.send.core.PhoneControlChecklist.accessibilityTarget(
            packageName,
            org.stellartimelock.send.RemoteControlService::class.java.name
        )
        val intent = Intent(target.action).putExtra(
            org.stellartimelock.send.core.PhoneControlChecklist.COMPONENT_EXTRA,
            target.component
        )
        val opened = runCatching { startActivity(intent) }.isSuccess
        if (!opened) {
            findViewById<TextView>(R.id.accessibilityHint).text =
                org.stellartimelock.send.core.PhoneControlChecklist.MANUAL
        }
    }

    private fun openRestrictedSettings() {
        val target = org.stellartimelock.send.core.PhoneControlChecklist.appInfoTarget(packageName)
        val intent = Intent(target.action, android.net.Uri.parse(target.packageUri))
        runCatching { startActivity(intent) }
    }

    private fun onEvent(event: SessionEvent) {
        when (event) {
            SessionEvent.Changed -> renderStatus()
            is SessionEvent.Notice -> {
                findViewById<TextView>(R.id.progress).apply {
                    text = event.text
                    visibility = View.VISIBLE
                }
                renderStatus()
            }
            is SessionEvent.Progress -> {
                val update = event.update
                val active = update.state == TransferState.IN_PROGRESS || update.state == TransferState.AWAITING_CONSENT
                val percent = if (update.total > 0) ((update.bytes * 100) / update.total).toInt().coerceIn(0, 100) else 0
                liveSend = if (active) LiveSend(update.name, percent, true) else null
                renderQueue()
            }
            is SessionEvent.ApkReady -> renderQueue()
            is SessionEvent.Receive -> {
                if (!event.snap.receiving) liveSend = null
                renderQueue()
                renderAppsBadge()
            }
            SessionEvent.ReceivedChanged -> {
                renderStatus()
                promptMissingFolderIfNeeded()
            }
            is SessionEvent.Consent -> showConsentDialog(event.id, event.title, event.body)
            is SessionEvent.Upgrade -> RelayUpgrade.show(this, event.message)
            SessionEvent.RemoteChanged -> applyRemoteAccess()
        }
    }

    private fun showConsentFrom(intent: Intent?) {
        val id = intent?.getStringExtra(ConsentPrompt.EXTRA_ID) ?: return
        val title = intent.getStringExtra(ConsentPrompt.EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(ConsentPrompt.EXTRA_BODY) ?: return
        showConsentDialog(id, title, body)
    }

    private fun showConsentDialog(id: String, title: String, body: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("Accept") { _, _ -> client.answerConsent(id, true) }
            .setNegativeButton("Decline") { _, _ -> client.answerConsent(id, false) }
            .setOnCancelListener { client.answerConsent(id, false) }
            .showInSafeArea()
    }

    private fun toastUnlessQuota(error: Throwable?) {
        if (error == null || error is RelayQuotaException || error.cause is RelayQuotaException) return
        Toast.makeText(this, error.message ?: "Pairing failed.", Toast.LENGTH_LONG).show()
    }

    private fun renderDevices() {
        val container = findViewById<LinearLayout>(R.id.devices)
        container.removeAllViews()
        val devices = client.pairedDevices()
        if (devices.isEmpty()) return
        devices.forEach { device ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(this).apply {
                text = device.name
                textSize = 18f
                setTextColor(getColor(org.stellartimelock.send.R.color.space_text))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                FitButtons.prepare(this)
            })
            row.addView(TextView(this).apply {
                text = device.pairedOn
                textSize = 16f
                setTextColor(getColor(org.stellartimelock.send.R.color.space_muted))
            })
            row.addView(Button(this).apply {
                text = "Forget"
                textSize = 16f
                contentDescription = "Forget ${device.name}"
                setOnClickListener { confirmForget(device.name) }
            })
            container.addView(row)
        }
    }

    /** Forget is permanent and the PC is not told, so ask first. One stray tap used to drop the pairing. */
    private fun confirmForget(name: String = "this PC") {
        MaterialAlertDialogBuilder(this)
            .setTitle("Forget $name?")
            .setMessage("Sends from it stop until you scan its code again.")
            .setPositiveButton("Forget") { _, _ ->
                client.unpair()
                hideCode()
                renderStatus()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun applyInbox(uri: String) {
        client.setInboxTree(uri, confirmed = true)
        renderStatus()
    }

    private fun promptMissingFolderIfNeeded() {
        if (folderPromptOpen) return
        if (!PhoneInbox.needsPrompt(this)) return
        folderPromptOpen = true
        MaterialAlertDialogBuilder(this)
            .setTitle("FlipSend folder")
            .setMessage("FlipSend can't write StellarTimeLock backups/FlipSend without a one-time folder pick. Point it at that folder. It will remember and save copies there.")
            .setPositiveButton("Choose folder") { _, _ ->
                PhoneInbox.markPrompted(this)
                pickInbox.launch(null)
            }
            .setNegativeButton("Not now") { _, _ ->
                folderPromptOpen = false
                PhoneInbox.markPrompted(this)
            }
            .setOnCancelListener {
                folderPromptOpen = false
                PhoneInbox.markPrompted(this)
            }
            .showInSafeArea()
    }

    private fun saveHost() {
        applyHost(findViewById<EditText>(R.id.manualHost).text.toString(), confirmed = false)
    }

    private fun applyHost(host: String, confirmed: Boolean) {
        val decision = client.setManualHost(host, confirmed)
        if (decision.kind == OverwriteKind.NEEDS_CONFIRM) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Replace save?")
                .setMessage("${decision.reason}\n\nCurrent items: ${decision.existingItems}\nNew items: ${decision.incomingItems}")
                .setPositiveButton("Replace") { _, _ -> applyHost(host, confirmed = true) }
                .setNeutralButton("Restore") { _, _ -> restoreSave() }
                .setNegativeButton("Cancel", null)
                .showInSafeArea()
        } else {
            Toast.makeText(this, "Address saved.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreSave() {
        val backups = client.saveBackups()
        if (backups.isEmpty()) {
            Toast.makeText(this, "No save backups yet.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = backups.map { backup ->
            "Backup ${backup.index} · ${backup.summary.describe()}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore save")
            .setItems(labels) { _, which ->
                val index = backups[which].index
                val decision = client.restoreSave(index)
                if (decision.kind == OverwriteKind.NEEDS_CONFIRM) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Replace save?")
                        .setMessage("${decision.reason}\n\nCurrent items: ${decision.existingItems}\nBackup items: ${decision.incomingItems}")
                        .setPositiveButton("Restore") { _, _ ->
                            client.restoreSave(index, confirmed = true)
                            findViewById<EditText>(R.id.manualHost).setText(client.snapshot().manualHost.orEmpty())
                            Toast.makeText(this, "Save restored.", Toast.LENGTH_LONG).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .showInSafeArea()
                } else {
                    findViewById<EditText>(R.id.manualHost).setText(client.snapshot().manualHost.orEmpty())
                    Toast.makeText(this, decision.reason, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .showInSafeArea()
    }

    private fun requestUsefulPermissions() {
        val missing = org.stellartimelock.send.core.StartupPermissions.missing(Build.VERSION.SDK_INT) { permission ->
            ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissions.launch(missing.toTypedArray())
        } else {
            askInstallPermission()
        }
    }

    private fun askInstallPermission() {
        val canInstall = if (Build.VERSION.SDK_INT >= 26) packageManager.canRequestPackageInstalls() else true
        if (!org.stellartimelock.send.core.StartupPermissions.needsUnknownApps(Build.VERSION.SDK_INT, canInstall)) return
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
    }

    private var sharing = false
    private val screenCapture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            Toast.makeText(this, "Allow screen capture to show this phone on the PC.", Toast.LENGTH_LONG).show()
            renderShareButton()
            return@registerForActivityResult
        }
        org.stellartimelock.send.ScreenShareService.start(this, result.resultCode, data)
        sharing = true
        renderShareButton()
        Thread {
            val outcome = runCatching { client.shareScreen { !sharing } }.getOrElse {
                org.stellartimelock.send.core.ServiceResult.fail("", "io", it.message ?: "Could not share this screen.")
            }
            runOnUiThread {
                sharing = false
                org.stellartimelock.send.ScreenShareService.stop(this)
                renderShareButton()
                if (!outcome.ok) {
                    if (showRepairPrompt(outcome.code, outcome.message) { startRepairFromPrompt() }) return@runOnUiThread
                    Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun toggleScreenShare() {
        if (sharing) {
            sharing = false
            client.stopShare()
            org.stellartimelock.send.ScreenShareService.stop(this)
            renderShareButton()
            return
        }
        if (!client.snapshot().paired) {
            Toast.makeText(this, org.stellartimelock.send.core.ScreenShare.NEED_PAIR, Toast.LENGTH_LONG).show()
            return
        }
        screenCapture.launch(org.stellartimelock.send.ScreenShareService.consentIntent(this))
    }

    private fun renderShareButton() {
        findViewById<Button>(R.id.shareScreen).apply {
            text = if (sharing) "Stop sharing" else "Check phone screen"
            contentDescription = if (sharing) "Stop sharing" else "Check phone screen"
        }
    }

    private fun renderCast(status: String) {
        findViewById<Button>(R.id.castToTv).text =
            if (tvCast.active) org.stellartimelock.send.core.TvCast.STOP else org.stellartimelock.send.core.TvCast.BUTTON
        findViewById<TextView>(R.id.castStatus).apply {
            text = status
            visibility = if (status.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun promptBatteryOptOnce() {
        if (Build.VERSION.SDK_INT < 23) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("stellarsend", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_opt_prompted", false)) return
        prefs.edit().putBoolean("battery_opt_prompted", true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(org.stellartimelock.send.R.string.battery_opt_title))
            .setMessage(getString(org.stellartimelock.send.R.string.battery_opt_body))
            .setPositiveButton("OK") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                }
            }
            .setNegativeButton("Not now", null)
            .showInSafeArea()
    }

    private fun requestBatteryOptExemption() {
        if (Build.VERSION.SDK_INT < 23) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Battery restrictions are already off for FlipSend.", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(org.stellartimelock.send.R.string.battery_opt_title))
            .setMessage(getString(org.stellartimelock.send.R.string.battery_opt_body))
            .setPositiveButton("OK") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                }
            }
            .setNegativeButton("Not now", null)
            .showInSafeArea()
    }

    private fun setupOneplusGuidance() {
        val card = findViewById<View>(R.id.oneplusGuidanceCard) ?: return
        val isOnePlus = Build.MANUFACTURER.equals("OnePlus", ignoreCase = true) ||
            Build.MANUFACTURER.equals("ONEPLUS", ignoreCase = true) ||
            Build.BRAND.equals("OnePlus", ignoreCase = true)
        card.visibility = if (isOnePlus) View.VISIBLE else View.GONE
        card.findViewById<Button>(R.id.openOneplusSettings)?.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }
    }
}
