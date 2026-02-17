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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.lsl.kotlin_agent_app.agent.OpenAgenticSdkChatAgent
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.config.AppPrefsKeys
import com.lsl.kotlin_agent_app.config.SharedPreferencesLlmConfigRepository
import com.lsl.kotlin_agent_app.web.WebPreviewCoordinator
import com.lsl.kotlin_agent_app.web.WebViewControllerProvider
import java.io.File

class ChatFragment : Fragment() {
    private val viewModel: ChatViewModel by viewModels {
        val prefs = requireContext().getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
        val repo = SharedPreferencesLlmConfigRepository(prefs)
        val agent = OpenAgenticSdkChatAgent(requireContext(), prefs, repo)
        val workspace = AgentsWorkspace(requireContext())
        val files =
            object : AgentsFiles {
                override fun ensureInitialized() = workspace.ensureInitialized()

                override fun readTextFile(path: String, maxBytes: Long): String = workspace.readTextFile(path, maxBytes = maxBytes)
            }
        val getActiveSessionId = {
            prefs.getString(AppPrefsKeys.CHAT_SESSION_ID, null)?.trim()?.ifEmpty { null }
        }
        val storeRootDir = File(requireContext().filesDir, ".agents").absolutePath
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(agent = agent, files = files, getActiveSessionId = getActiveSessionId, storeRootDir = storeRootDir) as T
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncSessionHistoryIfNeeded()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val coordinator = androidx.compose.runtime.remember {
                    WebPreviewCoordinator(WebViewControllerProvider.instance)
                }
                var webPreviewVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(webPreviewVisible) {
                    if (webPreviewVisible) coordinator.start() else coordinator.stop()
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
                        onOpenReport = viewModel::openReportViewer,
                        onCloseReport = viewModel::closeReportViewer,
                        webPreviewVisible = webPreviewVisible,
                        webPreviewFrame = frame,
                        onToggleWebPreview = { webPreviewVisible = !webPreviewVisible },
                        onCloseWebPreview = { webPreviewVisible = false },
                        onOpenWeb = {
                            val bottomNav = activity?.findViewById<BottomNavigationView>(com.lsl.kotlin_agent_app.R.id.nav_view)
                            if (bottomNav != null) {
                                bottomNav.selectedItemId = com.lsl.kotlin_agent_app.R.id.navigation_web
                            } else {
                                findNavController().navigate(com.lsl.kotlin_agent_app.R.id.navigation_web)
                            }
                        },
                    )
                }
            }
        }
    }
}
