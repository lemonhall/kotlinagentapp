package com.lsl.kotlin_agent_app.ui.ssh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lemonhall.jediterm.android.ComposeTerminalView
import com.lemonhall.jediterm.android.MeasureTerminalSize

private const val TERMINAL_FONT_SIZE = 14f

private data class PendingConnect(
    val trustOnFirstUse: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SshScreen(
    vm: SshViewModel,
    onBack: () -> Unit,
) {
    val st by vm.uiState.collectAsStateWithLifecycle()
    val connector by vm.connector.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.start()
    }

    DisposableEffect(Unit) {
        onDispose { vm.disconnect() }
    }

    var showMenu by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf<PendingConnect?>(null) }
    var measuredSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    LaunchedEffect(st.autoConnectRequested, connector) {
        if (connector == null && st.autoConnectRequested && pendingConnect == null) {
            pendingConnect = PendingConnect(trustOnFirstUse = false)
        }
    }

    if (st.pendingTrust != null) {
        val p = st.pendingTrust!!
        AlertDialog(
            onDismissRequest = { },
            title = { Text("信任主机指纹？") },
            text = {
                Column {
                    Text("Host：${p.host}:${p.port}")
                    Spacer(Modifier.height(8.dp))
                    Text("Fingerprint：${p.fingerprint}")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "提示：首次连接需要 TOFU（Trust On First Use）。确认后将把指纹写入 .agents/workspace/ssh/known_hosts。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingConnect = PendingConnect(trustOnFirstUse = true)
                    },
                ) { Text("信任并连接") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        vm.disconnect()
                    },
                ) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "SSH", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
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
                                text = { Text("断开") },
                                onClick = {
                                    showMenu = false
                                    vm.disconnect()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val terminalAreaMod = Modifier.fillMaxSize().padding(padding)
        val contentMod =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)

        if (pendingConnect != null && measuredSize == null) {
            Box(modifier = terminalAreaMod) {
                MeasureTerminalSize(fontSize = TERMINAL_FONT_SIZE) { cols, rows ->
                    measuredSize = cols to rows
                }
            }
        }

        LaunchedEffect(pendingConnect, measuredSize) {
            val req = pendingConnect ?: return@LaunchedEffect
            val size = measuredSize ?: return@LaunchedEffect
            pendingConnect = null
            vm.consumeAutoConnectRequest()
            vm.connect(columns = size.first, rows = size.second, trustOnFirstUse = req.trustOnFirstUse)
        }

        when {
            connector != null -> {
                val conn = connector!!
                val size = measuredSize ?: (80 to 24)
                ComposeTerminalView(
                    ttyConnector = conn,
                    modifier = terminalAreaMod,
                    columns = size.first,
                    rows = size.second,
                    fontSize = TERMINAL_FONT_SIZE,
                    onResize = { cols, rows -> conn.resizePty(cols, rows) },
                )
            }
            st.isLoading -> {
                Box(modifier = contentMod, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在连接…")
                    }
                }
            }
            else -> {
                Column(modifier = contentMod, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!st.hasCredentials) {
                        Text(
                            text = "未检测到 SSH 凭据：请先在 ssh-cli 的 .env 配置 SSH_PASSWORD 或 SSH_PRIVATE_KEY_PATH。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!st.errorMessage.isNullOrBlank()) {
                        Text(
                            text = st.errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    OutlinedTextField(
                        value = st.host,
                        onValueChange = vm::updateHost,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Host") },
                        singleLine = true,
                        enabled = st.hasCredentials,
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = st.portText,
                            onValueChange = vm::updatePortText,
                            modifier = Modifier.weight(0.32f),
                            label = { Text("Port") },
                            singleLine = true,
                            enabled = st.hasCredentials,
                        )
                        Spacer(Modifier.width(10.dp))
                        OutlinedTextField(
                            value = st.user,
                            onValueChange = vm::updateUser,
                            modifier = Modifier.weight(0.68f),
                            label = { Text("User") },
                            singleLine = true,
                            enabled = st.hasCredentials,
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = {
                                pendingConnect = PendingConnect(trustOnFirstUse = false)
                            },
                            enabled = st.hasCredentials,
                        ) { Text("连接") }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "提示：host/user/port 会自动从 .agents/workspace/ssh/last.json 或 ssh-cli 的 .env 读取。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
