package com.lsl.kotlin_agent_app.asr

import java.io.File
import java.io.IOException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

internal class AliyunQwenAsrClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val modelName: String,
    private val uploader: DashScopeFileUploader,
    private val debugDumpDir: File? = null,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val pollDelayMs: Long = 3000L,
    private val timeoutMs: Long = 30L * 60L * 1000L,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) : CloudAsrClient {
    override suspend fun transcribe(
        audioFile: File,
        mimeType: String,
        language: String?,
    ): AsrResult {
        val base = baseUrl.trimEnd('/')
        val model = modelName.trim()
        if (model.isBlank()) throw IllegalArgumentException("missing modelName")
        if (apiKey.isBlank()) throw IllegalArgumentException("missing apiKey")
        if (!audioFile.exists() || !audioFile.isFile) throw IllegalArgumentException("audio file not found: ${audioFile.path}")

        val fileUrl =
            try {
                uploader.uploadFileAndGetOssUrl(modelName = model, file = audioFile, debugDumpDir = debugDumpDir)
            } catch (t: AsrException) {
                throw t
            } catch (t: Throwable) {
                throw AsrUploadError("upload failed: ${t.message ?: "unknown"}", t)
            }
        debugDumpDir?.let { dir ->
            runCatching { dir.mkdirs() }
            runCatching { File(dir, "oss_url.txt").writeText(fileUrl.trim() + "\n", Charsets.UTF_8) }
        }

        val normalizedLanguage =
            language
                ?.trim()
                ?.ifBlank { null }
                ?.takeUnless { it.equals("auto", ignoreCase = true) || it.equals("null", ignoreCase = true) }
        val taskId = submitTask(base = base, model = model, fileUrl = fileUrl, language = normalizedLanguage)
        return pollResult(base = base, taskId = taskId)
    }

    private suspend fun submitTask(
        base: String,
        model: String,
        fileUrl: String,
        language: String?,
    ): String {
        val url = "$base/services/audio/asr/transcription"
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val bodyObj =
            buildJsonObject {
                put("model", JsonPrimitive(model))
                put(
                    "input",
                    buildJsonObject {
                        put("file_url", JsonPrimitive(fileUrl))
                    },
                )
                put(
                    "parameters",
                    buildJsonObject {
                        put("channel_id", buildJsonArray { add(JsonPrimitive(0)) })
                        put("enable_itn", JsonPrimitive(false))
                        put("enable_words", JsonPrimitive(true))
                        if (language != null) put("language", JsonPrimitive(language))
                    },
                )
            }
        val raw = json.encodeToString(JsonObject.serializer(), bodyObj)
        debugDumpDir?.let { dir ->
            runCatching { dir.mkdirs() }
            runCatching { File(dir, "submit_request.json").writeText(raw.trim() + "\n", Charsets.UTF_8) }
        }
        val reqBody = raw.toRequestBody("application/json".toMediaType())

        val responseBody: String =
            try {
                withContext(Dispatchers.IO) {
                    val req =
                        Request.Builder()
                            .url(url)
                            .post(reqBody)
                            .addHeader("Authorization", "Bearer $apiKey")
                            .addHeader("Content-Type", "application/json")
                            .addHeader("X-DashScope-Async", "enable")
                            .addHeader("X-DashScope-OssResourceResolve", "enable")
                            .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw AsrRemoteError(resp.code.toString(), "submit failed: http ${resp.code}")
                        resp.body?.string().orEmpty()
                    }
                }
            } catch (t: IOException) {
                throw AsrNetworkError("dashscope submit network error", t)
            }
        debugDumpDir?.let { dir ->
            runCatching { dir.mkdirs() }
            runCatching { File(dir, "submit_response.json").writeText(responseBody.trim() + "\n", Charsets.UTF_8) }
        }

        val obj =
            try {
                json.parseToJsonElement(responseBody).jsonObject
            } catch (t: Throwable) {
                throw AsrParseError("invalid submit response", t)
            }
        val out = obj["output"]?.jsonObject ?: throw AsrParseError("missing output in submit response")
        val taskId = out["task_id"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (taskId.isBlank()) throw AsrParseError("missing output.task_id in submit response")
        return taskId
    }

    private suspend fun pollResult(
        base: String,
        taskId: String,
    ): AsrResult {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val url = "$base/tasks/${taskId.trim()}"
        val start = System.currentTimeMillis()
        var lastBodyText: String? = null

        suspend fun downloadTranscriptionFile(transcriptionUrl: String): JsonObject {
            val safeUrl = transcriptionUrl.trim()
            if (safeUrl.isBlank()) throw AsrParseError("missing transcription_url")
            debugDumpDir?.let { dir ->
                runCatching { dir.mkdirs() }
                runCatching { File(dir, "transcription_url.txt").writeText(safeUrl + "\n", Charsets.UTF_8) }
                runCatching {
                    val host = runCatching { URL(safeUrl).host }.getOrNull()?.trim().orEmpty()
                    if (host.isNotBlank()) File(dir, "transcription_host.txt").writeText(host + "\n", Charsets.UTF_8)
                }
            }

            val bodyText: String =
                try {
                    withContext(Dispatchers.IO) {
                        val req = Request.Builder().url(safeUrl).get().build()
                        httpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw AsrRemoteError(resp.code.toString(), "download transcription file failed: http ${resp.code}")
                            resp.body?.string().orEmpty()
                        }
                    }
                } catch (t: IOException) {
                    throw AsrNetworkError("download transcription file network error", t)
                }

            debugDumpDir?.let { dir ->
                runCatching { dir.mkdirs() }
                runCatching { File(dir, "transcription_file.json").writeText(bodyText.trim() + "\n", Charsets.UTF_8) }
            }

            val el: JsonElement =
                try {
                    json.parseToJsonElement(bodyText)
                } catch (t: Throwable) {
                    throw AsrParseError("invalid transcription file (expected json)", t)
                }
            val obj: JsonObject =
                try {
                    el.jsonObject
                } catch (t: Throwable) {
                    throw AsrParseError("invalid transcription file (expected object)", t)
                }
            return obj
        }

        while (true) {
            val bodyText: String =
                try {
                    withContext(Dispatchers.IO) {
                        val req =
                            Request.Builder()
                                .url(url)
                                .get()
                                .addHeader("Authorization", "Bearer $apiKey")
                                .addHeader("X-DashScope-Async", "enable")
                                .addHeader("Content-Type", "application/json")
                                .build()
                        httpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw AsrRemoteError(resp.code.toString(), "poll failed: http ${resp.code}")
                            resp.body?.string().orEmpty()
                        }
                    }
                } catch (t: IOException) {
                    throw AsrNetworkError("dashscope poll network error", t)
                }
            lastBodyText = bodyText

            val obj: JsonObject =
                try {
                    json.parseToJsonElement(bodyText).jsonObject
                } catch (t: Throwable) {
                    throw AsrParseError("invalid poll response", t)
                }

            val out = obj["output"]?.jsonObject ?: throw AsrParseError("missing output in poll response")
            val status = out["task_status"]?.jsonPrimitive?.content?.trim().orEmpty()
            when (status) {
                "SUCCEEDED" -> {
                    val result = out["result"]?.jsonObject ?: throw AsrParseError("missing output.result")
                    debugDumpDir?.let { dir ->
                        runCatching { dir.mkdirs() }
                        runCatching { File(dir, "poll_succeeded.json").writeText(bodyText.trim() + "\n", Charsets.UTF_8) }
                    }

                    val transcriptionUrl = result["transcription_url"]?.jsonPrimitive?.content?.trim()?.ifBlank { null }
                    if (transcriptionUrl != null) {
                        val transcriptionObj = downloadTranscriptionFile(transcriptionUrl)
                        return parseAsrResult(transcriptionObj)
                    }

                    return parseAsrResult(result)
                }
                "FAILED" -> {
                    val code = out["code"]?.jsonPrimitive?.content?.trim()?.ifBlank { null } ?: "UNKNOWN"
                    val message = out["message"]?.jsonPrimitive?.content?.trim().orEmpty()
                    debugDumpDir?.let { dir ->
                        runCatching { dir.mkdirs() }
                        runCatching { File(dir, "poll_failed.json").writeText(bodyText.trim() + "\n", Charsets.UTF_8) }
                    }
                    throw AsrRemoteError(code, if (message.isBlank()) "dashscope task failed" else message)
                }
                "PENDING", "RUNNING" -> {
                    val elapsed = System.currentTimeMillis() - start
                    if (elapsed > timeoutMs) {
                        debugDumpDir?.let { dir ->
                            runCatching { dir.mkdirs() }
                            runCatching {
                                val last = lastBodyText?.trim()?.ifBlank { null }
                                if (last != null) File(dir, "poll_timeout_last.json").writeText(last + "\n", Charsets.UTF_8)
                            }
                        }
                        throw AsrTaskTimeout("dashscope task timeout: ${taskId.trim()}")
                    }
                    delayFn(pollDelayMs.coerceAtLeast(0L))
                }
                else -> throw AsrRemoteError("UNKNOWN", "unknown task status: $status")
            }
        }
    }

    private fun parseAsrResult(result: JsonObject): AsrResult {
        fun arrayOrNull(o: JsonObject?, key: String): JsonArray? = runCatching { o?.get(key)?.jsonArray }.getOrNull()
        fun objOrNull(o: JsonObject?, key: String): JsonObject? = runCatching { o?.get(key)?.jsonObject }.getOrNull()
        fun primStringOrNull(o: JsonObject?, key: String): String? =
            runCatching { o?.get(key)?.jsonPrimitive?.content }.getOrNull()?.trim()?.ifBlank { null }

        val transcriptChannels: JsonArray =
            arrayOrNull(result, "transcripts")
                ?: arrayOrNull(result, "transcriptions")
                ?: arrayOrNull(result, "channels")
                ?: JsonArray(emptyList())

        val firstChannel = transcriptChannels.firstOrNull()?.jsonObject
        val sentences: JsonArray =
            arrayOrNull(firstChannel, "sentences")
                ?: arrayOrNull(firstChannel, "sentence_list")
                ?: arrayOrNull(firstChannel, "segments")
                ?: arrayOrNull(result, "sentences")
                ?: arrayOrNull(result, "sentence_list")
                ?: arrayOrNull(result, "segments")
                ?: run {
                    // Some API variants return: result = { "transcript": { ... "sentences": [...] } }
                    val nested = objOrNull(result, "transcript") ?: objOrNull(result, "transcription") ?: objOrNull(result, "output")
                    arrayOrNull(nested, "sentences") ?: arrayOrNull(nested, "segments") ?: JsonArray(emptyList())
                }

        val segments =
            sentences.mapIndexedNotNull { index, sentence ->
                val s = sentence as? JsonObject ?: return@mapIndexedNotNull null
                val id = (s["sentence_id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: index
                val startMs =
                    (s["begin_time"] as? JsonPrimitive)?.content?.toLongOrNull()
                        ?: (s["start_time"] as? JsonPrimitive)?.content?.toLongOrNull()
                        ?: 0L
                val endMs =
                    (s["end_time"] as? JsonPrimitive)?.content?.toLongOrNull()
                        ?: (s["finish_time"] as? JsonPrimitive)?.content?.toLongOrNull()
                        ?: (s["end_time_ms"] as? JsonPrimitive)?.content?.toLongOrNull()
                        ?: 0L
                val text =
                    (s["text"] as? JsonPrimitive)?.content
                        ?: (s["transcript"] as? JsonPrimitive)?.content
                        ?: (s["sentence"] as? JsonPrimitive)?.content
                        ?: (s["content"] as? JsonPrimitive)?.content
                        ?: return@mapIndexedNotNull null
                val emotion = (s["emotion"] as? JsonPrimitive)?.content?.trim()?.ifBlank { null }
                AsrSegment(
                    id = id,
                    startMs = startMs,
                    endMs = endMs,
                    text = text,
                    emotion = emotion,
                )
            }

        val detectedLanguage =
            primStringOrNull(sentences.firstOrNull()?.jsonObject, "language")
                ?: primStringOrNull(firstChannel, "language")
                ?: primStringOrNull(result, "language")

        if (segments.isNotEmpty()) {
            return AsrResult(segments = segments, detectedLanguage = detectedLanguage)
        }

        // Fallback: if API only returns a single text field, produce 1 segment.
        val rawText =
            primStringOrNull(result, "text")
                ?: primStringOrNull(firstChannel, "text")
                ?: primStringOrNull(result, "transcript_text")
                ?: primStringOrNull(result, "transcription_text")
        if (rawText != null) {
            return AsrResult(
                segments = listOf(AsrSegment(id = 0, startMs = 0L, endMs = 0L, text = rawText, emotion = null)),
                detectedLanguage = detectedLanguage,
            )
        }

        return AsrResult(segments = segments, detectedLanguage = detectedLanguage)
    }
}
