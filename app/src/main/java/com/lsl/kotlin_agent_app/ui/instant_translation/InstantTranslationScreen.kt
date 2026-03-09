package com.lsl.kotlin_agent_app.ui.instant_translation

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lsl.kotlin_agent_app.voiceinput.VoiceInputUiState
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstantTranslationScreen(
    vm: InstantTranslationViewModel,
    voiceInputStateFlow: StateFlow<VoiceInputUiState>,
    onBack: () -> Unit,
    onToggleListening: () -> Unit,
    onPickTargetLanguage: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val voiceState by voiceInputStateFlow.collectAsStateWithLifecycle()
    val errorMessage = state.errorMessage ?: voiceState.errorMessage

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("\u5373\u65f6\u7ffb\u8bd1") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("\u8fd4\u56de")
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onPickTargetLanguage,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("\u76ee\u6807\u8bed\u8a00\uff1a${state.targetLanguageLabel}")
                }
                TextButton(
                    onClick = vm::clearTurns,
                    enabled = state.turns.isNotEmpty() || state.listeningPreview.isNotBlank(),
                ) {
                    Text("\u6e05\u7a7a")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text =
                            when {
                                voiceState.isStarting -> "\u6b63\u5728\u542f\u52a8\u9ea6\u514b\u98ce\u2026"
                                voiceState.isRecording -> "\u8bc6\u522b\u4e2d"
                                else -> "\u5f85\u673a\u4e2d"
                            },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.listeningPreview.ifBlank { "\u70b9\u51fb\u4e0b\u65b9\u6309\u94ae\uff0c\u5f00\u59cb\u5b9e\u65f6\u8bed\u97f3\u8bc6\u522b\u5e76\u7ffb\u8bd1\u3002" },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Button(
                onClick = onToggleListening,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when {
                    voiceState.isStarting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("\u542f\u52a8\u4e2d\u2026")
                    }

                    voiceState.isRecording -> Text("\u505c\u6b62\u8bed\u97f3\u8bc6\u522b")
                    else -> Text("\u5f00\u59cb\u8bed\u97f3\u8bc6\u522b")
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (state.turns.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "\u8fd8\u6ca1\u6709\u7ffb\u8bd1\u8bb0\u5f55",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.turns, key = { it.id }) { turn ->
                        InstantTranslationTurnCard(turn = turn)
                    }
                }
            }
        }
    }
}

@Composable
private fun InstantTranslationTurnCard(turn: InstantTranslationTurn) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = turn.sourceText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (turn.isPending) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    Text("\u7ffb\u8bd1\u4e2d\u2026", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    text = turn.translatedText.ifBlank { "\uff08\u672a\u8fd4\u56de\u7ffb\u8bd1\u6587\u672c\uff09" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}