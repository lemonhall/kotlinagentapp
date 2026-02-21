package com.lsl.kotlin_agent_app.ui.env_editor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.WindowCompat
import com.lsl.kotlin_agent_app.ui.theme.KotlinAgentAppTheme

class EnvEditorActivity : ComponentActivity() {

    private lateinit var vm: EnvEditorViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        super.onCreate(savedInstanceState)

        val agentsPath = intent?.getStringExtra(EXTRA_AGENTS_PATH)?.trim().orEmpty()
        val displayName = intent?.getStringExtra(EXTRA_DISPLAY_NAME)?.trim().orEmpty().ifBlank { ".env" }
        if (agentsPath.isBlank()) {
            Toast.makeText(this, "缺少文件路径", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        vm =
            ViewModelProvider(
                this,
                EnvEditorViewModel.Factory(applicationContext),
            )[EnvEditorViewModel::class.java]

        vm.load(agentsPath)

        val composeView =
            ComposeView(this).apply {
                setContent {
                    KotlinAgentAppTheme {
                        EnvEditorScreen(
                            vm = vm,
                            title = "⚙ " + displayName,
                            onBack = { finish() },
                            onToast = { msg -> Toast.makeText(this@EnvEditorActivity, msg, Toast.LENGTH_SHORT).show() },
                        )
                    }
                }
            }
        setContentView(composeView)
        title = displayName
    }

    companion object {
        private const val EXTRA_AGENTS_PATH = "agents_path"
        private const val EXTRA_DISPLAY_NAME = "display_name"

        fun intentOf(
            context: Context,
            agentsPath: String,
            displayName: String,
        ): Intent {
            return Intent(context, EnvEditorActivity::class.java).apply {
                putExtra(EXTRA_AGENTS_PATH, agentsPath)
                putExtra(EXTRA_DISPLAY_NAME, displayName)
            }
        }
    }
}
