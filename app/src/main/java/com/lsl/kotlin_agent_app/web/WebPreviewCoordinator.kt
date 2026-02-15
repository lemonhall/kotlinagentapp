package com.lsl.kotlin_agent_app.web

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WebPreviewFrame(
    val bitmap: Bitmap?,
    val url: String?,
    val timestampMs: Long = System.currentTimeMillis(),
)

class WebPreviewCoordinator(
    private val controller: WebViewController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _frame = MutableStateFlow(WebPreviewFrame(bitmap = null, url = null))
    val frame: StateFlow<WebPreviewFrame> = _frame.asStateFlow()

    private var job: Job? = null

    fun start(
        intervalMs: Long = 1500,
        targetWidth: Int = 480,
        targetHeight: Int = 270,
    ) {
        if (job?.isActive == true) return
        job =
            scope.launch {
                while (isActive) {
                    val state = runCatching { controller.getState() }.getOrNull()
                    val bmp =
                        runCatching { controller.capturePreviewBitmap(targetWidth, targetHeight) }
                            .getOrNull()
                    _frame.value =
                        WebPreviewFrame(
                            bitmap = bmp,
                            url = state?.url,
                        )
                    delay(intervalMs)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

