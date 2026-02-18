package com.lsl.kotlin_agent_app.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsl.kotlin_agent_app.databinding.FragmentTerminalBinding
import kotlinx.coroutines.launch

class TerminalFragment : Fragment() {
    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: TerminalViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTerminalBinding.inflate(inflater, container, false)
        viewModel =
            ViewModelProvider(this, TerminalViewModel.Factory(requireContext()))
                .get(TerminalViewModel::class.java)

        val adapter =
            TerminalRunsAdapter(
                onClick = { item -> showRunDialog(item.summary, item.artifacts) },
            )
        binding.recyclerRuns.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRuns.adapter = adapter

        binding.buttonToggleStdin.setOnClickListener {
            val show = binding.containerStdin.visibility != View.VISIBLE
            binding.containerStdin.visibility = if (show) View.VISIBLE else View.GONE
        }

        binding.buttonRun.setOnClickListener {
            val cmd = binding.inputCommand.text?.toString().orEmpty()
            val stdin = binding.inputStdin.text?.toString()
            viewModel.runCommand(
                command = cmd,
                stdin = stdin,
                onCompleted = { run ->
                    showRunDialog(run.summary, artifacts = run.artifacts)
                },
            )
        }

        binding.buttonRefresh.setOnClickListener { viewModel.refresh() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { st ->
                adapter.submit(st.runs)
                binding.progressRunning.visibility = if (st.isRunning) View.VISIBLE else View.GONE
                binding.textError.visibility = if (st.error.isNullOrBlank()) View.GONE else View.VISIBLE
                binding.textError.text = st.error.orEmpty()
                binding.textEmpty.visibility = if (st.runs.isEmpty()) View.VISIBLE else View.GONE
                binding.buttonRun.isEnabled = !st.isRunning
            }
        }

        viewModel.refresh()
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showRunDialog(
        summary: TerminalRunSummary,
        artifacts: List<TerminalRunArtifact>,
    ) {
        val text =
            buildString {
                appendLine("command:")
                appendLine(summary.command)
                appendLine()
                appendLine("exit_code: ${summary.exitCode}")
                if (!summary.errorCode.isNullOrBlank()) appendLine("error_code: ${summary.errorCode}")
                if (!summary.errorMessage.isNullOrBlank()) appendLine("error_message: ${summary.errorMessage}")
                appendLine()
                appendLine("stdout:")
                appendLine(summary.stdout.ifBlank { "(empty)" })
                appendLine()
                appendLine("stderr:")
                appendLine(summary.stderr.ifBlank { "(empty)" })
                if (artifacts.isNotEmpty()) {
                    appendLine()
                    appendLine("artifacts:")
                    for (a in artifacts) {
                        appendLine("- ${a.path} (${a.mime}) ${a.description}".trim())
                    }
                }
            }.trimEnd()

        val tv =
            TextView(requireContext()).apply {
                setTextIsSelectable(true)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                textSize = 12f
                setText(text)
            }
        val scroll =
            ScrollView(requireContext()).apply {
                addView(tv)
            }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Terminal Output")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .setNeutralButton("复制") { _, _ -> copyToClipboard(text) }
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    private fun dp(value: Int): Int {
        val d = resources.displayMetrics.density
        return (value * d).toInt()
    }
}
