package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.lsl.kotlin_agent_app.ui.dashboard.TranslationLanguagePickerDialog
import com.lsl.kotlin_agent_app.ui.theme.KotlinAgentAppTheme

class SimultaneousInterpretationActivity : ComponentActivity() {
    private lateinit var vm: SimultaneousInterpretationViewModel

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                vm.startSession()
            } else {
                vm.setError("未授予麦克风权限")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        super.onCreate(savedInstanceState)

        vm =
            ViewModelProvider(
                this,
                SimultaneousInterpretationViewModel.Factory(applicationContext),
            )[SimultaneousInterpretationViewModel::class.java]

        val composeView =
            ComposeView(this).apply {
                setContent {
                    KotlinAgentAppTheme {
                        val state by vm.uiState.collectAsState()
                        SimultaneousInterpretationScreen(
                            state = state,
                            onBack = { finish() },
                            onToggleSession = { toggleSession() },
                            onPickTargetLanguage = { showLanguagePicker() },
                            onToggleAudioCaptureMode = { vm.toggleAudioCaptureMode() },
                        )
                    }
                }
            }
        setContentView(composeView)
    }

    override fun onDestroy() {
        vm.stopSession()
        super.onDestroy()
    }

    private fun toggleSession() {
        val state = vm.uiState.value
        if (state.isRunning || state.isConnecting) {
            vm.stopSession()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startSession()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showLanguagePicker() {
        TranslationLanguagePickerDialog.show(
            context = this,
            title = "选择目标语言",
        ) { picked ->
            val label = picked.label.substringAfter(' ', missingDelimiterValue = picked.label).trim()
            vm.setTargetLanguage(picked.code, label)
        }
    }

    companion object {
        fun intentOf(context: Context): Intent {
            return Intent(context, SimultaneousInterpretationActivity::class.java)
        }
    }
}
