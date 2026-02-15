package com.lsl.kotlin_agent_app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.lsl.kotlin_agent_app.config.LlmConfig
import com.lsl.kotlin_agent_app.config.SharedPreferencesLlmConfigRepository
import com.lsl.kotlin_agent_app.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val repo =
            SharedPreferencesLlmConfigRepository(
                requireContext().getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE),
            )

        val current = repo.get()
        binding.inputBaseUrl.setText(current.baseUrl)
        binding.inputApiKey.setText(current.apiKey)
        binding.inputModel.setText(current.model)

        binding.buttonSave.setOnClickListener {
            repo.set(
                LlmConfig(
                    baseUrl = binding.inputBaseUrl.text?.toString().orEmpty(),
                    apiKey = binding.inputApiKey.text?.toString().orEmpty(),
                    model = binding.inputModel.text?.toString().orEmpty(),
                ),
            )
            Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show()
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

