package com.lsl.kotlin_agent_app.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.lsl.kotlin_agent_app.agent.OpenAgenticSdkChatAgent
import com.lsl.kotlin_agent_app.config.AppPrefsKeys
import com.lsl.kotlin_agent_app.config.SharedPreferencesLlmConfigRepository
import com.lsl.kotlin_agent_app.web.WebPreviewCoordinator
import com.lsl.kotlin_agent_app.web.WebViewControllerProvider

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
                val prefs = requireContext().getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
                val webPreviewEnabledState = androidx.compose.runtime.remember {
                    mutableStateOf(prefs.getBoolean(AppPrefsKeys.WEB_PREVIEW_ENABLED, false))
                }
                DisposableEffect(prefs) {
                    val listener =
                        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                            if (key == AppPrefsKeys.WEB_PREVIEW_ENABLED) {
                                webPreviewEnabledState.value =
                                    prefs.getBoolean(AppPrefsKeys.WEB_PREVIEW_ENABLED, false)
                            }
                        }
                    prefs.registerOnSharedPreferenceChangeListener(listener)
                    onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
                }

                val coordinator = androidx.compose.runtime.remember {
                    WebPreviewCoordinator(WebViewControllerProvider.instance)
                }
                val webPreviewEnabled = webPreviewEnabledState.value
                androidx.compose.runtime.LaunchedEffect(webPreviewEnabled) {
                    if (webPreviewEnabled) coordinator.start() else coordinator.stop()
                }
                DisposableEffect(Unit) {
                    onDispose { coordinator.stop() }
                }

                val uiState by viewModel.uiState.collectAsState()
                val frame by coordinator.frame.collectAsState()
                MaterialTheme {
                    ChatScreen(
                        uiState = uiState,
                        onSend = viewModel::sendUserMessage,
                        onClear = viewModel::clearConversation,
                        onStop = viewModel::stopSending,
                        webPreviewEnabled = webPreviewEnabled,
                        webPreviewFrame = frame,
                        onOpenWeb = { findNavController().navigate(com.lsl.kotlin_agent_app.R.id.navigation_web) },
                    )
                }
            }
        }
    }
}
