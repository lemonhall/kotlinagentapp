package com.lsl.kotlin_agent_app.translation

import com.lsl.kotlin_agent_app.radio_transcript.TranscriptSegment
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationPromptBuilderTest {

    @Test
    fun buildResponsesInput_hasSystemAndUserMessages() {
        val segments =
            listOf(
                TranscriptSegment(id = 0, startMs = 0, endMs = 1000, text = "こんにちは", emotion = null),
                TranscriptSegment(id = 1, startMs = 1000, endMs = 2000, text = "世界", emotion = "neutral"),
            )
        val ctx =
            listOf(
                TranslatedSegment(id = 9, startMs = 0, endMs = 0, sourceText = "前文", translatedText = "previous", emotion = null),
            )
        val input = TranslationPromptBuilder.buildResponsesInput(segments, ctx, sourceLanguage = "ja", targetLanguage = "zh")

        assertEquals(2, input.size)
        assertEquals("system", input[0]["role"]?.toString()?.trim('"'))
        assertEquals("user", input[1]["role"]?.toString()?.trim('"'))

        val userText =
            runCatching {
                input[1]["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            }.getOrNull().orEmpty()
        assertTrue(userText.contains("Segments to translate"))
        assertTrue(userText.contains("\"id\":0"))
        assertTrue(userText.contains("\"id\":1"))
        assertTrue(userText.contains("Context"))
    }
}
