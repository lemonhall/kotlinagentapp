package com.lsl.kotlin_agent_app.asr

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AliyunQwenAsrClientTest {

    @Test
    fun transcribe_uploads_submits_polls_andParsesSentencesAsSegments() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val baseUrl = server.url("/api/v1").toString().trimEnd('/')

                // 1) uploads policy
                val policyJson =
                    """
                    {
                      "request_id": "r1",
                      "data": {
                        "upload_host": "${server.url("/upload")}",
                        "upload_dir": "dashscope-instant/abc/2026-02-20/xyz",
                        "oss_access_key_id": "ak",
                        "policy": "p",
                        "signature": "sig",
                        "x_oss_object_acl": "private",
                        "x_oss_forbid_overwrite": "true"
                      }
                    }
                    """.trimIndent()
                server.enqueue(MockResponse().setResponseCode(200).setBody(policyJson))
                // 2) upload
                server.enqueue(MockResponse().setResponseCode(200).setBody(""))

                // 3) submit async transcription
                val submitJson =
                    """
                    { "request_id": "r2", "output": { "task_id": "t_123", "task_status": "PENDING" } }
                    """.trimIndent()
                server.enqueue(MockResponse().setResponseCode(200).setBody(submitJson))

                // 4) poll pending -> running -> succeeded
                val pendingJson =
                    """
                    { "request_id": "r3", "output": { "task_id": "t_123", "task_status": "PENDING" } }
                    """.trimIndent()
                val runningJson =
                    """
                    { "request_id": "r4", "output": { "task_id": "t_123", "task_status": "RUNNING" } }
                    """.trimIndent()
                val succeededJson =
                    """
                    {
                      "request_id": "r5",
                      "output": {
                        "task_id": "t_123",
                        "task_status": "SUCCEEDED",
                        "result": {
                          "transcripts": [
                            {
                              "channel_id": 0,
                              "sentences": [
                                { "sentence_id": 0, "begin_time": 0, "end_time": 3200, "language": "ja", "emotion": "neutral", "text": "こんにちは" },
                                { "sentence_id": 1, "begin_time": 3500, "end_time": 8100, "language": "ja", "emotion": "neutral", "text": "ニュースです" }
                              ]
                            }
                          ]
                        }
                      }
                    }
                    """.trimIndent()
                server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson))
                server.enqueue(MockResponse().setResponseCode(200).setBody(runningJson))
                server.enqueue(MockResponse().setResponseCode(200).setBody(succeededJson))

                val uploader = DashScopeFileUploader(baseUrl = baseUrl, apiKey = "k_test")
                val client =
                    AliyunQwenAsrClient(
                        baseUrl = baseUrl,
                        apiKey = "k_test",
                        modelName = "qwen3-asr-flash-filetrans",
                        uploader = uploader,
                        pollDelayMs = 0L,
                        timeoutMs = 5_000L,
                        delayFn = { },
                    )

                val f = File.createTempFile("chunk_001", ".ogg")
                f.writeBytes(byteArrayOf(0x01, 0x02))

                val out = client.transcribe(audioFile = f, mimeType = "audio/ogg", language = "ja")
                assertEquals(2, out.segments.size)
                assertEquals("ja", out.detectedLanguage)
                assertEquals(0L, out.segments[0].startMs)
                assertEquals("こんにちは", out.segments[0].text)

                // Basic smoke: requests should have been made.
                val req1 = server.takeRequest()
                assertTrue(req1.path!!.startsWith("/api/v1/uploads?action=getPolicy"))
                val req2 = server.takeRequest()
                assertEquals("/upload", req2.path)
                val req3 = server.takeRequest()
                assertEquals("/api/v1/services/audio/asr/transcription", req3.path)
                assertEquals("enable", req3.getHeader("X-DashScope-Async"))
                assertEquals("enable", req3.getHeader("X-DashScope-OssResourceResolve"))
                val req4 = server.takeRequest()
                assertEquals("/api/v1/tasks/t_123", req4.path)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun transcribe_whenPollReturnsTranscriptionUrl_downloadsFileAndParses() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val baseUrl = server.url("/api/v1").toString().trimEnd('/')

                // 1) uploads policy
                val policyJson =
                    """
                    {
                      "request_id": "r1",
                      "data": {
                        "upload_host": "${server.url("/upload")}",
                        "upload_dir": "dashscope-instant/abc/2026-02-20/xyz",
                        "oss_access_key_id": "ak",
                        "policy": "p",
                        "signature": "sig",
                        "x_oss_object_acl": "private",
                        "x_oss_forbid_overwrite": "true"
                      }
                    }
                    """.trimIndent()
                server.enqueue(MockResponse().setResponseCode(200).setBody(policyJson))
                // 2) upload
                server.enqueue(MockResponse().setResponseCode(200).setBody(""))

                // 3) submit async transcription
                val submitJson =
                    """
                    { "request_id": "r2", "output": { "task_id": "t_123", "task_status": "PENDING" } }
                    """.trimIndent()
                server.enqueue(MockResponse().setResponseCode(200).setBody(submitJson))

                // 4) poll succeeded with transcription_url
                val succeededJson =
                    """
                    {
                      "request_id": "r5",
                      "output": {
                        "task_id": "t_123",
                        "task_status": "SUCCEEDED",
                        "result": {
                          "transcription_url": "${server.url("/result.json")}"
                        }
                      }
                    }
                    """.trimIndent()
                server.enqueue(MockResponse().setResponseCode(200).setBody(succeededJson))

                // 5) download transcription file
                val transcriptionJson =
                    """
                    {
                      "file_url": "http://example.com/chunk_001.ogg",
                      "audio_info": { "format": "opus", "sample_rate": 48000 },
                      "transcripts": [
                        {
                          "channel_id": 0,
                          "text": "hello",
                          "sentences": [
                            { "sentence_id": 0, "begin_time": 0, "end_time": 1000, "language": "en", "emotion": "neutral", "text": "hello" }
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
                server.enqueue(MockResponse().setResponseCode(200).setBody(transcriptionJson))

                val uploader = DashScopeFileUploader(baseUrl = baseUrl, apiKey = "k_test")
                val client =
                    AliyunQwenAsrClient(
                        baseUrl = baseUrl,
                        apiKey = "k_test",
                        modelName = "qwen3-asr-flash-filetrans",
                        uploader = uploader,
                        pollDelayMs = 0L,
                        timeoutMs = 5_000L,
                        delayFn = { },
                    )

                val f = File.createTempFile("chunk_001", ".ogg")
                f.writeBytes(byteArrayOf(0x01, 0x02))

                val out = client.transcribe(audioFile = f, mimeType = "audio/ogg", language = "en")
                assertEquals(1, out.segments.size)
                assertEquals("en", out.detectedLanguage)
                assertEquals("hello", out.segments[0].text)

                // Basic smoke: requests should have been made.
                val req1 = server.takeRequest()
                assertTrue(req1.path!!.startsWith("/api/v1/uploads?action=getPolicy"))
                val req2 = server.takeRequest()
                assertEquals("/upload", req2.path)
                val req3 = server.takeRequest()
                assertEquals("/api/v1/services/audio/asr/transcription", req3.path)
                val req4 = server.takeRequest()
                assertEquals("/api/v1/tasks/t_123", req4.path)
                val req5 = server.takeRequest()
                assertEquals("/result.json", req5.path)
            } finally {
                server.shutdown()
            }
        }
}
