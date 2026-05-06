package com.localbridge.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localbridge.android.features.chat.ChatUiState
import com.localbridge.android.models.ChatMessage
import com.localbridge.android.ui.LocalAppStrings
import com.localbridge.android.ui.widgets.ChatMessageBubble

@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (ChatMessage) -> Unit
) {
    val strings = LocalAppStrings.current
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.extraLarge
        ) {
            if (uiState.messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = strings["chat_empty_headline"],
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = strings["chat_empty_supporting"],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        ChatMessageBubble(
                            message = message,
                            onRetry = { onRetry(message) }
                        )
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = uiState.draftMessage,
                    onValueChange = onDraftChanged,
                    modifier = Modifier.weight(1f),
                    label = { Text(strings["message_label"]) },
                    placeholder = { Text(strings["message_placeholder"]) },
                    minLines = 1,
                    maxLines = 4
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = uiState.canSend,
                    modifier = Modifier.size(52.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = strings["send"]
                    )
                }
            }
        }
    }
}
