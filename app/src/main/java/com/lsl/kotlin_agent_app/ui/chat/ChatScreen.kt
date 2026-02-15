package com.lsl.kotlin_agent_app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lsl.kotlin_agent_app.R

@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onStop: () -> Unit,
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
            if (uiState.isSending) {
                Button(
                    modifier = Modifier.size(48.dp),
                    onClick = onStop,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_stop),
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.onError,
                    )
                }
            } else {
                Button(
                    enabled = inputText.isNotBlank(),
                    onClick = {
                        val toSend = inputText
                        if (toSend.isNotBlank()) {
                            inputText = ""
                        }
                        onSend(toSend)
                    },
                    modifier = Modifier.size(48.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_send),
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
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
    var selected by remember { mutableStateOf<ToolTraceEvent?>(null) }
    val clipboard = LocalClipboardManager.current

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
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (traces.isNotEmpty()) {
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Less" else "More") }
                    }
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            }
            val show = if (expanded) traces.takeLast(12) else traces.takeLast(1)
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (expanded) 120.dp else 48.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                show.forEach { e ->
                    val color = if (e.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    Text(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !e.details.isNullOrBlank()) { selected = e },
                        text = "${e.name}: ${e.summary}",
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        maxLines = if (expanded) 2 else 1,
                    )
                }
            }
        }
    }

    val toShow = selected
    if (toShow != null) {
        val details = toShow.details.orEmpty()
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(toShow.name) },
            text = {
                SelectionContainer {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(text = toShow.summary, style = MaterialTheme.typography.bodySmall)
                        if (details.isNotBlank()) {
                            Text(text = "\n$details", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
            dismissButton = {
                if (details.isNotBlank()) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(details)) }) { Text("Copy") }
                }
            },
        )
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
