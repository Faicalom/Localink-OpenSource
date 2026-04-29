package com.localbridge.android.ui.widgets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localbridge.android.models.ChatMessage
import com.localbridge.android.models.ChatMessageStatus
import com.localbridge.android.ui.LocalAppStrings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val bubbleTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    var menuExpanded by remember(message.id) { mutableStateOf(false) }
    val timeLabel = runCatching {
        Instant.parse(message.timestampUtc)
            .atZone(ZoneId.systemDefault())
            .format(bubbleTimeFormatter)
    }.getOrElse { message.timestampUtc.take(16) }
    val statusLabel = strings.chatStatus(message.status)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start
    ) {
        Box {
            Surface(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .combinedClickable(
                        onClick = { menuExpanded = true },
                        onLongClick = { menuExpanded = true }
                    ),
                color = if (message.isMine) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!message.isMine) {
                        Text(
                            text = message.senderName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(message.text, style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "$timeLabel · $statusLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (message.isMine && message.status == ChatMessageStatus.Failed) {
                            OutlinedButton(onClick = onRetry) {
                                Text(strings["retry"])
                            }
                        }
                    }
                    if (message.isMine && message.status == ChatMessageStatus.Failed && message.lastError.isNotBlank()) {
                        Text(
                            text = message.lastError,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(strings["copy"]) },
                    onClick = {
                        copyMessageText(context, message.text, strings["message_copied"])
                        menuExpanded = false
                    }
                )
            }
        }
    }
}

private fun copyMessageText(
    context: Context,
    text: String,
    copiedMessage: String
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Localink chat", text))
    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
}
