package com.lsl.kotlin_agent_app.radio_transcript

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lsl.kotlin_agent_app.asr.AsrException
import com.lsl.kotlin_agent_app.asr.AsrNetworkError
import java.io.File

internal class TranscriptWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID)?.trim().orEmpty()
        val taskId = inputData.getString(KEY_TASK_ID)?.trim().orEmpty()
        if (sessionId.isBlank() || taskId.isBlank()) return Result.failure()

        val mgr = TranscriptTaskManager(appContext = applicationContext)
        val asr =
            try {
                val debugDir =
                    File(
                        applicationContext.filesDir,
                        ".agents/workspace/radio_recordings/$sessionId/transcripts/$taskId/_debug_asr",
                    )
                mgr.buildDefaultAsrClient(debugDumpDir = debugDir)
            } catch (_: TranscriptCliException) {
                return Result.failure()
            }

        return try {
            mgr.runTask(
                sessionId = sessionId,
                taskId = taskId,
                cloudAsrClient = asr,
                shouldStop = { isStopped },
            )
            Result.success()
        } catch (t: AsrException) {
            if (t is AsrNetworkError) Result.retry() else Result.failure()
        } catch (_: TranscriptCliException) {
            Result.failure()
        } catch (_: Throwable) {
            Result.failure()
        }
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_TASK_ID = "task_id"
    }
}
