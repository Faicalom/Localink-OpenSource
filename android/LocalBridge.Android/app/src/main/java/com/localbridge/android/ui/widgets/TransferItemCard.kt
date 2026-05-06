package com.localbridge.android.ui.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localbridge.android.models.TransferItem
import com.localbridge.android.ui.LocalAppStrings

@Composable
fun TransferItemCard(
    transfer: TransferItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val strings = LocalAppStrings.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TransferPreviewPane(transfer = transfer)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(transfer.fileName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${transfer.peerName} · ${strings.transferDirection(transfer.direction)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text(strings.transferStatus(transfer.status)) }
                )
            }

            LinearProgressIndicator(
                progress = { transfer.progress },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "${transfer.progressPercent}% · ${transfer.sizeLabel}",
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = "${strings.speedLabel(transfer.speedBytesPerSecond)} · ${strings.etaLabel(transfer.estimatedSecondsRemaining)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!transfer.savedPath.isNullOrBlank()) {
                Text(
                    transfer.savedPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (transfer.lastError.isNotBlank()) {
                Text(
                    transfer.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            val actions = buildList {
                if (transfer.canPause) add("pause")
                if (transfer.canResume) add("resume")
                if (transfer.canCancel) add("cancel")
                if (transfer.canOpen) {
                    add("open")
                    add("share")
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) {
                items(actions, key = { it }) { action ->
                    when (action) {
                        "pause" -> TextButton(onClick = onPause) { Text(strings["pause"]) }
                        "resume" -> TextButton(onClick = onResume) { Text(strings["resume"]) }
                        "cancel" -> TextButton(onClick = onCancel) { Text(strings["cancel"]) }
                        "open" -> TextButton(onClick = onOpen) { Text(strings["open"]) }
                        "share" -> TextButton(onClick = onShare) { Text(strings["share"]) }
                    }
                }
            }
        }
    }
}
