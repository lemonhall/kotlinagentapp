package com.lsl.kotlin_agent_app.ui.video_player

import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    vm: VideoPlayerViewModel,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val title by vm.displayName.collectAsState()
    val isBuffering by vm.isBuffering.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var controlsVisible by remember { mutableStateOf(true) }

    @Composable
    fun PlayerContent(modifier: Modifier) {
        Box(modifier = modifier) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PlayerView(context).apply {
                        player = vm.player
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        keepScreenOn = true
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { visibility ->
                                controlsVisible = (visibility == View.VISIBLE)
                            }
                        )
                    }
                },
                update = { view ->
                    if (view.player != vm.player) {
                        view.player = vm.player
                    }
                },
            )

            if (isLandscape && controlsVisible) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                    color = Color.Black.copy(alpha = 0.45f),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onBack,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        ) {
                            Text("返回")
                        }
                        Text(
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            text = title,
                            maxLines = 1,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            onClick = onOpenExternal,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        ) {
                            Text("其他应用")
                        }
                    }
                }
            }

            if (isBuffering) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.55f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )
                        Button(
                            modifier = Modifier.padding(top = 12.dp),
                            onClick = { vm.retry() },
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                        ) {
                            Text("重试")
                        }
                    }
                }
            }
        }
    }

    if (isLandscape) {
        PlayerContent(modifier = Modifier.fillMaxSize())
    } else {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(text = title, maxLines = 1) },
                    navigationIcon = {
                        TextButton(onClick = onBack) { Text("返回") }
                    },
                    actions = {
                        TextButton(onClick = onOpenExternal) { Text("其他应用") }
                    },
                )
            },
        ) { padding ->
            PlayerContent(modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}
