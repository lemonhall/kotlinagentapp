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
import com.lsl.kotlin_agent_app.databinding.FragmentSettingsBinding
import com.lsl.kotlin_agent_app.web.WebViewDataCleaner
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

        val current = repo.get()
        binding.inputBaseUrl.setText(current.baseUrl)
        binding.inputApiKey.setText(current.apiKey)
        binding.inputModel.setText(current.model)
        binding.inputTavilyUrl.setText(current.tavilyUrl)
        binding.inputTavilyApiKey.setText(current.tavilyApiKey)
        binding.switchWebPreview.isChecked = prefs.getBoolean(AppPrefsKeys.WEB_PREVIEW_ENABLED, false)

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
            Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show()
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
