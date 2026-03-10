package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimultaneousInterpretationViewModelTest {
    @Test
    fun startSession_updatesStateFromControllerEvents() {
        val controller = FakeLiveTranslateSessionController()
        val vm =
            SimultaneousInterpretationViewModel(
                sessionController = controller,
                apiKeyProvider = { "sk-test" },
                configRepo = SharedPreferencesSimultaneousInterpretationConfigRepository(InMemorySharedPreferences()),
            )

        vm.startSession()

        assertEquals("en", controller.lastStartConfig?.targetLanguageCode)
        assertEquals(LiveTranslateAudioCaptureMode.SENSITIVE, controller.lastStartConfig?.audioCaptureMode)
        controller.listener?.onSessionStarted(
            sessionPath = ".agents/workspace/simultaneous_interpretation/2026年03月09日 晚22点01分",
            isHeadsetConnected = false,
        )
        controller.listener?.onSourceTranscriptCompleted("你好")
        controller.listener?.onTranslationDelta("Hello")
        controller.listener?.onSegmentFinal(sourceText = "你好", translatedText = "Hello")

        val state = vm.uiState.value
        assertTrue(state.isRunning)
        assertFalse(state.isHeadsetConnected)
        assertEquals("你好", state.sourcePreview)
        assertEquals("Hello", state.translatedPreview)
        assertEquals(1, state.segments.size)
        assertEquals("Hello", state.segments.single().translatedText)
    }

    @Test
    fun startSession_withoutApiKey_setsError() {
        val controller = FakeLiveTranslateSessionController()
        val vm =
            SimultaneousInterpretationViewModel(
                sessionController = controller,
                apiKeyProvider = { "" },
                configRepo = SharedPreferencesSimultaneousInterpretationConfigRepository(InMemorySharedPreferences()),
            )

        vm.startSession()

        assertEquals("请先在 Settings 的 Voice Input 中填写 DASHSCOPE_API_KEY", vm.uiState.value.errorMessage)
        assertEquals(null, controller.lastStartConfig)
    }
}

private class FakeLiveTranslateSessionController : LiveTranslateSessionController {
    var listener: LiveTranslateSessionListener? = null
    var lastStartConfig: LiveTranslateSessionStartConfig? = null
    var stopCount: Int = 0

    override fun bind(listener: LiveTranslateSessionListener) {
        this.listener = listener
    }

    override fun start(config: LiveTranslateSessionStartConfig) {
        lastStartConfig = config
    }

    override fun stop() {
        stopCount += 1
    }
}

private class InMemorySharedPreferences : android.content.SharedPreferences {
    private val storage = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = storage.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? = (storage[key] as? String) ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (storage[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = (storage[key] as? Int) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = (storage[key] as? Long) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = (storage[key] as? Float) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (storage[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = storage.containsKey(key)

    override fun edit(): android.content.SharedPreferences.Editor = Editor(storage)

    override fun registerOnSharedPreferenceChangeListener(
        listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private class Editor(
        private val storage: MutableMap<String, Any?>,
    ) : android.content.SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
            if (key != null) storage[key] = value
            return this
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): android.content.SharedPreferences.Editor {
            if (key != null) storage[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor {
            if (key != null) storage[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor {
            if (key != null) storage[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor {
            if (key != null) storage[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor {
            if (key != null) storage[key] = value
            return this
        }

        override fun remove(key: String?): android.content.SharedPreferences.Editor {
            if (key != null) storage.remove(key)
            return this
        }

        override fun clear(): android.content.SharedPreferences.Editor {
            storage.clear()
            return this
        }

        override fun commit(): Boolean = true

        override fun apply() = Unit
    }
}
