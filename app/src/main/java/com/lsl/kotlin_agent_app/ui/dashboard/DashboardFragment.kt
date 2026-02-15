package com.lsl.kotlin_agent_app.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.app.AlertDialog
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import com.lsl.kotlin_agent_app.databinding.FragmentDashboardBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var editorDialog: AlertDialog? = null
    private var editorDialogPath: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val filesViewModel =
            ViewModelProvider(this, FilesViewModel.Factory(requireContext()))
                .get(FilesViewModel::class.java)

        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val adapter =
            FilesEntryAdapter(
                onClick = { entry ->
                    if (entry.type == AgentsDirEntryType.Dir) {
                        filesViewModel.goInto(entry)
                    } else {
                        filesViewModel.openFile(entry)
                    }
                },
                onLongClick = { entry ->
                    val isDir = entry.type == AgentsDirEntryType.Dir
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("删除确认")
                        .setMessage("确定删除 ${entry.name} 吗？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ ->
                            filesViewModel.deleteEntry(entry, recursive = isDir)
                        }
                        .show()
                },
            )

        binding.recyclerEntries.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerEntries.adapter = adapter

        binding.buttonRefresh.setOnClickListener { filesViewModel.refresh() }
        binding.buttonUp.setOnClickListener { filesViewModel.goUp() }
        binding.buttonUp.setOnLongClickListener {
            filesViewModel.goRoot()
            true
        }
        binding.buttonNewFile.setOnClickListener { promptNew("新建文件") { filesViewModel.createFile(it) } }
        binding.buttonNewFolder.setOnClickListener { promptNew("新建目录") { filesViewModel.createFolder(it) } }

        filesViewModel.state.observe(viewLifecycleOwner) { st ->
            binding.textCwd.text = displayCwd(st.cwd)
            binding.textError.visibility = if (st.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.textError.text = st.errorMessage.orEmpty()
            adapter.submitList(st.entries)

            val openPath = st.openFilePath
            val openText = st.openFileText
            if (!openPath.isNullOrBlank() && openText != null && (editorDialog?.isShowing != true || editorDialogPath != openPath)) {
                showEditor(openPath, openText) { action ->
                    when (action) {
                        is EditorAction.Save -> filesViewModel.saveEditor(action.text)
                        EditorAction.Close -> filesViewModel.closeEditor()
                    }
                }
            }
        }

        filesViewModel.refresh()
        return root
    }

    private fun promptNew(title: String, onOk: (String) -> Unit) {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "相对路径，如: foo.txt 或 folder/bar.md"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                if (name.isNotBlank()) onOk(name)
            }
            .show()
    }

    private sealed class EditorAction {
        data class Save(val text: String) : EditorAction()
        data object Close : EditorAction()
    }

    private fun showEditor(path: String, text: String, onAction: (EditorAction) -> Unit) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(text)
            setSelection(text.length)
            setHorizontallyScrolling(false)
            minLines = 12
        }
        editorDialog?.dismiss()
        val dialog =
            MaterialAlertDialogBuilder(requireContext())
            .setTitle(path)
            .setView(input)
            .setNegativeButton("关闭") { _, _ -> onAction(EditorAction.Close) }
            .setPositiveButton("保存") { _, _ -> onAction(EditorAction.Save(input.text?.toString().orEmpty())) }
            .create()
        dialog.setOnDismissListener {
            editorDialog = null
            editorDialogPath = null
            onAction(EditorAction.Close)
        }
        editorDialog = dialog
        editorDialogPath = path
        dialog.show()
    }

    private fun displayCwd(cwd: String): String {
        val p = cwd.trim()
        if (p == ".agents") return "根目录"
        if (p.startsWith(".agents/")) return "根目录/" + p.removePrefix(".agents/").trimStart('/')
        return p
    }

    override fun onDestroyView() {
        super.onDestroyView()
        editorDialog?.dismiss()
        editorDialog = null
        editorDialogPath = null
        _binding = null
    }
}
