package com.lsl.kotlin_agent_app.ui.pdf_viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.LruCache

class PdfViewerViewModel(
    private val appContext: Context,
    private val uri: Uri,
) : ViewModel() {

    private val renderMutex = Mutex()
    private val cache =
        object : LruCache<String, Bitmap>(12) {
            override fun sizeOf(key: String, value: Bitmap): Int = 1
        }

    private val pfd: ParcelFileDescriptor =
        appContext.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("无法打开文件描述符")

    private val renderer: PdfRenderer = PdfRenderer(pfd)

    val pageCount: Int = renderer.pageCount.coerceAtLeast(0)

    suspend fun renderPage(
        pageIndex: Int,
        targetWidthPx: Int,
    ): Bitmap {
        val idx = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val width = targetWidthPx.coerceAtLeast(1)
        val key = "${idx}@${width}"
        val cached = cache.get(key)
        if (cached != null && !cached.isRecycled) return cached

        return renderMutex.withLock {
            val cached2 = cache.get(key)
            if (cached2 != null && !cached2.isRecycled) return@withLock cached2

            val page = renderer.openPage(idx)
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
            renderer.close()
        } catch (_: Throwable) {
        }
        try {
            pfd.close()
        } catch (_: Throwable) {
        }
        super.onCleared()
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
