package org.stellartimelock.stellarsend

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.stellartimelock.send.SafeDrawing
import rikka.shizuku.Shizuku

/** One field and one button. Removes a package for every user. */
class RemoveOldAppActivity : AppCompatActivity() {
    private var launchedPackage = ""
    private var shizukuListener: Shizuku.OnRequestPermissionResultListener? = null

    private val uninstall = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onUninstallReturned(result.resultCode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SafeDrawing.prepare(this)
        setContentView(R.layout.activity_remove_old_app)
        SafeDrawing.apply(this)
        val field = findViewById<EditText>(R.id.removeOldPackage)
        if (field.text.isNullOrBlank()) field.setText(RemoveOldApp.DEFAULT_PACKAGE)
        show(visibilityStatus())
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                show(visibilityStatus())
            }
        })
        findViewById<Button>(R.id.removeOldCopy).setOnClickListener { onRemove() }
    }

    override fun onDestroy() {
        shizukuListener?.let { listener ->
            runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
        }
        shizukuListener = null
        super.onDestroy()
    }

    internal fun onUninstallReturned(resultCode: Int) {
        if (resultCode == Activity.RESULT_OK) {
            show(RemoveOldApp.CLEARED)
            return
        }
        fallbackShizuku(launchedPackage.ifBlank { packageText() })
    }

    private fun onRemove() {
        val pkg = packageText()
        if (!RemoveOldApp.isPackageName(pkg)) {
            show(RemoveOldApp.NOT_VISIBLE)
            return
        }
        launchedPackage = pkg
        val started = runCatching { uninstall.launch(RemoveOldApp.uninstallIntent(pkg)) }.isSuccess
        if (!started) {
            val deleted = runCatching {
                uninstall.launch(RemoveOldApp.uninstallIntent(pkg, android.content.Intent.ACTION_DELETE))
            }.isSuccess
            if (!deleted) fallbackShizuku(pkg)
        }
    }

    private fun fallbackShizuku(pkg: String) {
        if (!RemoveOldApp.isPackageName(pkg) || !ShizukuUninstall.available()) {
            show(visibilityStatus(pkg))
            return
        }
        if (!ShizukuUninstall.allowed()) {
            val listener = Shizuku.OnRequestPermissionResultListener { code, grant ->
                if (code != SHIZUKU_REQUEST) return@OnRequestPermissionResultListener
                shizukuListener?.let { runCatching { Shizuku.removeRequestPermissionResultListener(it) } }
                shizukuListener = null
                if (grant == PackageManager.PERMISSION_GRANTED) {
                    runPm(pkg)
                } else {
                    runOnUiThread { show(visibilityStatus(pkg)) }
                }
            }
            shizukuListener = listener
            val asked = runCatching {
                Shizuku.addRequestPermissionResultListener(listener)
                Shizuku.requestPermission(SHIZUKU_REQUEST)
            }.isSuccess
            if (!asked) show(visibilityStatus(pkg))
            return
        }
        runPm(pkg)
    }

    private fun runPm(pkg: String) {
        Thread {
            val code = runCatching { ShizukuUninstall.uninstallAllUsers(pkg) }.getOrDefault(-1)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                show(if (code == 0) RemoveOldApp.CLEARED else visibilityStatus(pkg))
            }
        }.start()
    }

    private fun packageText(): String =
        findViewById<EditText>(R.id.removeOldPackage).text?.toString()?.trim().orEmpty()

    private fun visibilityStatus(pkg: String = packageText()): String {
        val visible = RemoveOldApp.isPackageName(pkg) && RemoveOldApp.visibleInThisProfile(packageManager, pkg)
        return RemoveOldApp.visibilityStatus(visible)
    }

    private fun show(text: String) {
        findViewById<TextView>(R.id.removeOldStatus).text = text
    }

    private companion object {
        const val SHIZUKU_REQUEST = 7401
    }
}
