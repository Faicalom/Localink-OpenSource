package com.localbridge.android.ui.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.ui.LocalAppStrings

@Composable
fun DevicePeerCard(
    peer: DevicePeer,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleTrust: () -> Unit
) {
    val strings = LocalAppStrings.current
    val endpointLabel = if (peer.transportMode == AppConnectionMode.BluetoothFallback) {
        "BT ${peer.bluetoothAddress.ifBlank { "--" }}"
    } else {
        "${peer.ipAddress}:${peer.port}"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${peer.platform} · $endpointLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text(if (peer.isOnline) strings["state_connected"] else strings["state_disconnected"]) }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(strings.trustedLabel(peer.isTrusted)) })
                AssistChip(onClick = {}, label = { Text(strings.pairingLabel(peer.pairingRequired)) })
                AssistChip(onClick = {}, label = { Text(strings.modeLabel(peer.transportMode)) })
            }

            Text(
                text = "${strings["last_seen_prefix"]} ${peer.lastSeenAtUtc.ifBlank { "--" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isConnected) {
                    Button(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                        Text(strings["disconnect"])
                    }
                } else {
                    Button(onClick = onConnect, modifier = Modifier.weight(1f)) {
                        Text(strings["connect"])
                    }
                }
                OutlinedButton(onClick = onToggleTrust, modifier = Modifier.weight(1f)) {
                    Text(if (peer.isTrusted) strings["remove_trust"] else strings["trust"])
                }
            }
        }
    }
}
