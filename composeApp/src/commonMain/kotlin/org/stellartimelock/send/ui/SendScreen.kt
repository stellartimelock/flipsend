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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Main send screen — shown after pairing.
 *
 * iOS note: the file picker is a UIDocumentPickerViewController launched
 * from Swift in [PlatformBridge.pickFolder]. The Kotlin side calls the bridge
 * method; SwiftUI presents the picker sheet.
 */
@Composable
fun SendScreen(
    bridge: PlatformBridge,
    onShowHistory: () -> Unit,
    onShowSettings: () -> Unit
) {
    val peerName = bridge.pairedPcName() ?: "PC"
    val folderLabel = bridge.folderLabel()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("FlipSend", style = MaterialTheme.typography.headlineMedium)
            Row {
                IconButton(onClick = onShowHistory) {
                    Icon(Icons.Default.History, contentDescription = "History")
                }
                IconButton(onClick = onShowSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Connected to", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(peerName, style = MaterialTheme.typography.headlineSmall)
                Text(folderLabel, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { bridge.pickFolder { /* folder picked */ } },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            Spacer(Modifier.height(8.dp))
            Text("Choose receive folder")
        }

        Text(
            text = if (bridge.history().isEmpty()) {
                "Nothing in progress."
            } else {
                "Received. Open a file or save it to Photos."
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        FilledTonalButton(
            onClick = onShowHistory,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Received files")
        }

        Text(
            text = "Relay cap is 100 MB a month, 5 MB a minute, and 48 MB per file here. Not 50 GB. Resets on the 1st. Same Wi-Fi has no relay cap.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.weight(1f))

        OutlinedButton(
            onClick = { bridge.unpair() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Unpair")
        }
    }
}
