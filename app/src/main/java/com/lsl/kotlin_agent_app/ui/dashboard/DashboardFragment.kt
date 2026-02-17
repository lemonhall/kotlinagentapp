package com.lsl.kotlin_agent_app.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import android.content.Intent
import androidx.core.content.FileProvider
import android.webkit.MimeTypeMap
import android.content.ClipData
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.app.AlertDialog
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import com.lsl.kotlin_agent_app.databinding.FragmentDashboardBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.lsl.kotlin_agent_app.ui.markdown.MarkwonProvider
import java.io.File
import java.net.URLConnection
import android.widget.Toast

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var editorDialog: AlertDialog? = null
    private var editorDialogPath: String? = null
    private var editorDialogKind: EditorDialogKind? = null
    private var suppressCloseOnDismissOnce: Boolean = false

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
                    val cwd = filesViewModel.state.value?.cwd ?: ".agents"
                    val relativePath = joinAgentsPath(cwd, entry.name)

                    val actions =
                        if (isDir) {
                            arrayOf("删除")
                        } else {
                            arrayOf("分享", "删除")
                        }

                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(entry.name)
                        .setItems(actions) { _, which ->
                            val action = actions.getOrNull(which) ?: return@setItems
                            when (action) {
                                "分享" -> shareAgentsFile(relativePath)
                                "删除" ->
                                    MaterialAlertDialogBuilder(requireContext())
                                        .setTitle("删除确认")
                                        .setMessage("确定删除 ${entry.name} 吗？")
                                        .setNegativeButton("取消", null)
                                        .setPositiveButton("删除") { _, _ ->
                                            filesViewModel.deleteEntry(entry, recursive = isDir)
                                        }
                                        .show()
                            }
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
        binding.buttonClearSessions.setOnClickListener {
            val cwd = filesViewModel.state.value?.cwd ?: ".agents"
            if (cwd != ".agents/sessions") {
                Toast.makeText(requireContext(), "仅在 sessions 目录可用", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("清理 sessions")
                .setMessage("将删除 .agents/sessions 下所有会话目录（不删除文件）。此操作不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理") { _, _ ->
                    filesViewModel.clearSessions()
                }
                .show()
        }

        filesViewModel.state.observe(viewLifecycleOwner) { st ->
            binding.textCwd.text = displayCwd(st.cwd)
            binding.textError.visibility = if (st.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.textError.text = st.errorMessage.orEmpty()
            adapter.submitList(st.entries)
            binding.buttonClearSessions.visibility = if (st.cwd == ".agents/sessions") View.VISIBLE else View.GONE

            val openPath = st.openFilePath
            val openText = st.openFileText
            if (!openPath.isNullOrBlank() && openText != null) {
                val desiredKind =
                    when {
                        isMarkdownPath(openPath) -> EditorDialogKind.MarkdownPreview
                        isJsonPath(openPath) -> EditorDialogKind.PlainPreview
                        else -> EditorDialogKind.PlainEditor
                    }
                val shouldShow =
                    (editorDialog?.isShowing != true) ||
                        (editorDialogPath != openPath) ||
                        (editorDialogKind != desiredKind)

                if (shouldShow) {
                    when (desiredKind) {
                        EditorDialogKind.MarkdownPreview ->
                            showMarkdownPreview(openPath, openText) { action ->
                                when (action) {
                                    EditorAction.Edit -> showEditor(openPath, openText) { editor ->
                                        when (editor) {
                                            is EditorAction.Save -> filesViewModel.saveEditor(editor.text)
                                            EditorAction.Close -> filesViewModel.closeEditor()
                                            else -> Unit
                                        }
                                    }

                                    EditorAction.Close -> filesViewModel.closeEditor()
                                    else -> Unit
                                }
                            }

                        EditorDialogKind.PlainPreview ->
                            showPlainPreview(openPath, openText) { action ->
                                when (action) {
                                    EditorAction.Edit -> showEditor(openPath, openText) { editor ->
                                        when (editor) {
                                            is EditorAction.Save -> filesViewModel.saveEditor(editor.text)
                                            EditorAction.Close -> filesViewModel.closeEditor()
                                            else -> Unit
                                        }
                                    }

                                    EditorAction.Close -> filesViewModel.closeEditor()
                                    else -> Unit
                                }
                            }

                        EditorDialogKind.PlainEditor ->
                            showEditor(openPath, openText) { action ->
                                when (action) {
                                    is EditorAction.Save -> filesViewModel.saveEditor(action.text)
                                    EditorAction.Close -> filesViewModel.closeEditor()
                                    else -> Unit
                                }
                            }
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
        data object Edit : EditorAction()
        data object Close : EditorAction()
    }

    private fun showEditor(path: String, text: String, onAction: (EditorAction) -> Unit) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(text)
            setSelection(text.length)
            setHorizontallyScrolling(false)
            minLines = 12
        }
        if (editorDialog?.isShowing == true) {
            suppressCloseOnDismissOnce = true
            editorDialog?.dismiss()
        }
        val dialog =
            MaterialAlertDialogBuilder(requireContext())
            .setTitle(path)
            .setView(input)
            .setNegativeButton("关闭") { _, _ -> onAction(EditorAction.Close) }
            .setPositiveButton("保存") { _, _ -> onAction(EditorAction.Save(input.text?.toString().orEmpty())) }
            .create()
        dialog.setOnDismissListener { handleDialogDismiss(onAction, closeAction = EditorAction.Close) }
        editorDialog = dialog
        editorDialogPath = path
        editorDialogKind = EditorDialogKind.PlainEditor
        dialog.show()
    }

    private enum class EditorDialogKind {
        PlainEditor,
        PlainPreview,
        MarkdownPreview,
    }

    private fun showPlainPreview(path: String, content: String, onAction: (EditorAction) -> Unit) {
        val tv =
            TextView(requireContext()).apply {
                setTextIsSelectable(true)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurface,
                        android.graphics.Color.BLACK,
                    ),
                )
                typeface = android.graphics.Typeface.MONOSPACE
                text = content
            }
        val scroll = ScrollView(requireContext()).apply { addView(tv) }

        if (editorDialog?.isShowing == true) {
            suppressCloseOnDismissOnce = true
            editorDialog?.dismiss()
        }
        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(path)
                .setView(scroll)
                .setNegativeButton("关闭") { _, _ -> onAction(EditorAction.Close) }
                .setPositiveButton("编辑") { _, _ -> onAction(EditorAction.Edit) }
                .create()
        dialog.setOnDismissListener { handleDialogDismiss(onAction, closeAction = EditorAction.Close) }
        editorDialog = dialog
        editorDialogPath = path
        editorDialogKind = EditorDialogKind.PlainPreview
        dialog.show()
    }

    private fun showMarkdownPreview(path: String, text: String, onAction: (EditorAction) -> Unit) {
        val tv =
            TextView(requireContext()).apply {
                setTextIsSelectable(true)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK))
            }
        MarkwonProvider.get(requireContext()).setMarkdown(tv, text)
        val scroll = ScrollView(requireContext()).apply { addView(tv) }

        if (editorDialog?.isShowing == true) {
            suppressCloseOnDismissOnce = true
            editorDialog?.dismiss()
        }
        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(path)
                .setView(scroll)
                .setNegativeButton("关闭") { _, _ -> onAction(EditorAction.Close) }
                .setPositiveButton("编辑") { _, _ -> onAction(EditorAction.Edit) }
                .create()
        dialog.setOnDismissListener { handleDialogDismiss(onAction, closeAction = EditorAction.Close) }
        editorDialog = dialog
        editorDialogPath = path
        editorDialogKind = EditorDialogKind.MarkdownPreview
        dialog.show()
    }

    private fun handleDialogDismiss(onAction: (EditorAction) -> Unit, closeAction: EditorAction) {
        val suppressed = suppressCloseOnDismissOnce
        suppressCloseOnDismissOnce = false
        editorDialog = null
        editorDialogPath = null
        editorDialogKind = null
        if (!suppressed) onAction(closeAction)
    }

    private fun isMarkdownPath(path: String): Boolean {
        val p = path.lowercase()
        return p.endsWith(".md") || p.endsWith(".markdown")
    }

    private fun isJsonPath(path: String): Boolean {
        val p = path.lowercase()
        return p.endsWith(".json") || p.endsWith(".jsonl")
    }

    private fun dp(value: Int): Int {
        val d = resources.displayMetrics.density
        return (value * d).toInt()
    }

    private fun joinAgentsPath(dir: String, name: String): String {
        val d = dir.replace('\\', '/').trim().trimEnd('/')
        val n = name.replace('\\', '/').trim().trimStart('/')
        if (n.isEmpty()) return d
        return if (d.isEmpty() || d == ".agents") ".agents/$n" else "$d/$n"
    }

    private fun shareAgentsFile(relativePath: String) {
        val rel = relativePath.replace('\\', '/').trim()
        if (!rel.startsWith(".agents/")) return

        val file = File(requireContext().filesDir, rel)
        if (!file.exists() || !file.isFile) return

        val uri =
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file,
            )

        val ext = file.extension.lowercase().takeIf { it.isNotBlank() }
        val mimeFromMap =
            ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        val mime =
            mimeFromMap
                ?: URLConnection.guessContentTypeFromName(file.name)
                ?: when (ext) {
                    "md", "markdown" -> "text/markdown"
                    "jsonl", "json" -> "application/json"
                    "txt", "log" -> "text/plain"
                    else -> "application/octet-stream"
                }

        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(requireContext().contentResolver, file.name, uri)
            }

        startActivity(Intent.createChooser(intent, "分享文件"))
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
