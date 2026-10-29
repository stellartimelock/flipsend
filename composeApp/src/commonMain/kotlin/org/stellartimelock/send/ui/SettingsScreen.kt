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
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(bridge: PlatformBridge, onBack: () -> Unit, onReset: () -> Unit) {
    var deviceName by remember { mutableStateOf(bridge.deviceName()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }

        Spacer(Modifier.height(8.dp))

        Text("Device name", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { bridge.setDeviceName(deviceName) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save name")
        }

        Spacer(Modifier.height(8.dp))

        Text("Receive folder", style = MaterialTheme.typography.labelLarge)
        Text(
            bridge.folderLabel(),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = { bridge.pickFolder { } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Change folder")
        }

        Text("Plan", style = MaterialTheme.typography.labelLarge)
        Text(bridge.planLabel(), style = MaterialTheme.typography.bodyMedium)

        Text("Remote desktop", style = MaterialTheme.typography.labelLarge)
        Text(
            "Remote desktop is not available on iPhone. You can still send files and watch a shared screen.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onReset,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Reset app data…")
        }
    }
}
