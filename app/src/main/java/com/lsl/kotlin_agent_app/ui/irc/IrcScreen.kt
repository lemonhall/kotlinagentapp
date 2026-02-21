package com.lsl.kotlin_agent_app.ui.irc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IrcScreen(
    vm: IrcViewModel,
    onBack: () -> Unit,
) {
    val st by vm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.start()
    }

    var showChannelDialog by remember { mutableStateOf(false) }
    var showSendConfirm by remember { mutableStateOf<IrcSendDraft?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    if (showChannelDialog) {
        IrcChannelDialog(
            initial = st.selectedChannel.ifBlank { st.defaultChannel },
            onDismiss = { showChannelDialog = false },
            onSubmit = { ch ->
                showChannelDialog = false
                vm.setSelectedChannel(ch)
                vm.pullNow()
            },
        )
    }

    if (showSendConfirm != null) {
        val draft = showSendConfirm!!
        AlertDialog(
            onDismissRequest = { showSendConfirm = null },
            title = { Text("确认发送") },
            text = { Text("发送到非默认目标：${draft.to}\n\n继续吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSendConfirm = null
                        vm.send(to = draft.to, text = draft.text, confirmNonDefault = true)
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
                title = {
                    Text(
                        text = "IRC",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                actions = {
                    Box {
                        TextButton(onClick = { showMenu = true }) { Text("菜单") }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("凭据") },
                                onClick = {
                                    showMenu = false
                                    vm.openCredentialsEditor()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("连接") },
                                onClick = {
                                    showMenu = false
                                    vm.connect(force = false)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("断开") },
                                onClick = {
                                    showMenu = false
                                    vm.disconnect()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("频道") },
                                onClick = {
                                    showMenu = false
                                    showChannelDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("拉取") },
                                onClick = {
                                    showMenu = false
                                    vm.pullNow()
                                },
                            )
                        }
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

            val ch = st.selectedChannel.ifBlank { st.defaultChannel }
            Text(
                text =
                    buildString {
                        append("状态：")
                        append(st.state.ifBlank { "unknown" })
                        if (st.defaultChannel.isNotBlank()) {
                            append(" · 默认：")
                            append(st.defaultChannel)
                        }
                        if (ch.isNotBlank() && ch != st.defaultChannel) {
                            append(" · 当前：")
                            append(ch)
                        }
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            if (!st.hasCredentials) {
                Text(
                    text = "未检测到 IRC 凭据（.agents/skills/irc-cli/secrets/.env）。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.openCredentialsEditor() }) { Text("打开 .env") }
                Spacer(Modifier.height(12.dp))
            }

            if (st.isLoading) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
                Spacer(Modifier.height(10.dp))
            }

            val list =
                st.messages
                    .asSequence()
                    .filter { ch.isBlank() || it.channel == ch }
                    .toList()

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.key }) { line ->
                    IrcLineCard(line = line)
                }
            }

            Spacer(Modifier.height(10.dp))

            var to by remember(st.defaultChannel) { mutableStateOf(st.defaultChannel) }
            var text by remember { mutableStateOf("") }

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it },
                    modifier = Modifier.weight(0.42f),
                    label = { Text("To") },
                    singleLine = true,
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(0.58f),
                    label = { Text("Message") },
                    singleLine = true,
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        val target = to.trim().ifBlank { st.defaultChannel }
                        val msg = text.trim()
                        if (msg.isBlank()) return@TextButton
                        if (target.isNotBlank() && st.defaultChannel.isNotBlank() && target != st.defaultChannel) {
                            showSendConfirm = IrcSendDraft(to = target, text = msg)
                        } else {
                            vm.send(to = target, text = msg, confirmNonDefault = false)
                        }
                        text = ""
                    },
                    enabled = st.hasCredentials,
                ) { Text("发送") }
            }
        }
    }
}

@Composable
private fun IrcLineCard(line: IrcChatLine) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.CHINA) }
    val ts = fmt.format(Date(line.tsMs))
    val me = line.direction == "out"
    val head = if (me) "我" else line.nick.ifBlank { "?" }

    Column(
        modifier =
            Modifier
                .fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = head,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = ts,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun IrcChannelDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSubmit: (channel: String) -> Unit,
) {
    var ch by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换频道") },
        text = {
            Column {
                OutlinedTextField(
                    value = ch,
                    onValueChange = { ch = it },
                    label = { Text("Channel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "提示：当前实现只保证已加入默认频道；切换到其他频道可能无法接收消息（取决于服务器推送与 join 状态）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(ch.trim()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private data class IrcSendDraft(
    val to: String,
    val text: String,
)
