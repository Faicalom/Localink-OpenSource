package com.localbridge.android.ui.widgets

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localbridge.android.models.TransferDirection
import com.localbridge.android.models.TransferItem
import com.localbridge.android.ui.LocalAppStrings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val transferBubbleTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun ChatTransferBubble(
    transfer: TransferItem
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val timeLabel = runCatching {
        Instant.parse(transfer.createdAtUtc)
            .atZone(ZoneId.systemDefault())
            .format(transferBubbleTimeFormatter)
    }.getOrElse { transfer.createdAtUtc.take(16) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (transfer.direction == TransferDirection.Outgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            color = if (transfer.direction == TransferDirection.Outgoing) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${strings.transferDirection(transfer.direction)} ${strings["preview_file_suffix"]}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = transfer.peerName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TransferPreviewPane(transfer = transfer)
                Text(
                    text = transfer.fileName,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "${strings.transferStatus(transfer.status)} · ${transfer.sizeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (transfer.canOpen) {
                        TextButton(
                            onClick = {
                                if (!TransferFileActions.open(context, transfer)) {
                                    Toast.makeText(context, strings.couldNotOpen(transfer.fileName), Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text(strings["open"])
                        }
                        TextButton(
                            onClick = {
                                if (!TransferFileActions.share(context, transfer)) {
                                    Toast.makeText(context, strings.couldNotShare(transfer.fileName), Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text(strings["share"])
                        }
                    }
                }
            }
        }
    }
}
