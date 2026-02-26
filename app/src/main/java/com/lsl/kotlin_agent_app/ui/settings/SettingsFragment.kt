package com.lsl.kotlin_agent_app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsl.kotlin_agent_app.config.AppPrefsKeys
import com.lsl.kotlin_agent_app.config.LlmConfig
import com.lsl.kotlin_agent_app.config.ProviderEntry
import com.lsl.kotlin_agent_app.config.ProviderPresets
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
import java.util.UUID

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val providers = mutableListOf<ProviderEntry>()
    private var activeProviderId = ""
    private lateinit var cardAdapter: ProviderCardAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val prefs = requireContext().getSharedPreferences(
            "kotlin-agent-app", android.content.Context.MODE_PRIVATE,
        )
        val repo = SharedPreferencesLlmConfigRepository(prefs)
        val proxyRepo = SharedPreferencesProxyConfigRepository(prefs)
        val listeningHistory = ListeningHistoryStore(requireContext().applicationContext)

        val current = repo.get()
        providers.clear()
        providers.addAll(current.providers)
        activeProviderId = current.activeProviderId

        // ── Provider cards RecyclerView ──
        cardAdapter = ProviderCardAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            activeProviderId = { activeProviderId },
            onDelete = { entry ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete ${entry.displayName}?")
                    .setPositiveButton("Delete") { _, _ ->
                        providers.removeAll { it.id == entry.id }
                        if (activeProviderId == entry.id) {
                            activeProviderId = providers.firstOrNull()?.id.orEmpty()
                        }
                        refreshProviderList()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onChanged = { updated ->
                val idx = providers.indexOfFirst { it.id == updated.id }
                if (idx >= 0) providers[idx] = updated
            },
        )
        binding.recyclerProviders.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerProviders.adapter = cardAdapter
        refreshProviderList()

        // ── Active provider dropdown ──
        refreshActiveProviderDropdown()

        // ── Add Provider button ──
        binding.buttonAddProvider.setOnClickListener {
            val presetNames = ProviderPresets.ALL.map { it.displayName }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Add Provider")
                .setItems(presetNames) { _, which ->
                    val preset = ProviderPresets.ALL[which]
                    val entry = ProviderEntry(
                        id = UUID.randomUUID().toString(),
                        displayName = preset.displayName,
                        type = preset.type,
                        baseUrl = preset.defaultBaseUrl,
                        apiKey = "",
                        selectedModel = preset.defaultModels.firstOrNull().orEmpty(),
                        models = preset.defaultModels,
                    )
                    providers.add(entry)
                    if (providers.size == 1) activeProviderId = entry.id
                    refreshProviderList()
                    refreshActiveProviderDropdown()
                }
                .show()
        }

        // ── Tavily ──
        binding.inputTavilyUrl.setText(current.tavilyUrl)
        binding.inputTavilyApiKey.setText(current.tavilyApiKey)

        // ── Proxy ──
        val proxyCurrent = proxyRepo.get()
        binding.switchUseProxy.isChecked = proxyCurrent.enabled
        binding.inputHttpProxy.setText(proxyCurrent.httpProxy)
        binding.inputHttpsProxy.setText(proxyCurrent.httpsProxy)

        // ── Switches ──
        binding.switchWebPreview.isChecked = prefs.getBoolean(AppPrefsKeys.WEB_PREVIEW_ENABLED, false)
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
                .setPositiveButton("Agree") { _, _ -> listeningHistory.setEnabled(true) }
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

        // ── Save ──
        binding.buttonSave.setOnClickListener {
            repo.set(
                LlmConfig(
                    activeProviderId = activeProviderId,
                    providers = providers.toList(),
                    tavilyUrl = binding.inputTavilyUrl.text?.toString().orEmpty(),
                    tavilyApiKey = binding.inputTavilyApiKey.text?.toString().orEmpty(),
                ),
            )
            prefs.edit().putBoolean(AppPrefsKeys.WEB_PREVIEW_ENABLED, binding.switchWebPreview.isChecked).apply()
            prefs.edit().putBoolean(AppPrefsKeys.LISTENING_HISTORY_ENABLED, binding.switchListeningHistory.isChecked).apply()

            val newProxy = ProxyConfig(
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

    private fun refreshProviderList() {
        cardAdapter.submitList(providers.toList())
    }

    private fun refreshActiveProviderDropdown() {
        val labels = providers.map { "${it.displayName} (${it.selectedModel})" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        binding.inputActiveProvider.setAdapter(adapter)
        val activeIdx = providers.indexOfFirst { it.id == activeProviderId }
        if (activeIdx >= 0) {
            binding.inputActiveProvider.setText(labels[activeIdx], false)
        }
        binding.inputActiveProvider.setOnItemClickListener { _, _, position, _ ->
            activeProviderId = providers.getOrNull(position)?.id.orEmpty()
            cardAdapter.notifyDataSetChanged()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
