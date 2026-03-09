package com.lsl.kotlin_agent_app.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputDraftComposerTest {
    @Test
    fun partialTranscript_preservesExistingDraftWhilePreviewing() {
        val composer = VoiceInputDraftComposer(initialText = "帮我")

        assertEquals("帮我打开设置", composer.applyPartial("打开设置"))
        assertEquals("帮我", composer.committedText)
    }

    @Test
    fun finalTranscript_commitsTextAndClearsPreview() {
        val composer = VoiceInputDraftComposer(initialText = "帮我")

        composer.applyPartial("打开设置")
        assertEquals("帮我打开设置。", composer.applyFinal("打开设置。"))
        assertEquals("帮我打开设置。", composer.previewText)
        assertEquals("帮我打开设置。", composer.committedText)
    }

    @Test
    fun englishWords_insertSpaceWhenNeeded() {
        val composer = VoiceInputDraftComposer(initialText = "open")

        assertEquals("open settings", composer.applyFinal("settings"))
    }
}
