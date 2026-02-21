package com.lsl.kotlin_agent_app.ui.pdf_viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.onSizeChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    vm: PdfViewerViewModel,
    displayName: String,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val isLoading by vm.isLoading.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val pageCount by vm.pageCount.collectAsState()

    val pages =
        remember(pageCount) {
            (0 until pageCount).toList()
        }

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
        if (isLoading) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding),
                color = Color.Transparent,
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                }
            }
            return@Scaffold
        }

        if (!errorMessage.isNullOrBlank()) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding),
                color = Color.Transparent,
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("无法预览：${errorMessage.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Scaffold
        }

        if (pageCount <= 0) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding),
                color = Color.Transparent,
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("无法预览：空文档", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Scaffold
        }

        val cfg = LocalConfiguration.current
        val density = LocalDensity.current
        val targetWidthPx =
            with(density) {
                val wDp = cfg.screenWidthDp.coerceAtLeast(320)
                ((wDp.dp - 24.dp).toPx()).toInt().coerceAtLeast(1)
            }

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Black.copy(alpha = 0.02f)),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            items(pages) { pageIndex ->
                PdfPageItem(
                    vm = vm,
                    pageIndex = pageIndex,
                    targetWidthPx = targetWidthPx,
                )
            }
        }
    }
}

@Composable
private fun PdfPageItem(
    vm: PdfViewerViewModel,
    pageIndex: Int,
    targetWidthPx: Int,
) {
    var bmp by remember(pageIndex, targetWidthPx) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember(pageIndex, targetWidthPx) { mutableStateOf<String?>(null) }
    var scale by remember(pageIndex) { mutableStateOf(1f) }
    var offset by remember(pageIndex) { mutableStateOf(Offset.Zero) }
    var baseSize by remember(pageIndex) { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(pageIndex, targetWidthPx) {
        error = null
        bmp = null
        try {
            bmp = vm.renderPage(pageIndex = pageIndex, targetWidthPx = targetWidthPx)
        } catch (t: Throwable) {
            error = t.message ?: "render failed"
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        when {
            b != null -> {
                val minScale = 1f
                val maxScale = 5f
                Image(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { baseSize = it }
                            .clipToBounds()
                            .pointerInput(pageIndex) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val prevScale = scale
                                    val nextScale = (scale * zoom).coerceIn(minScale, maxScale)
                                    scale = nextScale

                                    if (nextScale <= minScale + 0.001f) {
                                        offset = Offset.Zero
                                        return@detectTransformGestures
                                    }

                                    val nextOffset = offset + pan * (nextScale / prevScale)
                                    val maxX =
                                        ((baseSize.width.toFloat() * (nextScale - 1f)) / 2f).coerceAtLeast(0f)
                                    val maxY =
                                        ((baseSize.height.toFloat() * (nextScale - 1f)) / 2f).coerceAtLeast(0f)
                                    offset =
                                        Offset(
                                            x = nextOffset.x.coerceIn(-maxX, maxX),
                                            y = nextOffset.y.coerceIn(-maxY, maxY),
                                        )
                                }
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    bitmap = b.asImageBitmap(),
                    contentDescription = "page_${pageIndex + 1}",
                    contentScale = ContentScale.FillWidth,
                )
            }
            !error.isNullOrBlank() -> {
                Text(
                    modifier = Modifier.padding(12.dp),
                    text = "第 ${pageIndex + 1} 页渲染失败：${error.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black,
                )
            }
            else -> {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        }
    }
}
