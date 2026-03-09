package com.lsl.kotlin_agent_app.ui.simultaneous_interpretation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import com.lsl.kotlin_agent_app.ui.theme.KotlinAgentAppTheme

class SimultaneousInterpretationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        super.onCreate(savedInstanceState)

        val archiveRootPath = ".agents/workspace/simultaneous_interpretation"
        val composeView =
            ComposeView(this).apply {
                setContent {
                    KotlinAgentAppTheme {
                        SimultaneousInterpretationScreen(
                            archiveRootPath = archiveRootPath,
                            onBack = { finish() },
                        )
                    }
                }
            }
        setContentView(composeView)
    }

    companion object {
        fun intentOf(context: Context): Intent {
            return Intent(context, SimultaneousInterpretationActivity::class.java)
        }
    }
}