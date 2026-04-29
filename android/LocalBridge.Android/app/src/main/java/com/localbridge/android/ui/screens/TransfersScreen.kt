package com.localbridge.android.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localbridge.android.features.transfers.TransfersUiState
import com.localbridge.android.models.TransferItem
import com.localbridge.android.ui.LocalAppStrings
import com.localbridge.android.ui.widgets.TransferFileActions
import com.localbridge.android.ui.widgets.TransferItemCard

@Composable
fun TransfersScreen(
    uiState: TransfersUiState,
    onPickFiles: (List<android.net.Uri>) -> Unit,
    onPause: (TransferItem) -> Unit,
    onResume: (TransferItem) -> Unit,
    onCancel: (TransferItem) -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val snackbarHostState = remember { SnackbarHostState() }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        onPickFiles(uris)
    }

    LaunchedEffect(uiState.connectionState.lastError) {
        uiState.connectionState.lastError
            ?.takeIf { it.isNotBlank() }
            ?.let { snackbarHostState.showSnackbar(it.replace('_', ' ')) }
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
                    Text(strings["transfers_title"], style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${strings["transfers_supporting"]} ${strings.fileLimitLabel(com.localbridge.android.core.AppConstants.transferMaxFileSizeBytes)}.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        filePickerLauncher.launch(
                            arrayOf("image/*", "application/pdf", "text/plain", "video/*", "*/*")
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.connectionState.isConnected
                ) {
                    Text(if (uiState.connectionState.isConnected) strings["select_files"] else strings["connect_to_send"])
                }
            }

            if (uiState.transfers.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = strings["no_transfers"],
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (uiState.connectionState.isConnected) strings["select_files"] else strings["connect_to_send"],
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(uiState.transfers, key = { it.id }) { transfer ->
                TransferItemCard(
                    transfer = transfer,
                    onPause = { onPause(transfer) },
                    onResume = { onResume(transfer) },
                    onCancel = { onCancel(transfer) },
                    onOpen = {
                        val opened = TransferFileActions.open(context, transfer)
                        if (!opened) {
                            Toast.makeText(context, strings.couldNotOpen(transfer.fileName), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onShare = {
                        val shared = TransferFileActions.share(context, transfer)
                        if (!shared) {
                            Toast.makeText(context, strings.couldNotShare(transfer.fileName), Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}
