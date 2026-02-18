package com.lsl.kotlin_agent_app.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import android.content.Intent
import android.content.Context
import android.content.ClipboardManager
import androidx.core.content.FileProvider
import android.webkit.MimeTypeMap
import android.content.ClipData
import android.net.Uri
import android.provider.OpenableColumns
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.app.AlertDialog
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.databinding.FragmentDashboardBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.lsl.kotlin_agent_app.ui.markdown.MarkwonProvider
import com.lsl.kotlin_agent_app.config.AppPrefsKeys
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLConnection
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import com.lsl.kotlin_agent_app.media.MusicPlayerControllerProvider

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var editorDialog: AlertDialog? = null
    private var editorDialogPath: String? = null
    private var editorDialogKind: EditorDialogKind? = null
    private var suppressCloseOnDismissOnce: Boolean = false
    private val sidRx = Regex("^[a-f0-9]{32}$", RegexOption.IGNORE_CASE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private var filesViewModel: FilesViewModel? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importExternalUriToInbox(uri)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        MusicPlayerControllerProvider.installAppContext(requireContext().applicationContext)
        val musicController = MusicPlayerControllerProvider.get()

        val filesViewModel =
            ViewModelProvider(this, FilesViewModel.Factory(requireContext()))
                .get(FilesViewModel::class.java)
        this.filesViewModel = filesViewModel

        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val adapter =
            FilesEntryAdapter(
                onClick = { entry ->
                    val cwd = filesViewModel.state.value?.cwd ?: ".agents"
                    if (entry.type == AgentsDirEntryType.Dir) {
                        if (cwd == ".agents/sessions" && sidRx.matches(entry.name)) {
                            val kind = readSessionKind(entry.name)
                            if (kind == "task") {
                                filesViewModel.goInto(entry)
                            } else {
                                resumeChatSession(entry.name)
                            }
                        } else {
                            filesViewModel.goInto(entry)
                        }
                    } else {
                        val path = joinAgentsPath(cwd, entry.name)
                        if (isMp3Name(entry.name) && isInMusicsTree(path)) {
                            musicController.playAgentsMp3(path)
                        } else {
                            filesViewModel.openFile(entry)
                        }
                    }
                },
                onLongClick = { entry ->
                    val isDir = entry.type == AgentsDirEntryType.Dir
                    val cwd = filesViewModel.state.value?.cwd ?: ".agents"
                    val relativePath = joinAgentsPath(cwd, entry.name)
                    val isMp3InMusics = (!isDir && isMp3Name(entry.name) && isInMusicsTree(relativePath))

                    val actions =
                        if (isMp3InMusics) {
                            arrayOf("播放", "播放/暂停", "停止", "分享", "剪切", "删除", "复制路径")
                        } else if (isDir) {
                            val isSessionDir = (cwd == ".agents/sessions" && sidRx.matches(entry.name))
                            if (isSessionDir) arrayOf("进入目录", "剪切", "删除") else arrayOf("剪切", "删除")
                        } else {
                            arrayOf("分享", "剪切", "删除")
                        }

                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(entry.name)
                        .setItems(actions) { _, which ->
                            val action = actions.getOrNull(which) ?: return@setItems
                            when (action) {
                                "进入目录" -> filesViewModel.goInto(entry)
                                "播放" -> musicController.playAgentsMp3(relativePath)
                                "播放/暂停" -> musicController.togglePlayPause()
                                "停止" -> musicController.stop()
                                "分享" -> shareAgentsFile(relativePath)
                                "剪切" -> {
                                    filesViewModel.cutEntry(entry)
                                    Toast.makeText(requireContext(), "已剪切：${entry.name}（到目标目录点“粘贴”）", Toast.LENGTH_SHORT).show()
                                }
                                "复制路径" -> copyTextToClipboard("path", relativePath)
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
        binding.recyclerEntries.setOnLongClickListener {
            val st = filesViewModel.state.value
            if (st?.clipboardCutPath.isNullOrBlank()) {
                Toast.makeText(requireContext(), "剪切板为空", Toast.LENGTH_SHORT).show()
            } else {
                filesViewModel.pasteCutIntoCwd()
            }
            true
        }

        binding.buttonRefresh.setOnClickListener { filesViewModel.refresh() }
        binding.buttonUp.setOnClickListener { filesViewModel.goUp() }
        binding.buttonUp.setOnLongClickListener {
            filesViewModel.goRoot()
            true
        }
        binding.buttonNewFile.setOnClickListener { promptNew("新建文件") { filesViewModel.createFile(it) } }
        binding.buttonNewFolder.setOnClickListener { promptNew("新建目录") { filesViewModel.createFolder(it) } }
        binding.buttonImport.setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }
        binding.buttonPaste.setOnClickListener {
            val st = filesViewModel.state.value
            if (st?.clipboardCutPath.isNullOrBlank()) {
                Toast.makeText(requireContext(), "剪切板为空", Toast.LENGTH_SHORT).show()
            } else {
                filesViewModel.pasteCutIntoCwd()
            }
        }
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

        binding.buttonMusicHelp.setOnClickListener {
            showMusicTroubleshootingDialog()
        }
        binding.buttonMusicPlayPause.setOnClickListener {
            musicController.togglePlayPause()
        }
        binding.buttonMusicStop.setOnClickListener {
            musicController.stop()
        }

        filesViewModel.state.observe(viewLifecycleOwner) { st ->
            binding.textCwd.text = displayCwd(st.cwd)
            binding.textError.visibility = if (st.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
             binding.textError.text = st.errorMessage.orEmpty()
             adapter.submitList(st.entries)
             binding.buttonClearSessions.visibility = if (st.cwd == ".agents/sessions") View.VISIBLE else View.GONE
             binding.buttonPaste.visibility = if (!st.clipboardCutPath.isNullOrBlank()) View.VISIBLE else View.GONE
             val inMusics = isInMusicsTree(st.cwd)
             binding.buttonMusicHelp.visibility = if (inMusics) View.VISIBLE else View.GONE
             binding.textMusicHint.visibility = if (inMusics) View.VISIBLE else View.GONE
             if (inMusics) {
                 binding.textMusicHint.text = "仅 musics/ 启用 mp3 播放与 metadata；后台不断播可点右上角“排障”。"
             }

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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                musicController.state.collect { st ->
                    updateMiniBar(st)
                    val cwd = filesViewModel.state.value?.cwd.orEmpty()
                    if (isInMusicsTree(cwd)) {
                        val base = "仅 musics/ 启用 mp3 播放与 metadata；后台不断播可点右上角“排障”。"
                        val warn = st.warningMessage?.trim()?.ifBlank { null }
                        binding.textMusicHint.text = if (warn != null) "$base\n$warn" else base
                    }
                }
            }
        }

        filesViewModel.refresh()
        return root
    }

    private fun updateMiniBar(st: com.lsl.kotlin_agent_app.media.MusicNowPlayingState) {
        val has = !st.agentsPath.isNullOrBlank()
        binding.musicMiniBar.visibility = if (has) View.VISIBLE else View.GONE
        if (!has) return

        fun fmt(ms: Long): String {
            val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
            val m = totalSec / 60
            val s = totalSec % 60
            return "%d:%02d".format(m, s)
        }

        binding.textMusicTitle.text = st.title?.trim()?.ifBlank { null } ?: "unknown"

        val pos = fmt(st.positionMs)
        val dur = st.durationMs?.let { fmt(it) }
        val timeLabel = if (dur != null) "$pos / $dur" else pos
        val artist = st.artist?.trim()?.ifBlank { null }
        binding.textMusicSubtitle.text =
            listOfNotNull(artist, timeLabel).joinToString(" · ").ifBlank { timeLabel }

        val progress =
            if (st.durationMs != null && st.durationMs > 0L) {
                ((st.positionMs.coerceIn(0L, st.durationMs) * 1000L) / st.durationMs).toInt().coerceIn(0, 1000)
            } else {
                0
            }
        binding.progressMusic.progress = progress

        binding.buttonMusicPlayPause.setImageResource(
            if (st.isPlaying) com.lsl.kotlin_agent_app.R.drawable.ic_pause_24 else com.lsl.kotlin_agent_app.R.drawable.ic_play_arrow_24
        )
    }

    private fun isMp3Name(name: String): Boolean {
        return name.trim().lowercase().endsWith(".mp3")
    }

    private fun isInMusicsTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        return p == ".agents/workspace/musics" || p.startsWith(".agents/workspace/musics/")
    }

    private fun copyTextToClipboard(label: String, text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun showMusicTroubleshootingDialog() {
        val msg =
            """
            如果后台/锁屏不断播，常见原因是系统电量/后台限制。

            华为 Nova 9（EMUI/Harmony）建议检查：
            1) 设置 → 电池 → 应用启动管理：允许本应用自启动/关联启动/后台活动
            2) 设置 → 电池 → 更多电池设置：关闭/放宽“休眠时始终保持网络连接”（如有）
            3) 设置 → 应用和服务 → 应用管理 → 本应用 → 电池：允许后台活动；将“电池优化”设为“不优化”（如有）
            4) Android 13+：通知权限需要开启，否则媒体通知可能无法显示，影响后台保活与控播

            说明：不同 ROM 行为可能不同；若仍会被系统杀，请记录触发条件并进入下一轮处理。
            """.trimIndent()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("后台播放排障")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
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

    private fun importExternalUriToInbox(uri: Uri) {
        val vm = filesViewModel ?: return
        val appContext = requireContext().applicationContext
        val resolver = appContext.contentResolver
        val ws = AgentsWorkspace(appContext)
        val inboxDir = ".agents/workspace/inbox"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (_, destName) =
                    withContext(Dispatchers.IO) {
                        ws.ensureInitialized()
                        ws.mkdir(inboxDir)

                        val displayName = queryDisplayName(resolver, uri) ?: ("import_" + System.currentTimeMillis())
                        val safeName = sanitizeFileName(displayName)
                        val finalName = allocateNonConflictingName(ws, dir = inboxDir, fileName = safeName)
                        val destPath0 = ws.joinPath(inboxDir, finalName)

                        resolver.openInputStream(uri)?.use { input ->
                            val destFile = File(appContext.filesDir, destPath0)
                            destFile.parentFile?.mkdirs()
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        } ?: error("无法读取来源文件")

                        destPath0 to finalName
                    }

                Toast.makeText(requireContext(), "已导入：$destName", Toast.LENGTH_SHORT).show()
                vm.goTo(inboxDir)
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "导入失败：${t.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun queryDisplayName(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx < 0) return null
                c.getString(idx)?.trim()?.ifBlank { null }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned =
            name
                .trim()
                .replace('\u0000', ' ')
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
        return cleaned.ifBlank { "imported" }
    }

    private fun allocateNonConflictingName(
        ws: AgentsWorkspace,
        dir: String,
        fileName: String,
    ): String {
        val base = fileName.trim().ifBlank { "imported" }
        val dot = base.lastIndexOf('.').takeIf { it > 0 && it < base.length - 1 }
        val stem = dot?.let { base.substring(0, it) } ?: base
        val ext = dot?.let { base.substring(it) } ?: ""

        var n = 0
        while (true) {
            val candidate = if (n == 0) base else "${stem}_$n$ext"
            val path = ws.joinPath(dir, candidate)
            if (!ws.exists(path)) return candidate
            n++
            if (n >= 1000) error("同名文件过多")
        }
    }

    private fun resumeChatSession(sessionId: String) {
        val sid = sessionId.trim()
        if (!sidRx.matches(sid)) return
        val prefs = requireContext().getSharedPreferences("kotlin-agent-app", Context.MODE_PRIVATE)
        prefs.edit().putString(AppPrefsKeys.CHAT_SESSION_ID, sid).apply()

        Toast.makeText(requireContext(), "已切换会话：${sid.take(8)}", Toast.LENGTH_SHORT).show()

        val bottomNav = activity?.findViewById<BottomNavigationView>(com.lsl.kotlin_agent_app.R.id.nav_view)
        if (bottomNav != null) {
            bottomNav.selectedItemId = com.lsl.kotlin_agent_app.R.id.navigation_home
        }
    }

    private fun readSessionKind(sessionId: String): String? {
        val sid = sessionId.trim()
        if (!sidRx.matches(sid)) return null
        val meta = File(requireContext().filesDir, ".agents/sessions/$sid/meta.json")
        if (!meta.exists() || !meta.isFile) return null
        val raw =
            try {
                meta.readText(Charsets.UTF_8)
            } catch (_: Throwable) {
                return null
            }
        val obj =
            try {
                json.parseToJsonElement(raw).jsonObject
            } catch (_: Throwable) {
                return null
            }
        val md = obj["metadata"] as? JsonObject ?: return null
        return md["kind"]?.jsonPrimitive?.content?.trim()?.lowercase()?.ifBlank { null }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        editorDialog?.dismiss()
        editorDialog = null
        editorDialogPath = null
        _binding = null
    }
}
