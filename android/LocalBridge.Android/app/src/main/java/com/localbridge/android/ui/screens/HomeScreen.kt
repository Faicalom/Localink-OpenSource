package com.localbridge.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localbridge.android.core.AppConstants
import com.localbridge.android.ui.LocalAppStrings
import com.localbridge.android.ui.widgets.SettingRow

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onOpenDevices: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenTransfers: () -> Unit
) {
    val strings = LocalAppStrings.current
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = AppConstants.appName,
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            text = strings["app_subtitle"],
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HomeMetricTile(
                            modifier = Modifier.weight(1f),
                            label = strings["preferred_mode_title"],
                            value = strings.modeLabel(uiState.settings.preferredMode)
                        )
                        HomeMetricTile(
                            modifier = Modifier.weight(1f),
                            label = strings["devices_title"],
                            value = uiState.discoveredPeersCount.toString()
                        )
                        HomeMetricTile(
                            modifier = Modifier.weight(1f),
                            label = strings["trusted_devices"],
                            value = uiState.trustedDevicesCount.toString()
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onOpenDevices, modifier = Modifier.weight(1f)) {
                    Text(strings["open_devices"])
                }
                Button(onClick = onOpenChat, modifier = Modifier.weight(1f)) {
                    Text(strings["open_chat"])
                }
                Button(onClick = onOpenTransfers, modifier = Modifier.weight(1f)) {
                    Text(strings["open_transfers"])
                }
            }
        }

        item {
            SettingRow(
                title = strings["active_peer_title"],
                value = uiState.connectionState.connectedPeer?.displayName ?: strings["no_active_peer"]
            )
        }

        item {
            SettingRow(
                title = strings["pairing_title"],
                value = strings["pairing_value"],
                supporting = strings["pairing_supporting"]
            )
        }
    }
}

@Composable
private fun HomeMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}
