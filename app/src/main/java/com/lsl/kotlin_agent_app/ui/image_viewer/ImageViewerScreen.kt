package com.lsl.kotlin_agent_app.ui.image_viewer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    uri: Uri,
    displayName: String,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    var isReady by remember(uri) { mutableStateOf(false) }
    var errorMessage by remember(uri) { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = displayName, maxLines = 1) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                actions = {
                    TextButton(onClick = onOpenExternal) { Text("其他应用") }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SubsamplingScaleImageView(context).apply {
                        tag = uri.toString()
                        setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                        setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
                        setImage(ImageSource.uri(uri))
                        setOnImageEventListener(
                            object : SubsamplingScaleImageView.OnImageEventListener {
                                override fun onReady() {
                                    isReady = true
                                }

                                override fun onImageLoaded() {
                                    isReady = true
                                }

                                override fun onPreviewLoadError(e: Exception?) {
                                    errorMessage = e?.message ?: "预览失败"
                                }

                                override fun onImageLoadError(e: Exception?) {
                                    errorMessage = e?.message ?: "加载失败"
                                }

                                override fun onTileLoadError(e: Exception?) {
                                    errorMessage = e?.message ?: "加载失败"
                                }

                                override fun onPreviewReleased() = Unit
                            }
                        )
                    }
                },
                update = { view ->
                    val desired = uri.toString()
                    val current = view.tag as? String
                    if (current != desired) {
                        view.tag = desired
                        isReady = false
                        errorMessage = null
                        view.setImage(ImageSource.uri(uri))
                    }
                },
            )

            if (!isReady && errorMessage.isNullOrBlank()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.15f)),
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
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            modifier = Modifier.padding(top = 32.dp),
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )
                        Button(
                            modifier = Modifier.padding(top = 12.dp),
                            onClick = onOpenExternal,
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                        ) {
                            Text("其他应用打开")
                        }
                    }
                }
            }
        }
    }
}
