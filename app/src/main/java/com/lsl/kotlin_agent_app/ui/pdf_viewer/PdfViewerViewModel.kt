package com.lsl.kotlin_agent_app.ui.pdf_viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.LruCache

class PdfViewerViewModel(
    private val appContext: Context,
    private val uri: Uri,
) : ViewModel() {

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount

    private val renderMutex = Mutex()
    private val cache =
        object : LruCache<String, Bitmap>(12) {
            override fun sizeOf(key: String, value: Bitmap): Int = 1
        }

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var fallbackFile: File? = null

    init {
        loadDoc()
    }

    private fun loadDoc() {
        _isLoading.value = true
        _errorMessage.value = null
        _pageCount.value = 0
        viewModelScope.launch {
            val loaded =
                withContext(Dispatchers.IO) {
                    openRendererWithFallback(uri)
                }
            if (loaded == null) {
                _isLoading.value = false
                return@launch
            }
            renderer = loaded.first
            pfd = loaded.second
            _pageCount.value = renderer?.pageCount?.coerceAtLeast(0) ?: 0
            _isLoading.value = false
        }
    }

    suspend fun renderPage(
        pageIndex: Int,
        targetWidthPx: Int,
    ): Bitmap {
        val r = renderer ?: throw IllegalStateException(_errorMessage.value ?: "文档未加载")
        val pc = _pageCount.value.coerceAtLeast(0)
        if (pc <= 0) throw IllegalStateException("空文档")
        val idx = pageIndex.coerceIn(0, (pc - 1).coerceAtLeast(0))
        val width = targetWidthPx.coerceAtLeast(1)
        val key = "${idx}@${width}"
        val cached = cache.get(key)
        if (cached != null && !cached.isRecycled) return cached

        return renderMutex.withLock {
            val cached2 = cache.get(key)
            if (cached2 != null && !cached2.isRecycled) return@withLock cached2

            val page = r.openPage(idx)
            try {
                val pageWidth = page.width.coerceAtLeast(1)
                val pageHeight = page.height.coerceAtLeast(1)
                val scale = width.toFloat() / pageWidth.toFloat()
                val height = (pageHeight.toFloat() * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bmp)
                bmp
            } finally {
                page.close()
            }
        }
    }

    override fun onCleared() {
        try {
            renderer?.close()
        } catch (_: Throwable) {
        }
        try {
            pfd?.close()
        } catch (_: Throwable) {
        }
        try {
            fallbackFile?.delete()
        } catch (_: Throwable) {
        }
        super.onCleared()
    }

    private fun openRendererWithFallback(uri: Uri): Pair<PdfRenderer, ParcelFileDescriptor>? {
        runCatching {
            val pfd0 =
                appContext.contentResolver.openFileDescriptor(uri, "r")
                    ?: error("无法打开文件描述符")
            try {
                val renderer0 = PdfRenderer(pfd0)
                return renderer0 to pfd0
            } catch (t: Throwable) {
                runCatching { pfd0.close() }
                throw t
            }
        }

        return runCatching {
            val tmp = copyToCachePdf(uri)
            fallbackFile = tmp
            val pfd2 = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer2 = PdfRenderer(pfd2)
            renderer2 to pfd2
        }.getOrElse { t ->
            _errorMessage.value = t.message ?: "无法预览 PDF"
            null
        }
    }

    private fun copyToCachePdf(uri: Uri): File {
        val dir = File(appContext.cacheDir, "pdf_viewer")
        if (!dir.exists()) dir.mkdirs()
        val name = "tmp_${UUID.randomUUID().toString().replace("-", "")}.pdf"
        val outFile = File(dir, name)

        val input =
            appContext.contentResolver.openInputStream(uri)
                ?: error("无法读取 PDF 内容")
        input.use { ins ->
            FileOutputStream(outFile).use { outs ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    outs.write(buf, 0, n)
                }
                outs.flush()
            }
        }
        return outFile
    }

    class Factory(
        private val appContext: Context,
        private val uri: Uri,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PdfViewerViewModel::class.java)) {
                return PdfViewerViewModel(appContext.applicationContext, uri) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
