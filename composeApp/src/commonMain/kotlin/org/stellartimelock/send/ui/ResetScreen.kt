package org.stellartimelock.send.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reset screen — wipes all app data after the user types "RESET".
 *
 * Rules:
 * - Large, readable text. No crammed layout.
 * - No phone-passcode unlock.
 * - If biometrics are on, prompt automatically before revealing the button.
 * - The wipe button is only enabled when the user has typed RESET exactly.
 */
@Composable
fun ResetScreen(bridge: PlatformBridge, onDone: () -> Unit) {
    var confirmText by remember { mutableStateOf("") }
    var biometricPassed by remember { mutableStateOf(!bridge.biometricsAvailable()) }
    var biometricRequested by remember { mutableStateOf(false) }

    if (bridge.biometricsAvailable() && !biometricRequested) {
        biometricRequested = true
        bridge.authenticateBiometrics { success -> biometricPassed = success }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "⚠ Reset",
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Text(
            text = "This will permanently delete your device identity, pairing, and all app settings. You cannot undo this.",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            textAlign = TextAlign.Center,
            lineHeight = 28.sp
        )

        Spacer(Modifier.height(8.dp))

        if (biometricPassed) {
            Text(
                text = "Type RESET to confirm",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                textAlign = TextAlign.Center
            )

            OutlinedTextField(
                value = confirmText,
                onValueChange = { confirmText = it.uppercase() },
                placeholder = { Text("RESET", fontSize = 20.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.fillMaxWidth().height(80.dp)
            )

            Button(
                onClick = {
                    if (confirmText == "RESET") {
                        bridge.resetAll(confirmed = true)
                        onDone()
                    }
                },
                enabled = confirmText == "RESET",
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Text("Erase Everything", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Text(
                text = "Authenticate with biometrics to continue.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.weight(1f))
    }
}
