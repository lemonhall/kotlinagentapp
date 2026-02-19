package com.lsl.kotlin_agent_app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lsl.kotlin_agent_app.config.AppPrefsKeys
import com.lsl.kotlin_agent_app.config.LlmConfig
import com.lsl.kotlin_agent_app.config.SharedPreferencesLlmConfigRepository
import com.lsl.kotlin_agent_app.config.SharedPreferencesProxyConfigRepository
import com.lsl.kotlin_agent_app.config.ProxyConfig
import com.lsl.kotlin_agent_app.databinding.FragmentSettingsBinding
import com.lsl.kotlin_agent_app.listening_history.ListeningHistoryPaths
import com.lsl.kotlin_agent_app.listening_history.ListeningHistoryStore
import com.lsl.kotlin_agent_app.net.ProxyManager
import com.lsl.kotlin_agent_app.web.WebViewDataCleaner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val prefs = requireContext().getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
        val repo =
            SharedPreferencesLlmConfigRepository(
                prefs,
            )
        val proxyRepo = SharedPreferencesProxyConfigRepository(prefs)
        val listeningHistory = ListeningHistoryStore(requireContext().applicationContext)

        val current = repo.get()
        binding.inputBaseUrl.setText(current.baseUrl)
        binding.inputApiKey.setText(current.apiKey)
        binding.inputModel.setText(current.model)
        binding.inputTavilyUrl.setText(current.tavilyUrl)
        binding.inputTavilyApiKey.setText(current.tavilyApiKey)
        binding.switchWebPreview.isChecked = prefs.getBoolean(AppPrefsKeys.WEB_PREVIEW_ENABLED, false)

        val proxyCurrent = proxyRepo.get()
        binding.switchUseProxy.isChecked = proxyCurrent.enabled
        binding.inputHttpProxy.setText(proxyCurrent.httpProxy)
        binding.inputHttpsProxy.setText(proxyCurrent.httpsProxy)

        binding.switchListeningHistory.isChecked = prefs.getBoolean(AppPrefsKeys.LISTENING_HISTORY_ENABLED, false)
        binding.textListeningHistoryPath.text = "Path: ${ListeningHistoryPaths.EVENTS_AGENTS_PATH}"

        var ignoreListeningSwitch = false
        binding.switchListeningHistory.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreListeningSwitch) return@setOnCheckedChangeListener
            if (!isChecked) {
                listeningHistory.setEnabled(false)
                return@setOnCheckedChangeListener
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Enable listening history?")
                .setMessage(
                    "This will write local JSONL events when you play/pause/resume/stop music or radio.\n\n" +
                        "Stored at:\n${ListeningHistoryPaths.EVENTS_AGENTS_PATH}\n\n" +
                        "You can clear it anytime.",
                )
                .setPositiveButton("Agree") { _, _ ->
                    listeningHistory.setEnabled(true)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    listeningHistory.setEnabled(false)
                    ignoreListeningSwitch = true
                    binding.switchListeningHistory.isChecked = false
                    ignoreListeningSwitch = false
                }
                .setOnCancelListener {
                    listeningHistory.setEnabled(false)
                    ignoreListeningSwitch = true
                    binding.switchListeningHistory.isChecked = false
                    ignoreListeningSwitch = false
                }
                .show()
        }

        binding.buttonClearListeningHistory.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear listening history?")
                .setMessage("This will delete local listening history on this device:\n${ListeningHistoryPaths.EVENTS_AGENTS_PATH}")
                .setPositiveButton("Delete") { _, _ ->
                    val ok = listeningHistory.clear(confirm = true)
                    Toast.makeText(requireContext(), if (ok) "Listening history cleared" else "Clear failed", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.buttonClearWebData.setOnClickListener {
            binding.buttonClearWebData.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { WebViewDataCleaner.clearAll(requireContext().applicationContext) }
                    .onSuccess { Toast.makeText(requireContext(), "WebView data cleared", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(requireContext(), "Clear failed: ${it.message}", Toast.LENGTH_SHORT).show() }
                binding.buttonClearWebData.isEnabled = true
            }
        }

        binding.buttonSave.setOnClickListener {
            repo.set(
                LlmConfig(
                    baseUrl = binding.inputBaseUrl.text?.toString().orEmpty(),
                    apiKey = binding.inputApiKey.text?.toString().orEmpty(),
                    model = binding.inputModel.text?.toString().orEmpty(),
                    tavilyUrl = binding.inputTavilyUrl.text?.toString().orEmpty(),
                    tavilyApiKey = binding.inputTavilyApiKey.text?.toString().orEmpty(),
                ),
                )
            prefs.edit().putBoolean(AppPrefsKeys.WEB_PREVIEW_ENABLED, binding.switchWebPreview.isChecked).apply()
            prefs.edit().putBoolean(AppPrefsKeys.LISTENING_HISTORY_ENABLED, binding.switchListeningHistory.isChecked).apply()

            val newProxy =
                ProxyConfig(
                    enabled = binding.switchUseProxy.isChecked,
                    httpProxy = binding.inputHttpProxy.text?.toString().orEmpty(),
                    httpsProxy = binding.inputHttpsProxy.text?.toString().orEmpty(),
                )
            proxyRepo.set(newProxy)
            ProxyManager.apply(requireContext().applicationContext, newProxy)

            Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show()
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
