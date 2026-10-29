package org.stellartimelock.send

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.stellartimelock.send.core.LogRedaction
import org.stellartimelock.send.core.ServiceResult

object VaultIntents {
    const val ACTION_PUSH_PASSWORD = "org.stellartimelock.send.action.PUSH_PASSWORD"
    const val EXTRA_PASSWORD = "password"
    const val PERMISSION = "org.stellartimelock.send.permission.VAULT_HANDOFF"
}

class VaultHandoffActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showScrollingMessage("Sending to your PC.")
        val password = intent?.getStringExtra(VaultIntents.EXTRA_PASSWORD)
        intent?.removeExtra(VaultIntents.EXTRA_PASSWORD)
        setIntent(intent)
        Thread {
            val result = runCatching { StellarSendClient(applicationContext).pushPasswordToPc(password) }
                .getOrElse {
                    ServiceResult.fail("", "io", LogRedaction.redact(it.message ?: "Could not reach the paired PC."))
                }
            runOnUiThread {
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }
}
