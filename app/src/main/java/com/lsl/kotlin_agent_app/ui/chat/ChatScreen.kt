package com.lsl.kotlin_agent_app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import com.lsl.kotlin_agent_app.R
import com.lsl.kotlin_agent_app.web.WebPreviewFrame
import android.widget.Toast

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onStop: () -> Unit,
    onOpenReport: (String) -> Unit = {},
    onCloseReport: () -> Unit = {},
    webPreviewVisible: Boolean = false,
    webPreviewFrame: WebPreviewFrame = WebPreviewFrame(bitmap = null, url = null),
    onToggleWebPreview: () -> Unit = {},
    onCloseWebPreview: () -> Unit = {},
    onOpenWeb: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var lastMessageCount by remember { mutableStateOf(0) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

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
            webPreviewVisible = webPreviewVisible,
            onToggleWebPreview = onToggleWebPreview,
            modifier = Modifier.fillMaxWidth(),
        )

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                },
                            )
                        },
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.messages) { message ->
                    val reportLink = uiState.reportLinksByMessageId[message.id] ?: extractReportLinkFromText(message.content)
                    MessageBubble(
                        message = message,
                        reportLink = reportLink,
                        onOpenReport = onOpenReport,
                    )
                }
            }

            if (webPreviewVisible) {
                WebPreviewPiP(
                    frame = webPreviewFrame,
                    onOpenWeb = onOpenWeb,
                    onClose = onCloseWebPreview,
                    modifier =
                        Modifier
                            .padding(8.dp)
                            .align(Alignment.TopEnd),
                )
            }
        }

        if (uiState.reportViewerPath != null || uiState.reportViewerError != null || uiState.isReportViewerLoading) {
            ReportViewerDialog(
                title = uiState.reportViewerPath ?: "Report",
                markdown = uiState.reportViewerText,
                isLoading = uiState.isReportViewerLoading,
                error = uiState.reportViewerError,
                onClose = onCloseReport,
            )
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions =
                    KeyboardActions(
                        onSend = {
                            val toSend = inputText
                            if (toSend.isBlank()) return@KeyboardActions
                            inputText = ""
                            onSend(toSend)
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                        },
                    ),
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
                            onSend(toSend)
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                        }
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
private fun WebPreviewPiP(
    frame: WebPreviewFrame,
    onOpenWeb: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .width(220.dp)
                .height(132.dp)
                .clickable(onClick = onOpenWeb),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val bmp = frame.bitmap
            if (bmp != null) {
                Image(
                    modifier = Modifier.fillMaxSize(),
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Web preview",
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                ) {
                    Text(
                        text = "Web 预览",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp),
                shape = RoundedCornerShape(13.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                IconButton(
                    modifier = Modifier.fillMaxSize(),
                    onClick = onClose,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close_24),
                        contentDescription = "Close preview",
                        tint = MaterialTheme.colorScheme.onError,
                    )
                }
            }

            val url = frame.url?.takeIf { it.isNotBlank() } ?: "about:blank"
            Card(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                shape = RoundedCornerShape(0.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    ),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ToolTracePanel(
    traces: List<ToolTraceEvent>,
    onClear: () -> Unit,
    webPreviewVisible: Boolean,
    onToggleWebPreview: () -> Unit,
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
                    TextButton(onClick = onToggleWebPreview) { Text(if (webPreviewVisible) "PreviewWeb:On" else "PreviewWeb:Off") }
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
    reportLink: ReportLink? = null,
    onOpenReport: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == ChatRole.User
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
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
            modifier =
                Modifier
                    .fillMaxWidth(0.88f)
                    .pointerInput(message.content) {
                        detectTapGestures(
                            onLongPress = {
                                val text = message.content.trim()
                                if (text.isNotEmpty()) {
                                    clipboard.setText(AnnotatedString(text))
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    },
            colors = CardDefaults.cardColors(containerColor = bg),
            border = BorderStroke(1.dp, borderColor),
            shape = shape,
        ) {
            val pad = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            if (!isUser && message.role == ChatRole.Assistant) {
                val fontSize = MaterialTheme.typography.bodyLarge.fontSize.value.takeIf { it > 0f } ?: 16f
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (message.content.isNotBlank()) {
                        MarkdownText(
                            markdown = message.content,
                            color = fg,
                            textSizeSp = fontSize,
                        )
                    }
                     val status = message.statusLine?.trim().orEmpty()
                     if (status.isNotBlank()) {
                        val (statusText, statusTimer) =
                            run {
                                val parts = status.split('\n', limit = 2)
                                val a = parts.getOrNull(0).orEmpty().trim()
                                val b = parts.getOrNull(1).orEmpty().trim()
                                a to b
                            }
                        Column(
                            modifier = Modifier.padding(top = if (message.content.isNotBlank()) 8.dp else 0.dp),
                        ) {
                            if (statusText.isNotBlank()) {
                                Text(
                                    text = statusText,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (statusTimer.isNotBlank()) {
                                Text(
                                    text = statusTimer,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                     }
                 }
             } else {
                 Text(
                    modifier = pad,
                    text = message.content,
                    color = fg,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        if (!isUser && reportLink != null && reportLink.path.isNotBlank()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth(0.88f)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { onOpenReport(reportLink.path) },
                ) {
                    Text("打开报告")
                }
            }
        }
    }
}

@Composable
private fun ReportViewerDialog(
    title: String,
    markdown: String?,
    isLoading: Boolean,
    error: String?,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    TextButton(onClick = onClose) { Text("关闭") }
                }

                val body =
                    when {
                        isLoading -> "正在加载…"
                        !error.isNullOrBlank() -> error
                        markdown.isNullOrBlank() -> "(empty)"
                        else -> null
                    }

                if (body != null) {
                    Text(
                        modifier = Modifier.padding(top = 8.dp),
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (!error.isNullOrBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(top = 8.dp),
                    ) {
                        MarkdownText(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp),
                            markdown = markdown!!,
                            color = MaterialTheme.colorScheme.onSurface,
                            textSizeSp = 16f,
                        )
                    }
                }
            }
        }
    }
}

private fun extractReportLinkFromText(text: String): ReportLink? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val m =
        Regex("(?m)^report_path:\\s*(\\S+)\\s*$").find(t)
            ?: return null
    val raw = m.groupValues.getOrNull(1)?.trim().orEmpty()
    if (raw.isBlank()) return null
    val normalized = normalizeAgentsPathFromReportPath(raw) ?: return null
    return ReportLink(path = normalized, summary = null)
}

private fun normalizeAgentsPathFromReportPath(rawPath: String): String? {
    val p = rawPath.trim().replace('\\', '/')
    if (p.isBlank()) return null
    if (p.startsWith(".agents/") || p == ".agents") return p
    if (p.startsWith("artifacts/") || p.startsWith("sessions/") || p.startsWith("skills/")) return ".agents/$p"
    val idx = p.indexOf("/.agents/")
    if (idx >= 0) {
        val rel = p.substring(idx + "/.agents/".length).trimStart('/')
        if (rel.isBlank()) return null
        return ".agents/$rel"
    }
    return null
}
