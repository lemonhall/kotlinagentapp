package com.lsl.kotlin_agent_app.ui.bilingual_player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.radio_bilingual.player.AndroidMediaMetadataChunkDurationReader
import com.lsl.kotlin_agent_app.radio_bilingual.player.BilingualSessionLoader
import com.lsl.kotlin_agent_app.radio_bilingual.player.Media3SessionPlayerController

internal class BilingualPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
        if (sessionId.isBlank()) {
            Toast.makeText(this, "缺少 sessionId", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val ws = AgentsWorkspace(applicationContext).also { it.ensureInitialized() }
        val loader =
            BilingualSessionLoader(
                workspace = ws,
                durationReader = AndroidMediaMetadataChunkDurationReader(),
            )
        val player = Media3SessionPlayerController(applicationContext)

        val vm =
            ViewModelProvider(
                this,
                BilingualPlayerViewModel.Factory(
                    workspace = ws,
                    loader = loader,
                    player = player,
                ),
            )[BilingualPlayerViewModel::class.java]

        vm.loadSession(sessionId)

        val composeView =
            ComposeView(this).apply {
                setContent {
                    MaterialTheme {
                        BilingualPlayerScreen(
                            vm = vm,
                            onBack = { finish() },
                        )
                    }
                }
            }
        setContentView(composeView)
    }

    companion object {
        private const val EXTRA_SESSION_ID = "session_id"

        fun intentOf(context: Context, sessionId: String): Intent {
            return Intent(context, BilingualPlayerActivity::class.java).putExtra(EXTRA_SESSION_ID, sessionId)
        }
    }
}
