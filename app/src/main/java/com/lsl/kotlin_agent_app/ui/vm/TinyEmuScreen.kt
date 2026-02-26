package com.lsl.kotlin_agent_app.ui.vm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lemonhall.jediterm.android.ComposeTerminalView
import com.lemonhall.jediterm.android.MeasureTerminalSize

private const val TERMINAL_FONT_SIZE = 14f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TinyEmuScreen(
    vm: TinyEmuViewModel,
    onBack: () -> Unit,
) {
    val st by vm.uiState.collectAsStateWithLifecycle()
    val connector by vm.connector.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.start() }

    var showMenu by remember { mutableStateOf(false) }
    var pendingBoot by remember { mutableStateOf(false) }
    var measuredSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VM Terminal") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    Box {
                        TextButton(onClick = { showMenu = true }) { Text("菜单") }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("关机") },
                                onClick = {
                                    showMenu = false
                                    vm.shutdown()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val terminalAreaMod = Modifier.fillMaxSize().padding(padding)
        val contentMod = Modifier.fillMaxSize().padding(padding).padding(12.dp)

        // Measure terminal size before booting
        if (pendingBoot && measuredSize == null) {
            Box(modifier = terminalAreaMod) {
                MeasureTerminalSize(fontSize = TERMINAL_FONT_SIZE) { cols, rows ->
                    measuredSize = cols to rows
                }
            }
        }

        LaunchedEffect(pendingBoot, measuredSize) {
            if (!pendingBoot) return@LaunchedEffect
            val size = measuredSize ?: return@LaunchedEffect
            pendingBoot = false
            vm.boot(columns = size.first, rows = size.second)
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
                )
            }
            st.isLoading -> {
                Box(modifier = contentMod, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在启动 VM…")
                    }
                }
            }
            else -> {
                Column(modifier = contentMod, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!st.errorMessage.isNullOrBlank()) {
                        Text(
                            text = st.errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (st.profiles.isNotEmpty()) {
                        Text(
                            text = "选择 ROM 镜像",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(st.profiles, key = { it.id }) { profile ->
                                val selected = profile.id == st.selectedProfileId
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { pendingBoot = true; vm.selectProfile(profile.id) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selected)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = profile.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = profile.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
