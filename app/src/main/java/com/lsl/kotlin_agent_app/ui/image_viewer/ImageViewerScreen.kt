package com.lsl.kotlin_agent_app.ui.image_viewer

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
    vm: ImageViewerViewModel,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val isLoading by vm.isLoading.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val items by vm.items.collectAsState()
    val startIndex by vm.startIndex.collectAsState()
    val currentIndex by vm.currentIndex.collectAsState()
    val title by vm.currentDisplayName.collectAsState()

    val pagerState =
        rememberPagerState(
            initialPage = 0,
            pageCount = { items.size.coerceAtLeast(1) },
        )

    LaunchedEffect(items, startIndex) {
        if (items.isNotEmpty()) {
            val idx = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            pagerState.scrollToPage(idx)
            vm.setCurrentIndex(idx)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        vm.setCurrentIndex(pagerState.currentPage)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    val suffix =
                        if (items.size > 1) {
                            " (${(currentIndex + 1).coerceAtLeast(1)}/${items.size})"
                        } else {
                            ""
                        }
                    Text(text = title + suffix, maxLines = 1)
                },
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
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    }
                }

                !errorMessage.isNullOrBlank() -> {
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

                items.isNotEmpty() -> {
                    HorizontalPager(
                        modifier = Modifier.fillMaxSize(),
                        state = pagerState,
                    ) { page ->
                        val item = items.getOrNull(page)
                        if (item != null) {
                            ImagePage(uriString = item.uri.toString())
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("无法预览", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                else -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("无可预览图片", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun ImagePage(
    uriString: String,
) {
    var isReady by remember(uriString) { mutableStateOf(false) }
    var pageError by remember(uriString) { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SubsamplingScaleImageView(context).apply {
                    tag = uriString
                    setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                    setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
                    setImage(ImageSource.uri(android.net.Uri.parse(uriString)))
                    setOnImageEventListener(
                        object : SubsamplingScaleImageView.OnImageEventListener {
                            override fun onReady() {
                                isReady = true
                            }

                            override fun onImageLoaded() {
                                isReady = true
                            }

                            override fun onPreviewLoadError(e: Exception?) {
                                pageError = e?.message ?: "预览失败"
                            }

                            override fun onImageLoadError(e: Exception?) {
                                pageError = e?.message ?: "加载失败"
                            }

                            override fun onTileLoadError(e: Exception?) {
                                pageError = e?.message ?: "加载失败"
                            }

                            override fun onPreviewReleased() = Unit
                        }
                    )
                }
            },
            update = { view ->
                val current = view.tag as? String
                if (current != uriString) {
                    view.tag = uriString
                    isReady = false
                    pageError = null
                    view.setImage(ImageSource.uri(android.net.Uri.parse(uriString)))
                }
            },
        )

        if (!isReady && pageError.isNullOrBlank()) {
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

        if (!pageError.isNullOrBlank()) {
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
                        text = pageError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
            }
        }
    }
}
