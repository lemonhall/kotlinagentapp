package com.lsl.kotlin_agent_app.ui.qqmail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.lsl.kotlin_agent_app.ui.theme.KotlinAgentAppTheme

class QqMailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        super.onCreate(savedInstanceState)

        val vm =
            ViewModelProvider(
                this,
                QqMailViewModel.Factory(applicationContext),
            )[QqMailViewModel::class.java]

        val composeView =
            ComposeView(this).apply {
                setContent {
                    KotlinAgentAppTheme {
                        QqMailScreen(
                            vm = vm,
                            onBack = { finish() },
                        )
                    }
                }
            }
        setContentView(composeView)
    }

    companion object {
        fun intentOf(context: Context): Intent {
            return Intent(context, QqMailActivity::class.java)
        }
    }
}

