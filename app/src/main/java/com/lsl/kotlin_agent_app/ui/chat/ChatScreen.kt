package com.lsl.kotlin_agent_app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (uiState.errorMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    modifier = Modifier.padding(12.dp),
                    text = uiState.errorMessage,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        ToolTracePanel(
            traces = uiState.toolTraces,
            onClear = onClear,
            modifier = Modifier.fillMaxWidth(),
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(uiState.messages) { message ->
                MessageBubble(message = message)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = inputText,
                onValueChange = { inputText = it },
                singleLine = true,
                label = { Text("Message") },
            )
            Button(
                enabled = !uiState.isSending,
                onClick = {
                    val toSend = inputText
                    if (toSend.isNotBlank()) {
                        inputText = ""
                    }
                    onSend(toSend)
                },
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun ToolTracePanel(
    traces: List<ToolTraceEvent>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Tool Trace (${traces.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onClear) { Text("Clear") }
            }
            if (traces.isEmpty()) {
                Text(
                    text = "No tool calls yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                traces.takeLast(3).forEach { e ->
                    Text(
                        text = "${e.name}: ${e.summary}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == ChatRole.User
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val align = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.88f),
            colors = CardDefaults.cardColors(containerColor = bg),
        ) {
            Text(
                modifier = Modifier.padding(12.dp),
                text = message.content,
                color = fg,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
