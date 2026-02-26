package com.lsl.kotlin_agent_app.radio_transcript

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.radio_recordings.RadioRecordingsStore
import com.lsl.kotlin_agent_app.translation.OpenAgenticTranslationClient
import com.lsl.kotlin_agent_app.config.SharedPreferencesLlmConfigRepository
import com.lsl.kotlin_agent_app.recordings.RecordingSessionResolver
import java.io.File

internal class RecordingPipelineWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID)?.trim().orEmpty()
        val targetLang = inputData.getString(KEY_TARGET_LANG)?.trim()?.ifBlank { null }
        if (sessionId.isBlank()) return Result.failure()

        val appContext = applicationContext
        val ws = AgentsWorkspace(appContext)
        val ref = RecordingSessionResolver.resolve(ws, sessionId) ?: return Result.failure()
        val store = RadioRecordingsStore(ws, rootDir = ref.rootDir)

        val txMgr = TranscriptTaskManager(appContext = appContext, ws = ws)
        val asr =
            try {
                val debugDir = File(appContext.filesDir, "${ref.sessionDir}/_debug_asr_pipeline")
                txMgr.buildDefaultAsrClient(debugDumpDir = debugDir, sessionRef = ref)
            } catch (_: TranscriptCliException) {
                return Result.failure()
            }

        fun buildTranslationClient(
            @Suppress("UNUSED_PARAMETER")
            targetLanguage: String,
        ): com.lsl.kotlin_agent_app.translation.TranslationClient {
            val prefs = appContext.getSharedPreferences("kotlin-agent-app", Context.MODE_PRIVATE)
            val llm = SharedPreferencesLlmConfigRepository(prefs).get()
            val active = llm.activeProvider
            return OpenAgenticTranslationClient(
                baseUrl = active?.baseUrl.orEmpty(),
                apiKey = active?.apiKey.orEmpty(),
                model = active?.selectedModel.orEmpty(),
            )
        }

        val pipeline =
            RecordingPipeline(
                ws = ws,
                store = store,
                asrClient = asr,
                translationClientFactory = ::buildTranslationClient,
            )

        return try {
            pipeline.run(sessionId = sessionId, targetLanguageOverride = targetLang, shouldStop = { isStopped })
            Result.success()
        } catch (t: RecordingPipelineException) {
            Result.failure()
        } catch (_: Throwable) {
            Result.failure()
        }
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_TARGET_LANG = "target_lang"
    }
}
