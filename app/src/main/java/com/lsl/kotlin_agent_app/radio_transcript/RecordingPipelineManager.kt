package com.lsl.kotlin_agent_app.radio_transcript

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

internal class RecordingPipelineManager(
    appContext: Context,
    private val workManager: WorkManager? = runCatching { WorkManager.getInstance(appContext.applicationContext) }.getOrNull(),
) {
    private val ctx = appContext.applicationContext

    fun enqueue(
        sessionId: String,
        targetLanguage: String? = null,
        replace: Boolean = false,
    ) {
        val wm = workManager ?: return
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        val tgt = targetLanguage?.trim()?.ifBlank { null }
        try {
            val req =
                OneTimeWorkRequestBuilder<RecordingPipelineWorker>()
                    .setInputData(
                        workDataOf(
                            RecordingPipelineWorker.KEY_SESSION_ID to sid,
                            RecordingPipelineWorker.KEY_TARGET_LANG to tgt,
                        ),
                    )
                    .build()
            wm.enqueueUniqueWork(
                uniqueWorkName(sid),
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                req,
            )
        } catch (_: Throwable) {
        }
    }

    private fun uniqueWorkName(sessionId: String): String = "radio_pipeline_${sessionId.trim()}"
}

