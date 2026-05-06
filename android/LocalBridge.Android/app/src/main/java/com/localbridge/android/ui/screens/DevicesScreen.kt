package com.localbridge.android.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localbridge.android.features.devices.DevicesUiState
import com.localbridge.android.models.ConnectionLifecycleState
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.ui.LocalAppStrings
import com.localbridge.android.ui.widgets.DevicePeerCard
import com.localbridge.android.ui.widgets.StatusSummaryCard

@Composable
fun DevicesScreen(
    uiState: DevicesUiState,
    onRefresh: () -> Unit,
    onPairingTokenChanged: (String) -> Unit,
    onConnect: (DevicePeer) -> Unit,
    onDisconnect: () -> Unit,
    onToggleTrust: (DevicePeer) -> Unit
) {
    val strings = LocalAppStrings.current
    val snackbarHostState = remember { SnackbarHostState() }
    val discoverableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    LaunchedEffect(uiState.connectionState.lastError) {
        uiState.connectionState.lastError
            ?.takeIf { it.isNotBlank() }
            ?.let { snackbarHostState.showSnackbar(it.replace('_', ' ')) }
    }

    val connectionHeadline = strings.connectionLifecycleLabel(
        state = uiState.connectionState.lifecycleState,
        isScanning = uiState.discoveryState.isScanning
    )
    val connectionSupporting = buildString {
        append(
            if (uiState.discoveryState.isScanning &&
                uiState.connectionState.lifecycleState in setOf(
                    ConnectionLifecycleState.Idle,
                    ConnectionLifecycleState.Disconnected
                )
            ) {
                uiState.discoveryState.statusText
            } else {
                uiState.connectionState.statusText
            }
        )

        if (uiState.connectionState.handshakeSummary.isNotBlank()) {
            append(" · ")
            append(uiState.connectionState.handshakeSummary)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 20.dp + innerPadding.calculateTopPadding(),
                end = 20.dp,
                bottom = 20.dp + innerPadding.calculateBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(strings["devices_title"], style = MaterialTheme.typography.headlineSmall)
                    Text(
                        strings["devices_hint"],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                StatusSummaryCard(
                    title = strings["discovery_title"],
                    headline = if (uiState.discoveryState.isScanning) {
                        strings["discovery_scanning"]
                    } else if (uiState.discoveryState.isRunning) {
                        strings["discovery_listening"]
                    } else {
                        strings["discovery_stopped"]
                    },
                    supporting = uiState.discoveryState.statusText
                )
            }

            item {
                StatusSummaryCard(
                    title = strings["connection_title"],
                    headline = connectionHeadline,
                    supporting = connectionSupporting
                )
            }

            item {
                StatusSummaryCard(
                    title = strings["pairing_token"],
                    headline = uiState.connectionState.localPairingCode,
                    supporting = strings["android_pairing_code_supporting"]
                )
            }

            item {
                OutlinedTextField(
                    value = uiState.pairingToken,
                    onValueChange = onPairingTokenChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings["pairing_token"]) },
                    placeholder = { Text(strings["pairing_placeholder"]) }
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                        Text(if (uiState.discoveryState.isScanning) strings["scanning_button"] else strings["refresh_lan_peers"])
                    }
                    Button(
                        onClick = {
                            discoverableLauncher.launch(
                                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                                }
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(strings["make_bluetooth_discoverable"])
                    }
                }
            }

            item {
                Text(
                    text = strings.foundWindowsPeers(uiState.devices.size),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (uiState.devices.isEmpty()) {
                item {
                    StatusSummaryCard(
                        title = strings["no_windows_peers_title"],
                        headline = strings["no_windows_peers_headline"],
                        supporting = strings["no_windows_peers_supporting"]
                    )
                }
            }

            items(uiState.devices, key = { it.id }) { peer ->
                DevicePeerCard(
                    peer = peer,
                    isConnected = uiState.connectionState.connectedPeer?.id == peer.id,
                    onConnect = { onConnect(peer) },
                    onDisconnect = onDisconnect,
                    onToggleTrust = { onToggleTrust(peer) }
                )
            }
        }
    }
}
