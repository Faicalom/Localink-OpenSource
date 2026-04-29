package com.localbridge.android.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.localbridge.android.core.permissions.PermissionsController
import com.localbridge.android.features.settings.SettingsUiState
import com.localbridge.android.models.AppConnectionMode
import com.localbridge.android.models.AppLanguage
import com.localbridge.android.ui.LocalAppStrings
import com.localbridge.android.ui.widgets.SettingRow

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    permissionsController: PermissionsController,
    onPreferredModeChanged: (AppConnectionMode) -> Unit,
    onLanguageChanged: (AppLanguage) -> Unit,
    onReceiveFolderLabelChanged: (String) -> Unit,
    onReceiveTreeSelected: (String, String) -> Unit,
    onClearReceiveTree: () -> Unit,
    onDarkThemeChanged: (Boolean) -> Unit,
    onDeviceAliasChanged: (String) -> Unit,
    onRemoveTrustedDevice: (String) -> Unit,
    onClearChatHistory: () -> Unit,
    onClearTransferHistory: () -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    var aliasDraft by remember(uiState.settings.deviceAlias) { mutableStateOf(uiState.settings.deviceAlias) }
    var receiveFolderDraft by remember(uiState.settings.receiveFolderName) { mutableStateOf(uiState.settings.receiveFolderName) }
    val treePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }

            val displayName = DocumentFile.fromTreeUri(context, uri)?.name
                ?: uri.lastPathSegment
                ?: "Picked folder"
            onReceiveTreeSelected(uri.toString(), displayName)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(strings["settings_title"], style = MaterialTheme.typography.headlineSmall)
                Text(
                    strings["theme_storage_supporting"],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SettingRow(
                title = strings["active_receive_destination"],
                value = uiState.settings.receiveFolderLabel,
                supporting = strings["active_receive_destination_supporting"]
            )
        }

        item {
            Text(strings["dark_theme"], style = MaterialTheme.typography.titleMedium)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectionButton(
                    label = strings["theme_light"],
                    selected = !uiState.settings.darkThemeEnabled,
                    onClick = { onDarkThemeChanged(false) },
                    modifier = Modifier.weight(1f)
                )
                SelectionButton(
                    label = strings["theme_dark"],
                    selected = uiState.settings.darkThemeEnabled,
                    onClick = { onDarkThemeChanged(true) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Text(strings["app_language"], style = MaterialTheme.typography.titleMedium)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectionButton(
                    label = strings.languageLabel(AppLanguage.Arabic),
                    selected = uiState.settings.language == AppLanguage.Arabic,
                    onClick = { onLanguageChanged(AppLanguage.Arabic) },
                    modifier = Modifier.weight(1f)
                )
                SelectionButton(
                    label = strings.languageLabel(AppLanguage.English),
                    selected = uiState.settings.language == AppLanguage.English,
                    onClick = { onLanguageChanged(AppLanguage.English) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Text(strings["preferred_mode_title"], style = MaterialTheme.typography.titleMedium)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectionButton(
                    label = strings["mode_lan"],
                    selected = uiState.settings.preferredMode == AppConnectionMode.LocalWifiLan,
                    onClick = { onPreferredModeChanged(AppConnectionMode.LocalWifiLan) },
                    modifier = Modifier.weight(1f)
                )
                SelectionButton(
                    label = strings["mode_bluetooth"],
                    selected = uiState.settings.preferredMode == AppConnectionMode.BluetoothFallback,
                    onClick = { onPreferredModeChanged(AppConnectionMode.BluetoothFallback) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            OutlinedTextField(
                value = aliasDraft,
                onValueChange = {
                    aliasDraft = it
                    onDeviceAliasChanged(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings["device_alias"]) }
            )
        }

        item {
            Text(strings["receive_folder_title"], style = MaterialTheme.typography.titleMedium)
        }

        item {
            OutlinedTextField(
                value = receiveFolderDraft,
                onValueChange = {
                    receiveFolderDraft = it
                    onReceiveFolderLabelChanged(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings["fallback_subfolder"]) },
                supportingText = { Text(strings["fallback_subfolder_supporting"]) }
            )
        }

        item {
            SettingRow(
                title = strings["active_receive_destination"],
                value = uiState.settings.receiveFolderLabel,
                supporting = uiState.activeReceiveLocationDescription
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { treePickerLauncher.launch(null) }, modifier = Modifier.weight(1f)) {
                    Text(strings["pick_folder"])
                }
                if (uiState.settings.hasExternalReceiveFolder) {
                    OutlinedButton(
                        onClick = {
                            uiState.settings.receiveTreeUri?.let { treeUri ->
                                runCatching {
                                    context.contentResolver.releasePersistableUriPermission(
                                        treeUri.toUri(),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    )
                                }
                            }
                            onClearReceiveTree()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(strings["use_fallback"])
                    }
                }
            }
        }

        item {
            Text(strings["history_title"], style = MaterialTheme.typography.titleMedium)
        }

        item {
            Button(
                onClick = {
                    onClearChatHistory()
                    onClearTransferHistory()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text(strings["clear_all"])
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onClearChatHistory, modifier = Modifier.weight(1f)) {
                    Text(strings["clear_chat"])
                }
                OutlinedButton(onClick = onClearTransferHistory, modifier = Modifier.weight(1f)) {
                    Text(strings["clear_transfers"])
                }
            }
        }

        item {
            Text(strings["trusted_devices"], style = MaterialTheme.typography.titleMedium)
        }

        if (uiState.trustedDevices.isEmpty()) {
            item {
                SettingRow(
                    title = strings["no_trusted_devices_title"],
                    value = strings["no_trusted_devices_value"]
                )
            }
        }

        items(uiState.trustedDevices, key = { it.id }) { device ->
            SettingRow(
                title = device.displayName,
                value = device.id,
                supporting = "${device.platform} · ${device.ipAddress}:${device.port}",
                actionLabel = strings["remove"],
                onAction = { onRemoveTrustedDevice(device.id) }
            )
        }

        if (uiState.recentLogs.isNotEmpty()) {
            item {
                Text(strings["recent_logs"], style = MaterialTheme.typography.titleMedium)
            }

            items(uiState.recentLogs.take(4)) { entry ->
                SettingRow(
                    title = "${entry.level} · ${entry.timestampUtc}",
                    value = entry.message
                )
            }
        }
    }
}

@Composable
private fun SelectionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
            )
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier
        ) {
            Text(label)
        }
    }
}
