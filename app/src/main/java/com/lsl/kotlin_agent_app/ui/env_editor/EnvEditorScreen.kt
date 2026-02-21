package com.lsl.kotlin_agent_app.ui.env_editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvEditorScreen(
    vm: EnvEditorViewModel,
    title: String,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
) {
    val isLoading by vm.isLoading.collectAsState()
    val error by vm.errorMessage.collectAsState()
    val entries by vm.entries.collectAsState()
    val raw by vm.rawText.collectAsState()

    var rawMode by remember { mutableStateOf(false) }
    var rawDraft by remember(raw) { mutableStateOf(raw) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = { rawMode = !rawMode }) { Text(if (rawMode) "表单" else "RAW") }
                    TextButton(
                        onClick = {
                            if (rawMode) {
                                vm.saveRaw(rawDraft) { ok, msg -> onToast(msg); if (ok) rawMode = false }
                            } else {
                                vm.saveForm { _, msg -> onToast(msg) }
                            }
                        },
                    ) { Text("保存") }
                },
            )
        },
    ) { padding ->
        if (isLoading) {
            Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@Scaffold
        }

        if (!error.isNullOrBlank()) {
            Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("无法打开：${error.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Scaffold
        }

        if (rawMode) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            ) {
                TextField(
                    modifier = Modifier.fillMaxSize(),
                    value = rawDraft,
                    onValueChange = { rawDraft = it },
                    label = { Text("Raw .env") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("键值对", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { vm.addEntry() }) { Text("+ 添加") }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 18.dp),
            ) {
                itemsIndexed(entries) { index, e ->
                    EnvRow(
                        index = index,
                        entry = e,
                        onKeyChange = { vm.updateEntry(index, key = it) },
                        onValueChange = { vm.updateEntry(index, value = it) },
                        onRemove = { vm.removeEntry(index) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnvRow(
    index: Int,
    entry: EnvEntry,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.padding(end = 8.dp),
                text = "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
            )
            TextButton(onClick = onRemove) { Text("删除") }
        }
        TextField(
            modifier = Modifier.fillMaxWidth(),
            value = entry.key,
            onValueChange = onKeyChange,
            label = { Text("KEY") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        TextField(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            value = entry.value,
            onValueChange = onValueChange,
            label = { Text("VALUE") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

