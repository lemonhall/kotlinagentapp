package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun SimultaneousInterpretationScreen(
    state: SimultaneousInterpretationUiState,
    onBack: () -> Unit,
    onToggleSession: () -> Unit,
    onPickTargetLanguage: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onBack) {
                Text("返回")
            }
            Button(onClick = onPickTargetLanguage) {
                Text("目标语言：${state.targetLanguageLabel}")
            }
            Button(onClick = onToggleSession) {
                Text(if (state.isRunning || state.isConnecting) "停止同传" else "开始同传")
            }
        }

        Text(
            text = "同声传译",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "状态：${state.statusText}",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.sessionPath?.let {
            Text(
                text = "回溯目录：$it",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!state.isHeadsetConnected) {
            Text(
                text = "建议佩戴耳机后再开启同传，否则容易听到自己的译音回灌。",
                color = Color(0xFFB3261E),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = Color(0xFFB3261E),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            text = "源文",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = state.sourcePreview.ifBlank { "等待源语音…" },
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(
            text = "译文",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = state.translatedPreview.ifBlank { "等待译文…" },
            style = MaterialTheme.typography.bodyLarge,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = state.segments.reversed(),
                key = { it.id },
            ) { segment ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = segment.sourceText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = segment.translatedText,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}