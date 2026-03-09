package com.lsl.kotlin_agent_app.ui.chat

import android.Manifest
import android.os.Bundle
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.lsl.kotlin_agent_app.agent.OpenAgenticSdkChatAgent
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcConnectionState
import com.lsl.kotlin_agent_app.agent.tools.irc.IrcSessionRuntimeStore
import com.lsl.kotlin_agent_app.config.AppPrefsKeys
import com.lsl.kotlin_agent_app.config.SharedPreferencesLlmConfigRepository
import com.lsl.kotlin_agent_app.ui.theme.KotlinAgentAppTheme
import com.lsl.kotlin_agent_app.voiceinput.FunAsrRealtimeVoiceInputEngine
import com.lsl.kotlin_agent_app.voiceinput.SharedPreferencesVoiceInputConfigRepository
import com.lsl.kotlin_agent_app.voiceinput.VoiceInputController
import com.lsl.kotlin_agent_app.web.WebPreviewCoordinator
import com.lsl.kotlin_agent_app.web.WebViewControllerProvider
import java.io.File

class ChatFragment : Fragment() {
    private lateinit var voiceInputController: VoiceInputController
    private lateinit var voiceInputConfigRepo: SharedPreferencesVoiceInputConfigRepository

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceInput()
            } else {
                voiceInputController.setError("未授予麦克风权限")
            }
        }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = requireContext().getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
        voiceInputConfigRepo = SharedPreferencesVoiceInputConfigRepository(prefs)
        voiceInputController =
            VoiceInputController(
                engineFactory = {
                    FunAsrRealtimeVoiceInputEngine(
                        context = requireContext().applicationContext,
                        config = voiceInputConfigRepo.get(),
                    )
                },
            )
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncSessionHistoryIfNeeded()

        IrcSessionRuntimeStore.installAppContext(requireContext().applicationContext)
        val prefs = requireContext().getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
        val sessionKey = prefs.getString(AppPrefsKeys.CHAT_SESSION_ID, null)?.trim()?.ifEmpty { null } ?: return
        val stateNow = IrcSessionRuntimeStore.statusFlow(sessionKey).value.state
        if (stateNow == IrcConnectionState.Joined) return
        if (stateNow == IrcConnectionState.Connecting || stateNow == IrcConnectionState.Reconnecting) return
        val agentsRoot = File(requireContext().filesDir, ".agents").canonicalFile
        IrcSessionRuntimeStore.requestConnect(
            agentsRoot = agentsRoot,
            sessionKey = sessionKey,
            force = false,
        )
    }

    override fun onStop() {
        voiceInputController.stop()
        super.onStop()
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
                val voiceInputState by voiceInputController.state.collectAsState()
                val frame by coordinator.frame.collectAsState()
                KotlinAgentAppTheme {
                    ChatScreen(
                        uiState = uiState,
                        onDraftChange = viewModel::setDraftText,
                        onSendDraft = viewModel::sendDraftMessage,
                        onClear = viewModel::clearConversation,
                        onStop = viewModel::stopSending,
                        voiceInputState = voiceInputState,
                        onToggleVoiceInput = ::toggleVoiceInput,
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

    private fun toggleVoiceInput() {
        val state = voiceInputController.state.value
        if (state.isRecording || state.isStarting) {
            voiceInputController.stop()
            return
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceInput() {
        val config = voiceInputConfigRepo.get()
        if (config.apiKey.isBlank()) {
            voiceInputController.setError("请先在 Settings 的 Voice Input 中填写 DASHSCOPE_API_KEY")
            return
        }

        voiceInputController.start(
            initialDraft = viewModel.uiState.value.draftText,
            onDraftChanged = viewModel::setDraftText,
        )
    }
}
