package org.stellartimelock.send.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PairScreen(bridge: PlatformBridge, onPaired: () -> Unit) {
    var qrData by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var pairingError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        qrData = bridge.getQrCode()
    }

    val paired = bridge.pairedPcName()
    LaunchedEffect(paired) {
        if (paired != null) onPaired()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("FlipSend", style = MaterialTheme.typography.headlineMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QrCodeView(data = qrData, modifier = Modifier.size(260.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Type a code") },
                    singleLine = true
                )
                Button(
                    onClick = {
                        val error = bridge.pairFromQr(typed.trim())
                        pairingError = error
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Type a code") }
                OutlinedButton(
                    onClick = {
                        val error = bridge.pairFromQr(typed.trim())
                        pairingError = error
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Scan a code") }
            }
        }
        Text(
            "Relay cap is 100 MB a month, 5 MB a minute, and 48 MB per file here. The 50 GB figure is the shared pool, not yours. It resets on the 1st.",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
        pairingError?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
