package com.localbridge.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.qr.PairingQrPeerResolver
import com.localbridge.android.core.qr.PairingQrPayloadParser
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.ui.LocalAppStrings
import com.localbridge.android.ui.widgets.PairingQrScannerDialog
import com.localbridge.android.ui.widgets.SettingRow
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onOpenDevices: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenTransfers: () -> Unit,
    autoLaunchQrScanner: Boolean,
    onAutoLaunchQrScannerConsumed: () -> Unit,
    onConnectQrPeer: (DevicePeer, String) -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var isScannerVisible by rememberSaveable { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isScannerVisible = true
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(strings["scan_qr_permission_denied"])
            }
        }
    }
    val launchQrScanner = {
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission) {
            isScannerVisible = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(uiState.connectionState.lastError) {
        uiState.connectionState.lastError
            ?.takeIf { it.isNotBlank() }
            ?.let { snackbarHostState.showSnackbar(it.replace('_', ' ')) }
    }

    LaunchedEffect(autoLaunchQrScanner) {
        if (autoLaunchQrScanner && !isScannerVisible) {
            onAutoLaunchQrScannerConsumed()
            launchQrScanner()
        }
    }

    if (isScannerVisible) {
        PairingQrScannerDialog(
            onDismissRequest = { isScannerVisible = false },
            onRawValueScanned = { rawValue ->
                isScannerVisible = false
                val scanResult = PairingQrPayloadParser.parse(rawValue)
                when {
                    scanResult == null -> {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(strings["scan_qr_invalid"])
                        }
                    }

                    else -> {
                        val resolvedPeer = PairingQrPeerResolver.resolve(
                            scanResult = scanResult,
                            discoveredPeers = uiState.devices,
                            preferredMode = uiState.settings.preferredMode
                        )
                        if (resolvedPeer == null) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(strings["scan_qr_direct_connect_only"])
                            }
                        } else {
                            onConnectQrPeer(resolvedPeer, scanResult.pairingToken)
                        }
                    }
                }
            },
            onScannerError = {
                isScannerVisible = false
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(strings["scan_qr_camera_error"])
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 20.dp + innerPadding.calculateTopPadding(),
                end = 20.dp,
                bottom = 20.dp + innerPadding.calculateBottomPadding()
            ),
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
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
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

                            OutlinedButton(
                                onClick = launchQrScanner,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .defaultMinSize(minWidth = 0.dp)
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Outlined.QrCodeScanner,
                                    contentDescription = strings["scan_pairing_qr"]
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("QR")
                            }
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
