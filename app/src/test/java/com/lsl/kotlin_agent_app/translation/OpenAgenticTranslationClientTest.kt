package com.lsl.kotlin_agent_app.translation

import com.lsl.kotlin_agent_app.radio_transcript.TranscriptSegment
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenAgenticTranslationClientTest {

    @Test
    fun translateBatch_parsesResponsesSseCompleted() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val sseBody =
                    buildString {
                        val completed =
                            """
                            {"type":"response.completed","response":{"id":"resp_1","output":[{"type":"message","content":[{"type":"output_text","text":"[{\"id\":0,\"translatedText\":\"你好\"}]"}]}]}}
                            """.trimIndent()
                        append("data: ")
                        append(completed.trim())
                        append("\n\n")
                    }
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("content-type", "text/event-stream")
                        .setBody(sseBody),
                )

                val baseUrl = server.url("/v1").toString().trimEnd('/')
                val client =
                    OpenAgenticTranslationClient(
                        baseUrl = baseUrl,
                        apiKey = "test-key",
                        model = "test-model",
                        maxBatchChars = 2000,
                    )

                val segs = listOf(TranscriptSegment(id = 0, startMs = 0, endMs = 1000, text = "hello", emotion = null))
                val out = client.translateBatch(segs, context = emptyList(), sourceLanguage = "en", targetLanguage = "zh")
                assertEquals(1, out.size)
                assertEquals("你好", out[0].translatedText)
            } finally {
                server.shutdown()
            }
        }
}
