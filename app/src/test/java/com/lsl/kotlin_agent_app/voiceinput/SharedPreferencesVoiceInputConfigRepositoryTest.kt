package com.lsl.kotlin_agent_app.voiceinput

import android.content.Context
import com.lsl.kotlin_agent_app.config.AppPrefsKeys
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPreferencesVoiceInputConfigRepositoryTest {
    @Test
    fun get_prefersStoredApiKeyOverEnvFallback() {
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        val prefs = context.getSharedPreferences("voice-input-test-1", Context.MODE_PRIVATE)
        prefs.edit().putString(AppPrefsKeys.ASR_DASHSCOPE_API_KEY, "stored_key").commit()

        val repo =
            SharedPreferencesVoiceInputConfigRepository(
                prefs = prefs,
                envApiKeyProvider = { "env_key" },
            )

        assertEquals("stored_key", repo.get().apiKey)
        assertEquals(VoiceInputDefaults.MODEL, repo.get().model)
    }

    @Test
    fun get_fallsBackToEnvApiKeyWhenStoredValueIsBlank() {
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        val prefs = context.getSharedPreferences("voice-input-test-2", Context.MODE_PRIVATE)
        prefs.edit().putString(AppPrefsKeys.ASR_DASHSCOPE_API_KEY, "  ").commit()

        val repo =
            SharedPreferencesVoiceInputConfigRepository(
                prefs = prefs,
                envApiKeyProvider = { "env_key" },
            )

        assertEquals("env_key", repo.get().apiKey)
    }
}
