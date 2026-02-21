package com.lsl.kotlin_agent_app.ui.qqmail

import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lsl.kotlin_agent_app.ui.markdown.MarkwonProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QqMailScreen(
    vm: QqMailViewModel,
    onBack: () -> Unit,
) {
    val st by vm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.refreshLocal()
    }

    var showFetchDialog by remember { mutableStateOf(false) }
    var showComposeDialog by remember { mutableStateOf(false) }
    var showSendConfirm by remember { mutableStateOf<QqMailSendDraft?>(null) }

    if (showFetchDialog) {
        QqMailFetchDialog(
            onDismiss = { showFetchDialog = false },
            onSubmit = { folder, limit ->
                showFetchDialog = false
                vm.fetch(folder = folder, limit = limit)
            },
        )
    }

    if (showComposeDialog) {
        QqMailComposeDialog(
            onDismiss = { showComposeDialog = false },
            onSubmit = { to, subject, body ->
                showComposeDialog = false
                showSendConfirm = QqMailSendDraft(to = to, subject = subject, body = body)
            },
        )
    }

    if (showSendConfirm != null) {
        val draft = showSendConfirm!!
        AlertDialog(
            onDismissRequest = { showSendConfirm = null },
            title = { Text("确认发送") },
            text = { Text("确定发送给 ${draft.to} 吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSendConfirm = null
                        vm.send(to = draft.to, subject = draft.subject, body = draft.body)
                    },
                ) { Text("发送") }
            },
            dismissButton = {
                TextButton(onClick = { showSendConfirm = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (st.openMessage != null) "邮件详情" else "QQMail") },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            if (st.openMessage != null) vm.closeMessage() else onBack()
                        },
                    ) { Text("返回") }
                },
                actions = {
                    if (st.openMessage == null) {
                        TextButton(onClick = { vm.openCredentialsEditor() }) { Text("凭据") }
                        TextButton(onClick = { showFetchDialog = true }) { Text("拉取") }
                        TextButton(onClick = { showComposeDialog = true }) { Text("写邮件") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp),
        ) {
            if (st.errorMessage != null) {
                Text(text = st.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            if (!st.hasCredentials) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("未配置 QQMail 凭据", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "请在“凭据”里填写 `.env`（EMAIL_ADDRESS / EMAIL_PASSWORD）。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (st.isLoading) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
                Spacer(Modifier.height(12.dp))
            }

            if (st.openMessage != null) {
                MessageView(st.openMessage!!)
            } else {
                Tabs(
                    selected = st.selectedMailbox,
                    onSelect = { vm.setMailbox(it) },
                )
                Spacer(Modifier.height(10.dp))

                val list = if (st.selectedMailbox == QqMailMailbox.Inbox) st.inbox else st.sent
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.agentsPath }) { item ->
                        MailItemCard(
                            item = item,
                            onOpen = { vm.openMessage(item) },
                        )
                    }
                    if (list.isEmpty()) {
                        item {
                            Text(
                                text = "暂无邮件（点右上角“拉取”）",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tabs(
    selected: QqMailMailbox,
    onSelect: (QqMailMailbox) -> Unit,
) {
    val idx = if (selected == QqMailMailbox.Inbox) 0 else 1
    TabRow(selectedTabIndex = idx) {
        Tab(selected = idx == 0, onClick = { onSelect(QqMailMailbox.Inbox) }, text = { Text("收件箱") })
        Tab(selected = idx == 1, onClick = { onSelect(QqMailMailbox.Sent) }, text = { Text("已发送") })
    }
}

@Composable
private fun MailItemCard(
    item: QqMailLocalItem,
    onOpen: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onOpen() },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(item.subject.ifBlank { "(无主题)" }, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.peer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = item.preview,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.dateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageView(msg: QqMailLocalMessage) {
    val context = LocalContext.current
    val markwon = remember(context) { MarkwonProvider.get(context) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(msg.item.subject.ifBlank { "(无主题)" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = msg.item.peer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextView(ctx).apply {
                    setTextIsSelectable(true)
                }
            },
            update = { tv ->
                markwon.setMarkdown(tv, msg.bodyMarkdown)
            },
        )
    }
}

private data class QqMailSendDraft(
    val to: String,
    val subject: String,
    val body: String,
)

@Composable
private fun QqMailFetchDialog(
    onDismiss: () -> Unit,
    onSubmit: (folder: String, limit: Int) -> Unit,
) {
    var folder by remember { mutableStateOf("INBOX") }
    var limitText by remember { mutableStateOf("20") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("拉取邮件") },
        text = {
            Column {
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text("Folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { limitText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Limit") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val limit = limitText.toIntOrNull()?.coerceIn(0, 200) ?: 20
                    onSubmit(folder.trim().ifBlank { "INBOX" }, limit)
                },
            ) { Text("开始") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun QqMailComposeDialog(
    onDismiss: () -> Unit,
    onSubmit: (to: String, subject: String, body: String) -> Unit,
) {
    var to by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("写邮件") },
        text = {
            Column(modifier = Modifier.fillMaxHeight(0.8f)) {
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it },
                    label = { Text("To") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Subject") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Body (Text)") },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(to.trim(), subject, body) },
                enabled = to.trim().isNotBlank(),
            ) { Text("下一步") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
