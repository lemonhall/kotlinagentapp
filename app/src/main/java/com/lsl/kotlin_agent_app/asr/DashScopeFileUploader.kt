package com.lsl.kotlin_agent_app.asr

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType

internal class DashScopeFileUploader(
    private val baseUrl: String,
    private val apiKey: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun uploadFileAndGetOssUrl(
        modelName: String,
        file: File,
        debugDumpDir: File? = null,
    ): String {
        val model = modelName.trim()
        if (model.isBlank()) throw IllegalArgumentException("missing modelName")
        if (!file.exists() || !file.isFile) throw IllegalArgumentException("audio file not found: ${file.path}")

        val base = baseUrl.trimEnd('/')
        val policyUrl = "$base/uploads?action=getPolicy&model=$model"
        debugDumpDir?.let { dir ->
            runCatching { dir.mkdirs() }
            runCatching { File(dir, "upload_policy_url.txt").writeText(policyUrl.trim() + "\n", Charsets.UTF_8) }
        }

        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        val policyBody: String =
            try {
                withContext(Dispatchers.IO) {
                    val req =
                        Request.Builder()
                            .url(policyUrl)
                            .get()
                            .addHeader("Authorization", "Bearer $apiKey")
                            .addHeader("Content-Type", "application/json")
                            .build()
                    httpClient.newCall(req).execute().use { resp ->
                        require2xx(resp, "upload policy")
                        resp.body?.string().orEmpty()
                    }
                }
            } catch (t: IOException) {
                throw AsrNetworkError("dashscope upload policy network error", t)
            } catch (t: AsrException) {
                throw t
            } catch (t: Throwable) {
                throw AsrUploadError("dashscope upload policy failed: ${t.message ?: "unknown"}", t)
            }
        debugDumpDir?.let { dir ->
            runCatching { dir.mkdirs() }
            runCatching { File(dir, "upload_policy_response.json").writeText(policyBody.trim() + "\n", Charsets.UTF_8) }
        }

        val obj =
            try {
                json.parseToJsonElement(policyBody).jsonObject
            } catch (t: Throwable) {
                throw AsrParseError("invalid dashscope policy response", t)
            }
        val data = obj["data"]?.jsonObject ?: throw AsrParseError("missing data in dashscope policy response")

        fun s(key: String): String {
            val v = runCatching { data[key]?.jsonPrimitive?.content }.getOrNull()?.trim().orEmpty()
            if (v.isBlank()) throw AsrParseError("missing policy field: $key")
            return v
        }

        val uploadHost = s("upload_host").trimEnd('/')
        val uploadDir = s("upload_dir").trim().trim('/')
        val ossAccessKeyId = s("oss_access_key_id")
        val policy = s("policy")
        val signature = s("signature")
        val acl = s("x_oss_object_acl")
        val forbidOverwrite = s("x_oss_forbid_overwrite")

        val key = "$uploadDir/${file.name}"
        debugDumpDir?.let { dir ->
            runCatching { dir.mkdirs() }
            runCatching {
                File(dir, "upload_file_info.txt").writeText(
                    buildString {
                        appendLine("file_name=${file.name}")
                        appendLine("file_bytes=${file.length()}")
                        appendLine("upload_host=$uploadHost")
                        appendLine("upload_dir=$uploadDir")
                        appendLine("oss_key=$key")
                    },
                    Charsets.UTF_8,
                )
            }
        }

        try {
            withContext(Dispatchers.IO) {
                val body =
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("OSSAccessKeyId", ossAccessKeyId)
                        .addFormDataPart("Signature", signature)
                        .addFormDataPart("policy", policy)
                        .addFormDataPart("x-oss-object-acl", acl)
                        .addFormDataPart("x-oss-forbid-overwrite", forbidOverwrite)
                        .addFormDataPart("key", key)
                        .addFormDataPart("success_action_status", "200")
                        .addFormDataPart(
                            "file",
                            file.name,
                            file.asRequestBody("application/octet-stream".toMediaType()),
                        )
                        .build()

                val req =
                    Request.Builder()
                        .url(uploadHost)
                        .post(body)
                        .build()
                httpClient.newCall(req).execute().use { resp ->
                    require2xx(resp, "upload file")
                    debugDumpDir?.let { dir ->
                        runCatching { dir.mkdirs() }
                        runCatching { File(dir, "upload_file_status.txt").writeText("http=${resp.code}\n", Charsets.UTF_8) }
                    }
                }
            }
        } catch (t: IOException) {
            throw AsrNetworkError("dashscope upload network error", t)
        } catch (t: AsrException) {
            throw t
        } catch (t: Throwable) {
            throw AsrUploadError("dashscope upload failed: ${t.message ?: "unknown"}", t)
        }

        return "oss://$key"
    }

    private fun require2xx(resp: Response, op: String) {
        if (resp.isSuccessful) return
        val code = resp.code
        val msg = resp.body?.string().orEmpty()
        throw AsrUploadError("$op failed: http $code ${msg.take(200)}".trim())
    }
}
