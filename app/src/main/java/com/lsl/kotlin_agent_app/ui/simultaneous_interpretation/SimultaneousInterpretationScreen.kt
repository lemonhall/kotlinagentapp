package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SimultaneousInterpretationScreen(
    archiveRootPath: String,
    onBack: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onBack) {
            Text("返回")
        }
        Text(
            text = "同声传译",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "v47 第一阶段：Files 入口与空壳 Activity 已接通。",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "归档根目录：$archiveRootPath",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "下一步会在这里接上麦克风、实时译文、实时译音和会话归档。",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}