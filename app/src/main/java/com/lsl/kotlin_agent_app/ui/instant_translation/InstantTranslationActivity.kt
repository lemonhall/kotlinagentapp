package com.lsl.kotlin_agent_app.ui.instant_translation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.lsl.kotlin_agent_app.ui.dashboard.TranslationLanguagePickerDialog
import com.lsl.kotlin_agent_app.ui.theme.KotlinAgentAppTheme
import com.lsl.kotlin_agent_app.voiceinput.FunAsrRealtimeVoiceInputEngine
import com.lsl.kotlin_agent_app.voiceinput.SharedPreferencesVoiceInputConfigRepository
import com.lsl.kotlin_agent_app.voiceinput.VoiceInputController

class InstantTranslationActivity : ComponentActivity() {
    private lateinit var voiceInputController: VoiceInputController
    private lateinit var voiceInputConfigRepo: SharedPreferencesVoiceInputConfigRepository
    private lateinit var vm: InstantTranslationViewModel

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceInput()
            } else {
                vm.setError("\u672a\u6388\u4e88\u9ea6\u514b\u98ce\u6743\u9650")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("kotlin-agent-app", MODE_PRIVATE)
        voiceInputConfigRepo = SharedPreferencesVoiceInputConfigRepository(prefs)
        voiceInputController =
            VoiceInputController(
                engineFactory = {
                    FunAsrRealtimeVoiceInputEngine(
                        context = applicationContext,
                        config = voiceInputConfigRepo.get(),
                    )
                },
            )
        vm =
            ViewModelProvider(
                this,
                InstantTranslationViewModel.Factory(applicationContext),
            )[InstantTranslationViewModel::class.java]

        val composeView =
            ComposeView(this).apply {
                setContent {
                    KotlinAgentAppTheme {
                        InstantTranslationScreen(
                            vm = vm,
                            voiceInputStateFlow = voiceInputController.state,
                            onBack = { finish() },
                            onToggleListening = ::toggleVoiceInput,
                            onPickTargetLanguage = ::showLanguagePicker,
                        )
                    }
                }
            }
        setContentView(composeView)
    }

    override fun onDestroy() {
        voiceInputController.stop()
        super.onDestroy()
    }

    private fun toggleVoiceInput() {
        val state = voiceInputController.state.value
        if (state.isRecording || state.isStarting) {
            voiceInputController.stop()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceInput() {
        val config = voiceInputConfigRepo.get()
        if (config.apiKey.isBlank()) {
            vm.setError("\u8bf7\u5148\u5728 Settings \u7684 Voice Input \u4e2d\u586b\u5199 DASHSCOPE_API_KEY")
            return
        }
        voiceInputController.start(
            initialDraft = "",
            onDraftChanged = {},
            onPartialTranscript = vm::onPartialTranscript,
            onFinalTranscript = vm::onFinalTranscript,
        )
    }

    private fun showLanguagePicker() {
        TranslationLanguagePickerDialog.show(this) { picked ->
            vm.setTargetLanguage(
                code = picked.code,
                label = picked.label,
            )
        }
    }

    companion object {
        fun intentOf(context: Context): Intent {
            return Intent(context, InstantTranslationActivity::class.java)
        }
    }
}