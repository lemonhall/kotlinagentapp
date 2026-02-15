package com.lsl.kotlin_agent_app.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.lsl.kotlin_agent_app.agent.OpenAgenticSdkChatAgent
import com.lsl.kotlin_agent_app.config.SharedPreferencesLlmConfigRepository

class ChatFragment : Fragment() {
    private val viewModel: ChatViewModel by viewModels {
        val prefs = requireContext().getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
        val repo = SharedPreferencesLlmConfigRepository(prefs)
        val agent = OpenAgenticSdkChatAgent(requireContext(), prefs, repo)
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(agent) as T
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val uiState by viewModel.uiState.collectAsState()
                MaterialTheme {
                    ChatScreen(
                        uiState = uiState,
                        onSend = viewModel::sendUserMessage,
                        onClear = viewModel::clearConversation,
                    )
                }
            }
        }
    }
}
