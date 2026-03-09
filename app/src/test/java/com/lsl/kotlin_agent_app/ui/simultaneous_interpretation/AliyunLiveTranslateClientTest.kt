package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import com.google.gson.JsonObject
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AliyunLiveTranslateClientTest {
    @Test
    fun inputTranscriptCompleted_emitsSourceAndRequestsResponse() {
        val sources = mutableListOf<String>()
        var requestCount = 0
        val dispatcher =
            LiveTranslateEventDispatcher(
                onSessionCreated = {},
                onSourceTranscriptCompleted = {
                    sources += it
                },
                onTranslationDelta = {},
                onAudioDelta = {},
                onResponseDone = {},
                onError = { _, _ -> },
                requestResponse = {
                    requestCount += 1
                },
            )

        dispatcher.onEvent(
            JsonObject().apply {
                addProperty("type", "conversation.item.input_audio_transcription.completed")
                addProperty("transcript", "你好")
            },
        )

        assertEquals(listOf("你好"), sources)
        assertEquals(1, requestCount)
    }

    @Test
    fun translationAndAudioEvents_areForwarded() {
        val translations = mutableListOf<String>()
        val audioChunks = mutableListOf<ByteArray>()
        var responseDone = 0
        val dispatcher =
            LiveTranslateEventDispatcher(
                onSessionCreated = {},
                onSourceTranscriptCompleted = {},
                onTranslationDelta = {
                    translations += it
                },
                onAudioDelta = {
                    audioChunks += it
                },
                onResponseDone = {
                    responseDone += 1
                },
                onError = { _, _ -> },
                requestResponse = {},
            )

        dispatcher.onEvent(
            JsonObject().apply {
                addProperty("type", "response.audio_transcript.delta")
                addProperty("delta", "Hello")
            },
        )
        dispatcher.onEvent(
            JsonObject().apply {
                addProperty("type", "response.audio.delta")
                addProperty("delta", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
            },
        )
        dispatcher.onEvent(
            JsonObject().apply {
                addProperty("type", "response.done")
            },
        )

        assertEquals(listOf("Hello"), translations)
        assertEquals(1, audioChunks.size)
        assertTrue(audioChunks.single().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(1, responseDone)
    }
}