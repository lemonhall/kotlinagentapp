package com.lsl.kotlin_agent_app.ui.bilingual_player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun BilingualPlayerScreen(
    vm: BilingualPlayerViewModel,
    onBack: () -> Unit,
) {
    val st by vm.state.collectAsState()
    var settingsOpen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    var suppressAutoScroll by remember { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            suppressAutoScroll = true
        } else if (suppressAutoScroll) {
            delay(3000)
            suppressAutoScroll = false
        }
    }

    LaunchedEffect(st.currentSegmentIndex, st.autoScrollEnabled, suppressAutoScroll) {
        if (!st.autoScrollEnabled) return@LaunchedEffect
        if (suppressAutoScroll) return@LaunchedEffect
        val idx = st.currentSegmentIndex
        if (idx >= 0 && idx <= st.segments.lastIndex) {
            runCatching { listState.animateScrollToItem(idx) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            title = "双语播放",
            onBack = onBack,
            onOpenSettings = { settingsOpen = true },
        )

        if (!st.lastErrorMessage.isNullOrBlank()) {
            Text(
                text = "错误：${st.lastErrorCode ?: "Unknown"} · ${st.lastErrorMessage}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        DisplayModeTabs(
            mode = st.displayMode,
            onModeChange = vm::setDisplayMode,
        )

        Divider()

        SubtitleList(
            segments = st.segments,
            currentIndex = st.currentSegmentIndex,
            displayMode = st.displayMode,
            fontSize = st.subtitleFontSize,
            onSegmentClick = { vm.seekToSegment(it) },
            listState = listState,
            modifier = Modifier.weight(1f, fill = true),
        )

        Divider()

        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            AudioWaveform(isPlaying = st.isPlaying, modifier = Modifier.fillMaxWidth().height(30.dp))
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "chunk ${st.currentChunkDisplayIndex} / ${st.chunkCount.coerceAtLeast(0)}")
                Text(text = "${formatTimeMs(st.totalPositionMs)} / ${formatTimeMs(st.totalDurationMs)}")
            }

            Slider(
                value = st.totalPositionMs.coerceAtLeast(0L).toFloat(),
                onValueChange = { v -> vm.seekToTotalPositionMs(v.toLong()) },
                valueRange = 0f..(st.totalDurationMs.coerceAtLeast(1L).toFloat()),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = vm::seekToPrevSegment) { Text("⏮") }
                OutlinedButton(onClick = vm::cycleSpeed) { Text("${st.playbackSpeed}x") }
                Button(onClick = vm::togglePlayPause) { Text(if (st.isPlaying) "暂停" else "播放") }
                OutlinedButton(onClick = vm::seekToNextSegment) { Text("⏭") }
            }
        }
    }

    if (settingsOpen) {
        ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
            PlayerSettingsSheet(
                fontSize = st.subtitleFontSize,
                autoScrollEnabled = st.autoScrollEnabled,
                onFontSizeChange = vm::setSubtitleFontSize,
                onAutoScrollChange = vm::setAutoScrollEnabled,
                onClose = { settingsOpen = false },
            )
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = "◀ 返回", modifier = Modifier.clickable { onBack() })
        Text(text = title, fontWeight = FontWeight.SemiBold)
        Text(text = "⚙ 设置", modifier = Modifier.clickable { onOpenSettings() })
    }
}

@Composable
private fun DisplayModeTabs(
    mode: BilingualPlayerViewModel.DisplayMode,
    onModeChange: (BilingualPlayerViewModel.DisplayMode) -> Unit,
) {
    val idx =
        when (mode) {
            BilingualPlayerViewModel.DisplayMode.Source -> 0
            BilingualPlayerViewModel.DisplayMode.Translation -> 1
            BilingualPlayerViewModel.DisplayMode.Bilingual -> 2
        }
    TabRow(selectedTabIndex = idx) {
        Tab(selected = idx == 0, onClick = { onModeChange(BilingualPlayerViewModel.DisplayMode.Source) }, text = { Text("原文") })
        Tab(selected = idx == 1, onClick = { onModeChange(BilingualPlayerViewModel.DisplayMode.Translation) }, text = { Text("译文") })
        Tab(selected = idx == 2, onClick = { onModeChange(BilingualPlayerViewModel.DisplayMode.Bilingual) }, text = { Text("双语") })
    }
}

@Composable
private fun SubtitleList(
    segments: List<com.lsl.kotlin_agent_app.radio_bilingual.player.SubtitleSyncEngine.SubtitleSegment>,
    currentIndex: Int,
    displayMode: BilingualPlayerViewModel.DisplayMode,
    fontSize: BilingualPlayerViewModel.SubtitleFontSize,
    onSegmentClick: (Int) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    val fs =
        when (fontSize) {
            BilingualPlayerViewModel.SubtitleFontSize.Small -> 14.sp
            BilingualPlayerViewModel.SubtitleFontSize.Medium -> 16.sp
            BilingualPlayerViewModel.SubtitleFontSize.Large -> 18.sp
        }

    if (segments.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "暂无字幕", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        items(count = segments.size, key = { i -> "${segments[i].id}-${segments[i].totalStartMs}" }) { i ->
            val s = segments[i]
            val isCur = (i == currentIndex)
            val bg = if (isCur) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .clickable { onSegmentClick(i) }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                val dot = if (isCur) "●" else " "
                Text(
                    text = "$dot ${formatTimeMs(s.totalStartMs)}",
                    modifier = Modifier.padding(end = 10.dp),
                    color = if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f)) {
                    when (displayMode) {
                        BilingualPlayerViewModel.DisplayMode.Source -> {
                            Text(text = s.sourceText, fontSize = fs)
                        }
                        BilingualPlayerViewModel.DisplayMode.Translation -> {
                            Text(text = s.translatedText ?: s.sourceText, fontSize = fs)
                        }
                        BilingualPlayerViewModel.DisplayMode.Bilingual -> {
                            Text(text = s.sourceText, fontSize = fs)
                            val tx = s.translatedText?.trim().orEmpty()
                            if (tx.isNotBlank()) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = tx,
                                    fontSize = (fs.value - 2).coerceAtLeast(12f).sp,
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

@Composable
private fun PlayerSettingsSheet(
    fontSize: BilingualPlayerViewModel.SubtitleFontSize,
    autoScrollEnabled: Boolean,
    onFontSizeChange: (BilingualPlayerViewModel.SubtitleFontSize) -> Unit,
    onAutoScrollChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = "播放设置", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))

        Text(text = "字幕字号", modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onFontSizeChange(BilingualPlayerViewModel.SubtitleFontSize.Small) }) {
                Text(if (fontSize == BilingualPlayerViewModel.SubtitleFontSize.Small) "小 ✓" else "小")
            }
            OutlinedButton(onClick = { onFontSizeChange(BilingualPlayerViewModel.SubtitleFontSize.Medium) }) {
                Text(if (fontSize == BilingualPlayerViewModel.SubtitleFontSize.Medium) "中 ✓" else "中")
            }
            OutlinedButton(onClick = { onFontSizeChange(BilingualPlayerViewModel.SubtitleFontSize.Large) }) {
                Text(if (fontSize == BilingualPlayerViewModel.SubtitleFontSize.Large) "大 ✓" else "大")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "自动滚动")
            Switch(checked = autoScrollEnabled, onCheckedChange = onAutoScrollChange)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

private fun formatTimeMs(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val mm = (s / 60).coerceAtLeast(0)
    val ss = (s % 60).coerceAtLeast(0)
    return "%02d:%02d".format(mm, ss)
}
