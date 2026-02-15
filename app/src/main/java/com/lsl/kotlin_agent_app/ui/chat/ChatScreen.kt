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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var lastMessageCount by remember { mutableStateOf(0) }

    val isAtBottom by remember(uiState.messages.size) {
        derivedStateOf {
            val lastIndex = uiState.messages.lastIndex
            if (lastIndex < 0) return@derivedStateOf true
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleIndex >= lastIndex - 1
        }
    }

    val lastMessageLen = uiState.messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(uiState.messages.size, lastMessageLen, uiState.isSending) {
        val lastIndex = uiState.messages.lastIndex
        if (lastIndex < 0) return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect

        val countChanged = uiState.messages.size != lastMessageCount
        val shouldFollow = uiState.isSending || isAtBottom || countChanged
        if (!shouldFollow) return@LaunchedEffect

        if (countChanged) {
            lastMessageCount = uiState.messages.size
            listState.animateScrollToItem(lastIndex)
        } else {
            listState.scrollToItem(lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
            state = listState,
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
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Tool Trace (${traces.size})",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (traces.isNotEmpty()) {
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Less" else "More") }
                    }
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            }
            val show = if (expanded) traces.takeLast(3) else traces.takeLast(1)
            show.forEach { e ->
                Text(
                    text = "${e.name}: ${e.summary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 2 else 1,
                )
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
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val align = if (isUser) Alignment.End else Alignment.Start
    val borderColor: Color =
        if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.outlineVariant
    val shape =
        if (isUser) {
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
        } else {
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.88f),
            colors = CardDefaults.cardColors(containerColor = bg),
            border = BorderStroke(1.dp, borderColor),
            shape = shape,
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                text = message.content,
                color = fg,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
