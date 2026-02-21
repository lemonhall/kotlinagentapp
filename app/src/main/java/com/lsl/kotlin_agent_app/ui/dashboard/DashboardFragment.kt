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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.app.AlertDialog
import com.lsl.kotlin_agent_app.agent.AgentsDirEntry
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.databinding.FragmentDashboardBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.lsl.kotlin_agent_app.ui.markdown.MarkwonProvider
import com.lsl.kotlin_agent_app.config.AppPrefsKeys
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLConnection
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import com.lsl.kotlin_agent_app.media.MusicPlayerControllerProvider
import com.lsl.kotlin_agent_app.databinding.BottomSheetMusicPlayerBinding
import com.lsl.kotlin_agent_app.media.lyrics.LrcParser
import com.lsl.kotlin_agent_app.media.lyrics.LrcLine
import com.lsl.kotlin_agent_app.smb_media.SmbMediaActions
import com.lsl.kotlin_agent_app.radios.RadioPathNaming
import com.lsl.kotlin_agent_app.radios.RadioStationFileV1
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.radio.RadioCommand
import com.lsl.kotlin_agent_app.radio_recordings.RecordingMetaV1
import com.lsl.kotlin_agent_app.radio_translation.TranslationChunkV1
import com.lsl.kotlin_agent_app.radio_transcript.TranscriptCliException
import com.lsl.kotlin_agent_app.radio_transcript.TranscriptTaskManager
import com.lsl.kotlin_agent_app.radio_transcript.TranscriptTasksIndexV1
import com.lsl.kotlin_agent_app.radio_transcript.RecordingPipelineManager
import com.lsl.kotlin_agent_app.recordings.RecordingRoots
import com.lsl.kotlin_agent_app.recordings.RecordingSessionRef
import com.lsl.kotlin_agent_app.ui.bilingual_player.BilingualPlayerActivity
import com.lsl.kotlin_agent_app.ui.video_player.VideoPlayerActivity
import com.lsl.kotlin_agent_app.ui.image_viewer.ImageViewerActivity
import com.lsl.kotlin_agent_app.ui.pdf_viewer.PdfViewerActivity
import com.lsl.kotlin_agent_app.ui.env_editor.EnvEditorActivity
import com.lsl.kotlin_agent_app.ui.ledger.LedgerActivity

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

    private var lastCoverBytesRef: ByteArray? = null
    private var lastCoverBitmap: Bitmap? = null

    private var musicSheetDialog: BottomSheetDialog? = null
    private var musicSheetBinding: BottomSheetMusicPlayerBinding? = null
    private var sheetLyricsAdapter: LyricsLineAdapter? = null
    private var sheetLyricsRaw: String? = null
    private var sheetLyricsTimed: List<LrcLine>? = null
    private var sheetLastHighlightedIndex: Int = -1
    private var sheetIsSlidingVolume: Boolean = false
    private var lastPlaybackErrorToast: String? = null

    private val recordingByAgentsPath = linkedMapOf<String, String>()

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
                        if (DashboardLedgerRules.isLedgerDirAtWorkspaceRoot(cwd = cwd, entry = entry)) {
                            startActivity(LedgerActivity.intentOf(requireContext()))
                            return@FilesEntryAdapter
                        }
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
                        if (isRadioName(entry.name) && isInRadiosTree(path)) {
                            musicController.playAgentsRadio(path)
                        } else if (isAudioName(entry.name) && isInMusicsTree(path)) {
                            musicController.playAgentsMp3(path)
                        } else if (isAudioName(entry.name) && isInNasSmbTree(path)) {
                            val display = entry.displayName ?: entry.name
                            SmbMediaActions.playNasSmbAudio(
                                context = requireContext(),
                                agentsPath = path,
                                displayName = display,
                                musicController = musicController,
                            )
                        } else if (isVideoName(entry.name) && isInNasSmbTree(path)) {
                            val display = entry.displayName ?: entry.name
                            val ref =
                                SmbMediaActions.createNasSmbMp4Content(
                                    context = requireContext(),
                                    agentsPath = path,
                                    displayName = display,
                                ) ?: return@FilesEntryAdapter
                            startActivity(
                                VideoPlayerActivity.intentOf(
                                    context = requireContext(),
                                    uri = ref.uri,
                                    displayName = display,
                                    mime = ref.mime,
                                    agentsPath = path,
                                )
                            )
                        } else if (isVideoName(entry.name)) {
                            val display = entry.displayName ?: entry.name
                            openAgentsVideoInternal(path, displayName = display)
                        } else if (isImageName(entry.name) && isInNasSmbTree(path)) {
                            val display = entry.displayName ?: entry.name
                            val ref =
                                SmbMediaActions.createNasSmbImageContent(
                                    context = requireContext(),
                                    agentsPath = path,
                                    displayName = display,
                                ) ?: return@FilesEntryAdapter
                            startActivity(
                                ImageViewerActivity.intentOf(
                                    context = requireContext(),
                                    uri = ref.uri,
                                    displayName = display,
                                    mime = ref.mime,
                                    agentsPath = path,
                                )
                            )
                        } else if (isPdfName(entry.name) && isInNasSmbTree(path)) {
                            val display = entry.displayName ?: entry.name
                            val ref =
                                SmbMediaActions.createNasSmbPdfContent(
                                    context = requireContext(),
                                    agentsPath = path,
                                    displayName = display,
                                ) ?: return@FilesEntryAdapter
                            startActivity(
                                PdfViewerActivity.intentOf(
                                    context = requireContext(),
                                    uri = ref.uri,
                                    displayName = display,
                                    agentsPath = path,
                                )
                            )
                        } else if (isImageName(entry.name)) {
                            val display = entry.displayName ?: entry.name
                            openAgentsImageInternal(path, displayName = display)
                        } else if (isPdfName(entry.name)) {
                            val display = entry.displayName ?: entry.name
                            openAgentsPdfInternal(path, displayName = display)
                        } else if (isEnvName(entry.name)) {
                            val display = entry.displayName ?: entry.name
                            startActivity(
                                EnvEditorActivity.intentOf(
                                    context = requireContext(),
                                    agentsPath = path,
                                    displayName = display,
                                )
                            )
                        } else if (isOggName(entry.name) && isInRadioRecordingsTree(path)) {
                            musicController.playAgentsRecordingOgg(path)
                        } else if (shouldOpenExternalByDefault(entry.name) && isInNasSmbTree(path)) {
                            val display = entry.displayName ?: entry.name
                            SmbMediaActions.openNasSmbFileExternal(
                                context = requireContext(),
                                agentsPath = path,
                                displayName = display,
                                chooserTitle = "打开文件",
                            )
                        } else if (shouldOpenExternalByDefault(entry.name)) {
                            val display = entry.displayName ?: entry.name
                            openAgentsFileExternal(path, displayName = display, chooserTitle = "打开文件")
                        } else {
                            filesViewModel.openFile(entry)
                        }
                    }
                },
                onLongClick = { entry ->
                    val isDir = entry.type == AgentsDirEntryType.Dir
                    val cwd = filesViewModel.state.value?.cwd ?: ".agents"
                    val relativePath = joinAgentsPath(cwd, entry.name)
                    val normalizedCwd = cwd.replace('\\', '/').trim().trimEnd('/')
                    val isRecordingSessionDir =
                        isDir &&
                            (normalizedCwd == RecordingRoots.RADIO_ROOT_DIR || normalizedCwd == RecordingRoots.MICROPHONE_ROOT_DIR) &&
                            entry.name.trim().startsWith("rec_")
                    val isAudioInMusics = (!isDir && isAudioName(entry.name) && isInMusicsTree(relativePath))
                    val isRadioInRadios = (!isDir && isRadioName(entry.name) && isInRadiosTree(relativePath))
                    val isOggInRadioRecordings = (!isDir && isOggName(entry.name) && isInRadioRecordingsTree(relativePath))
                    val isRadioInFavorites = isRadioInRadios && isInRadioFavorites(relativePath)

                    if (isRecordingSessionDir) {
                        val ref = RecordingSessionRef(rootDir = normalizedCwd, sessionId = entry.name.trim())
                        showRecordingSessionTranscriptMenu(
                            sessionRef = ref,
                            displayName = entry.displayName ?: entry.name,
                        )
                        return@FilesEntryAdapter
                    }

                    if (isDir && normalizedCwd == ".agents/workspace" && entry.name.trim() == "recordings") {
                        showMicrophoneRecordingsRootMenu()
                        return@FilesEntryAdapter
                    }
                    if (isDir && normalizedCwd == ".agents/workspace" && entry.name.trim() == "radio_recordings") {
                        showRadioRecordingsRootMenu()
                        return@FilesEntryAdapter
                    }
                    if (DashboardLedgerRules.isLedgerDirAtWorkspaceRoot(cwd = normalizedCwd, entry = entry)) {
                        val actions = DashboardLedgerRules.ledgerDirLongClickActions()
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(entry.name)
                            .setItems(actions) { _, which ->
                                val action = actions.getOrNull(which) ?: return@setItems
                                when (action) {
                                    "进入目录" -> filesViewModel.goInto(entry)
                                    "打开账本" -> startActivity(LedgerActivity.intentOf(requireContext()))
                                    "剪切" -> filesViewModel.cutEntry(entry)
                                    "复制" -> filesViewModel.copyEntry(entry)
                                    "重命名" -> {
                                        val input =
                                            android.widget.EditText(requireContext()).apply {
                                                setText(entry.name)
                                                setSelection(entry.name.length)
                                            }
                                        MaterialAlertDialogBuilder(requireContext())
                                            .setTitle("重命名")
                                            .setView(input)
                                            .setNegativeButton("取消", null)
                                            .setPositiveButton("确定") { _, _ ->
                                                val newName = input.text?.toString().orEmpty().trim()
                                                filesViewModel.renameEntry(entry, newName)
                                            }
                                            .show()
                                    }
                                    "复制路径" -> copyTextToClipboard("path", relativePath)
                                    "删除" ->
                                        MaterialAlertDialogBuilder(requireContext())
                                            .setTitle("删除确认")
                                            .setMessage("确定删除 ${entry.name} 吗？")
                                            .setNegativeButton("取消", null)
                                            .setPositiveButton("删除") { _, _ ->
                                                filesViewModel.deleteEntry(entry, recursive = true)
                                            }
                                            .show()
                                }
                            }
                            .show()
                        return@FilesEntryAdapter
                    }

                    val actions =
                        if (isRadioInRadios) {
                            if (isRadioInFavorites) {
                                arrayOf("播放", "播放/暂停", "停止", "移出收藏", "分享", "剪切", "复制", "重命名", "删除", "复制路径")
                            } else {
                                arrayOf("播放", "播放/暂停", "停止", "收藏", "分享", "剪切", "复制", "重命名", "删除", "复制路径")
                            }
                        } else if (isAudioInMusics) {
                            arrayOf("播放", "播放/暂停", "停止", "分享", "剪切", "复制", "重命名", "删除", "复制路径")
                        } else if (isOggInRadioRecordings) {
                            arrayOf("播放", "播放/暂停", "停止", "分享", "剪切", "复制", "重命名", "删除", "复制路径")
                        } else if (isDir) {
                            val isSessionDir = (cwd == ".agents/sessions" && sidRx.matches(entry.name))
                            if (isSessionDir) arrayOf("进入目录", "剪切", "复制", "重命名", "删除", "复制路径") else arrayOf("剪切", "复制", "重命名", "删除", "复制路径")
                        } else {
                            arrayOf("打开", "分享", "剪切", "复制", "重命名", "删除", "复制路径")
                        }

                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(entry.name)
                        .setItems(actions) { _, which ->
                            val action = actions.getOrNull(which) ?: return@setItems
                            when (action) {
                                "进入目录" -> filesViewModel.goInto(entry)
                                "打开" -> {
                                    val path = relativePath
                                    val display = entry.displayName ?: entry.name
                                    when {
                                        isRadioName(entry.name) && isInRadiosTree(path) -> musicController.playAgentsRadio(path)
                                        isAudioName(entry.name) && isInMusicsTree(path) -> musicController.playAgentsMp3(path)
                                        isAudioName(entry.name) && isInNasSmbTree(path) ->
                                            SmbMediaActions.playNasSmbAudio(
                                                context = requireContext(),
                                                agentsPath = path,
                                                displayName = display,
                                                musicController = musicController,
                                            )
                                        isVideoName(entry.name) && isInNasSmbTree(path) -> {
                                            val ref =
                                                SmbMediaActions.createNasSmbVideoContent(
                                                    context = requireContext(),
                                                    agentsPath = path,
                                                    displayName = display,
                                                ) ?: return@setItems
                                            startActivity(
                                                VideoPlayerActivity.intentOf(
                                                    context = requireContext(),
                                                    uri = ref.uri,
                                                    displayName = display,
                                                    mime = ref.mime,
                                                    agentsPath = path,
                                                )
                                            )
                                        }
                                        isVideoName(entry.name) -> openAgentsVideoInternal(path, displayName = display)
                                        isImageName(entry.name) && isInNasSmbTree(path) -> {
                                            val ref =
                                                SmbMediaActions.createNasSmbImageContent(
                                                    context = requireContext(),
                                                    agentsPath = path,
                                                    displayName = display,
                                                ) ?: return@setItems
                                            startActivity(
                                                ImageViewerActivity.intentOf(
                                                    context = requireContext(),
                                                    uri = ref.uri,
                                                    displayName = display,
                                                    mime = ref.mime,
                                                    agentsPath = path,
                                                )
                                            )
                                        }
                                        isImageName(entry.name) -> openAgentsImageInternal(path, displayName = display)
                                        isPdfName(entry.name) && isInNasSmbTree(path) -> {
                                            val ref =
                                                SmbMediaActions.createNasSmbPdfContent(
                                                    context = requireContext(),
                                                    agentsPath = path,
                                                    displayName = display,
                                                ) ?: return@setItems
                                            startActivity(
                                                PdfViewerActivity.intentOf(
                                                    context = requireContext(),
                                                    uri = ref.uri,
                                                    displayName = display,
                                                    agentsPath = path,
                                                )
                                            )
                                        }
                                        isPdfName(entry.name) -> openAgentsPdfInternal(path, displayName = display)
                                        isOggName(entry.name) && isInRadioRecordingsTree(path) -> musicController.playAgentsRecordingOgg(path)
                                        shouldOpenExternalByDefault(entry.name) && isInNasSmbTree(path) ->
                                            SmbMediaActions.openNasSmbFileExternal(
                                                context = requireContext(),
                                                agentsPath = path,
                                                displayName = display,
                                                chooserTitle = "打开文件",
                                            )
                                        shouldOpenExternalByDefault(entry.name) -> openAgentsFileExternal(path, displayName = display, chooserTitle = "打开文件")
                                        else -> filesViewModel.openFile(entry)
                                    }
                                }
                                "播放" ->
                                    when {
                                        isRadioName(entry.name) && isInRadiosTree(relativePath) -> musicController.playAgentsRadio(relativePath)
                                        isAudioName(entry.name) && isInMusicsTree(relativePath) -> musicController.playAgentsMp3(relativePath)
                                        isOggName(entry.name) && isInRadioRecordingsTree(relativePath) -> musicController.playAgentsRecordingOgg(relativePath)
                                        else -> Unit
                                    }
                                "播放/暂停" -> musicController.togglePlayPause()
                                "停止" -> musicController.stop()
                                "收藏" -> addRadioFavorite(relativePath)
                                "移出收藏" -> removeRadioFavorite(relativePath)
                                "分享" -> shareAgentsFile(relativePath)
                                "剪切" -> {
                                    filesViewModel.cutEntry(entry)
                                    Toast.makeText(requireContext(), "已剪切：${entry.name}（到目标目录点“粘贴”）", Toast.LENGTH_SHORT).show()
                                }
                                "复制" -> {
                                    filesViewModel.copyEntry(entry)
                                    Toast.makeText(requireContext(), "已复制：${entry.name}（到目标目录点“粘贴”）", Toast.LENGTH_SHORT).show()
                                }
                                "重命名" -> {
                                    val input =
                                        android.widget.EditText(requireContext()).apply {
                                            setText(entry.name)
                                            setSelection(entry.name.length)
                                        }
                                    MaterialAlertDialogBuilder(requireContext())
                                        .setTitle("重命名")
                                        .setView(input)
                                        .setNegativeButton("取消", null)
                                        .setPositiveButton("确定") { _, _ ->
                                            val newName = input.text?.toString().orEmpty().trim()
                                            filesViewModel.renameEntry(entry, newName)
                                        }
                                        .show()
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
            if (st?.clipboardCutPath.isNullOrBlank() && st?.clipboardCopyPath.isNullOrBlank()) {
                Toast.makeText(requireContext(), "剪切/复制板为空", Toast.LENGTH_SHORT).show()
            } else {
                filesViewModel.pasteClipboardIntoCwd()
            }
            true
        }

        binding.buttonRefresh.setOnClickListener { filesViewModel.refresh(force = true) }
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
            if (st?.clipboardCutPath.isNullOrBlank() && st?.clipboardCopyPath.isNullOrBlank()) {
                Toast.makeText(requireContext(), "剪切/复制板为空", Toast.LENGTH_SHORT).show()
            } else {
                filesViewModel.pasteClipboardIntoCwd()
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
        binding.buttonMusicPrev.setOnClickListener {
            musicController.prev()
        }
        binding.buttonMusicNext.setOnClickListener {
            musicController.next()
        }
        binding.buttonMusicStop.setOnClickListener {
            musicController.stop()
        }
        binding.buttonMusicRecord.setOnClickListener {
            toggleRecordingFromUi()
        }
        binding.textMusicSubtitle.setOnClickListener {
            val cur = musicController.state.value.playbackMode
            val next =
                when (cur) {
                    com.lsl.kotlin_agent_app.media.MusicPlaybackMode.SequentialLoop -> com.lsl.kotlin_agent_app.media.MusicPlaybackMode.ShuffleLoop
                    com.lsl.kotlin_agent_app.media.MusicPlaybackMode.ShuffleLoop -> com.lsl.kotlin_agent_app.media.MusicPlaybackMode.RepeatOne
                    com.lsl.kotlin_agent_app.media.MusicPlaybackMode.RepeatOne -> com.lsl.kotlin_agent_app.media.MusicPlaybackMode.PlayOnce
                    com.lsl.kotlin_agent_app.media.MusicPlaybackMode.PlayOnce -> com.lsl.kotlin_agent_app.media.MusicPlaybackMode.SequentialLoop
                }
            musicController.cyclePlaybackMode()
            Toast.makeText(requireContext(), "播放模式：${modeLabel(next)}", Toast.LENGTH_SHORT).show()
        }
        binding.imageMusicCover.setOnClickListener {
            showMusicPlayerSheet(musicController)
        }
        binding.textMusicTitle.setOnClickListener {
            showMusicPlayerSheet(musicController)
        }

        filesViewModel.state.observe(viewLifecycleOwner) { st ->
            binding.textCwd.text = displayCwd(st.cwd)
            binding.textError.visibility = if (st.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
             binding.textError.text = st.errorMessage.orEmpty()
             adapter.submitList(st.entries)
             binding.buttonClearSessions.visibility = if (st.cwd == ".agents/sessions") View.VISIBLE else View.GONE
             binding.buttonPaste.visibility =
                 if (!st.clipboardCutPath.isNullOrBlank() || !st.clipboardCopyPath.isNullOrBlank()) {
                     View.VISIBLE
                 } else {
                     View.GONE
                 }
             val inMusics = isInMusicsTree(st.cwd)
             val inRadios = isInRadiosTree(st.cwd)
             binding.buttonMusicHelp.visibility = if (inMusics) View.VISIBLE else View.GONE
             binding.textMusicHint.visibility = if (inMusics || inRadios) View.VISIBLE else View.GONE
             if (inMusics) {
                 binding.textMusicHint.text = "仅 musics/ 启用 mp3 播放与 metadata；后台不断播可点右上角“排障”。"
             } else if (inRadios) {
                 binding.textMusicHint.text = "仅 radios/ 启用电台目录与 .radio 播放；长按 .radio 可收藏到 favorites/；点“刷新”可强制拉取目录。"
             }

              val openPath = st.openFilePath
              val openText = st.openFileText
              val isTruncated = st.openFileIsTruncated
              if (!openPath.isNullOrBlank() && openText != null) {
                 val desiredKind =
                    when {
                        isTranslationChunk(openPath, openText) -> EditorDialogKind.TranslationPreview
                        isMarkdownPath(openPath) -> EditorDialogKind.MarkdownPreview
                        isJsonPath(openPath) -> EditorDialogKind.PlainPreview
                        else -> EditorDialogKind.PlainPreview
                    }
                val shouldShow =
                    (editorDialog?.isShowing != true) ||
                        (editorDialogPath != openPath) ||
                        (editorDialogKind != desiredKind)

                if (shouldShow) {
                     when (desiredKind) {
                         EditorDialogKind.MarkdownPreview ->
                             showMarkdownPreview(openPath, openText, allowEdit = !isTruncated) { action ->
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
                             showPlainPreview(openPath, openText, allowEdit = !isTruncated) { action ->
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

                        EditorDialogKind.TranslationPreview ->
                            showTranslationPreview(openPath, openText) { action ->
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
                    updateMusicSheetIfVisible(st)
                    val cwd = filesViewModel.state.value?.cwd.orEmpty()
                    val err = st.errorMessage?.trim()?.ifBlank { null }
                    if (err != null && err != lastPlaybackErrorToast) {
                        lastPlaybackErrorToast = err
                        Toast.makeText(requireContext(), "播放失败：$err", Toast.LENGTH_SHORT).show()
                    }
                    if (err == null) lastPlaybackErrorToast = null

                    val inMusics = isInMusicsTree(cwd)
                    val inRadios = isInRadiosTree(cwd)
                    if (inMusics || inRadios) {
                        val base =
                            if (inMusics) {
                                "仅 musics/ 启用 mp3 播放与 metadata；后台不断播可点右上角“排障”。"
                            } else {
                                "仅 radios/ 启用电台目录与 .radio 播放；点“刷新”可强制拉取目录。"
                            }
                        val warn = st.warningMessage?.trim()?.ifBlank { null }
                        val lines = listOfNotNull(base, warn, err?.let { "错误：$it" })
                        binding.textMusicHint.text = lines.joinToString("\n").trim()
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

        val timeLabel =
            if (st.isLive) {
                "LIVE"
            } else {
                val pos = fmt(st.positionMs)
                val dur = st.durationMs?.let { fmt(it) }
                if (dur != null) "$pos / $dur" else pos
            }
        val artist = st.artist?.trim()?.ifBlank { null }
        val mode = modeLabel(st.playbackMode)
        binding.textMusicSubtitle.text =
            listOfNotNull(artist, mode, timeLabel).joinToString(" · ").ifBlank { timeLabel }

        binding.progressMusic.isIndeterminate = st.isLive
        val progress =
            if (!st.isLive && st.durationMs != null && st.durationMs > 0L) {
                ((st.positionMs.coerceIn(0L, st.durationMs) * 1000L) / st.durationMs).toInt().coerceIn(0, 1000)
            } else {
                0
            }
        binding.progressMusic.progress = progress

        binding.buttonMusicPlayPause.setImageResource(
            if (st.isPlaying) com.lsl.kotlin_agent_app.R.drawable.ic_pause_24 else com.lsl.kotlin_agent_app.R.drawable.ic_play_arrow_24
        )

        val canSkip = (st.queueSize ?: 0) > 1
        binding.buttonMusicPrev.isEnabled = canSkip
        binding.buttonMusicPrev.alpha = if (canSkip) 1.0f else 0.4f
        binding.buttonMusicNext.isEnabled = canSkip
        binding.buttonMusicNext.alpha = if (canSkip) 1.0f else 0.4f

        val cover = st.coverArtBytes
        if (cover != null && cover.isNotEmpty()) {
            if (lastCoverBytesRef !== cover) {
                lastCoverBytesRef = cover
                lastCoverBitmap =
                    runCatching { BitmapFactory.decodeByteArray(cover, 0, cover.size) }.getOrNull()
            }
            binding.imageMusicCover.imageTintList = null
            binding.imageMusicCover.setImageBitmap(lastCoverBitmap)
        } else {
            lastCoverBytesRef = null
            lastCoverBitmap = null
            binding.imageMusicCover.setImageResource(com.lsl.kotlin_agent_app.R.drawable.ic_insert_drive_file_24)
            val tint = MaterialColors.getColor(binding.imageMusicCover, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.GRAY)
            binding.imageMusicCover.imageTintList = ColorStateList.valueOf(tint)
        }

        updateRecordingButtons(st)
    }

    private fun modeLabel(mode: com.lsl.kotlin_agent_app.media.MusicPlaybackMode): String {
        return when (mode) {
            com.lsl.kotlin_agent_app.media.MusicPlaybackMode.ShuffleLoop -> "随机循环"
            com.lsl.kotlin_agent_app.media.MusicPlaybackMode.SequentialLoop -> "顺序循环"
            com.lsl.kotlin_agent_app.media.MusicPlaybackMode.RepeatOne -> "单曲循环"
            com.lsl.kotlin_agent_app.media.MusicPlaybackMode.PlayOnce -> "播放一次"
        }
    }

    private fun showMusicPlayerSheet(musicController: com.lsl.kotlin_agent_app.media.MusicPlayerController) {
        if (musicSheetDialog?.isShowing == true) return

        val dialog = BottomSheetDialog(requireContext())
        val sheet = BottomSheetMusicPlayerBinding.inflate(layoutInflater)

        sheet.sheetPrev.setOnClickListener { musicController.prev() }
        sheet.sheetPlayPause.setOnClickListener { musicController.togglePlayPause() }
        sheet.sheetNext.setOnClickListener { musicController.next() }
        sheet.sheetStop.setOnClickListener { musicController.stop() }
        sheet.sheetRecord.setOnClickListener { toggleRecordingFromUi() }

        sheet.sheetMode.setOnClickListener {
            val cur = musicController.state.value.playbackMode
            val next =
                when (cur) {
                    com.lsl.kotlin_agent_app.media.MusicPlaybackMode.SequentialLoop -> com.lsl.kotlin_agent_app.media.MusicPlaybackMode.ShuffleLoop
                    com.lsl.kotlin_agent_app.media.MusicPlaybackMode.ShuffleLoop -> com.lsl.kotlin_agent_app.media.MusicPlaybackMode.RepeatOne
                    com.lsl.kotlin_agent_app.media.MusicPlaybackMode.RepeatOne -> com.lsl.kotlin_agent_app.media.MusicPlaybackMode.PlayOnce
                    com.lsl.kotlin_agent_app.media.MusicPlaybackMode.PlayOnce -> com.lsl.kotlin_agent_app.media.MusicPlaybackMode.SequentialLoop
                }
            musicController.cyclePlaybackMode()
            Toast.makeText(requireContext(), "播放模式：${modeLabel(next)}", Toast.LENGTH_SHORT).show()
        }

        sheet.sheetMute.setOnClickListener { musicController.toggleMute() }
        sheet.sheetVolumeSlider.addOnSliderTouchListener(
            object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    sheetIsSlidingVolume = true
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    sheetIsSlidingVolume = false
                }
            }
        )
        sheet.sheetVolumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) musicController.setVolume(value)
        }

        val lyricsAdapter = LyricsLineAdapter()
        sheet.sheetLyricsList.layoutManager = LinearLayoutManager(requireContext())
        sheet.sheetLyricsList.adapter = lyricsAdapter

        dialog.setContentView(sheet.root)
        dialog.setOnDismissListener {
            musicSheetDialog = null
            musicSheetBinding = null
            sheetLyricsAdapter = null
            sheetLyricsRaw = null
            sheetLyricsTimed = null
            sheetLastHighlightedIndex = -1
            sheetIsSlidingVolume = false
        }

        musicSheetDialog = dialog
        musicSheetBinding = sheet
        sheetLyricsAdapter = lyricsAdapter
        updateMusicSheetIfVisible(musicController.state.value)

        dialog.show()
    }

    private fun toggleRecordingFromUi() {
        val appContext = requireContext().applicationContext
        val ctrl = MusicPlayerControllerProvider.get()
        val st = ctrl.state.value
        val agentsPath = st.agentsPath?.trim()?.ifBlank { null }
        val isRadio = st.isLive && agentsPath != null && agentsPath.endsWith(".radio", ignoreCase = true) && isInRadiosTree(agentsPath)
        if (!isRadio) {
            Toast.makeText(requireContext(), "仅在播放电台时可录制", Toast.LENGTH_SHORT).show()
            return
        }

        val existingSessionId = recordingByAgentsPath[agentsPath]
        fun startRecording(recordOnly: Boolean, targetLang: String?) {
            viewLifecycleOwner.lifecycleScope.launch startRecordingLaunch@{
                try {
                    val cmd = RadioCommand(appContext)
                    val argv =
                        buildList {
                            add("radio")
                            add("record")
                            add("start")
                            if (recordOnly) {
                                add("--record_only")
                            } else if (!targetLang.isNullOrBlank()) {
                                add("--target_lang")
                                add(targetLang.trim())
                            }
                        }
                    val out = withContext(Dispatchers.IO) { cmd.run(argv, stdin = null) }
                    if (out.exitCode != 0) {
                        Toast.makeText(requireContext(), out.stderr.ifBlank { out.errorMessage ?: "录制失败" }, Toast.LENGTH_SHORT).show()
                        return@startRecordingLaunch
                    }

                    val sid: String? =
                        runCatching {
                            out.result
                                ?.jsonObject
                                ?.get("session_id")
                                ?.jsonPrimitive
                                ?.content
                                ?.trim()
                                ?.ifBlank { null }
                        }.getOrNull()
                    if (sid != null) {
                        recordingByAgentsPath[agentsPath] = sid
                        Toast.makeText(requireContext(), "开始录制：${sid.takeLast(6)}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "开始录制（无 session_id）", Toast.LENGTH_SHORT).show()
                    }
                    updateRecordingButtons(st)
                } catch (t: Throwable) {
                    Toast.makeText(requireContext(), "录制失败：${t.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (!existingSessionId.isNullOrBlank()) {
            viewLifecycleOwner.lifecycleScope.launch stopRecordingLaunch@{
                try {
                    val cmd = RadioCommand(appContext)
                    val out =
                        withContext(Dispatchers.IO) {
                            cmd.run(listOf("radio", "record", "stop", "--session", existingSessionId), stdin = null)
                        }
                    if (out.exitCode != 0) {
                        Toast.makeText(requireContext(), out.stderr.ifBlank { out.errorMessage ?: "停止录制失败" }, Toast.LENGTH_SHORT).show()
                        return@stopRecordingLaunch
                    }
                    recordingByAgentsPath.remove(agentsPath)
                    Toast.makeText(requireContext(), "已停止录制", Toast.LENGTH_SHORT).show()
                    updateRecordingButtons(st)
                } catch (t: Throwable) {
                    Toast.makeText(requireContext(), "停止录制失败：${t.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        val actions = arrayOf("仅录制（不转录/翻译）", "不翻译（仅转录）", "翻译（选择目标语言）")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("录制设置")
            .setItems(actions) { _, which ->
                when (actions.getOrNull(which)) {
                    "仅录制（不转录/翻译）" -> startRecording(recordOnly = true, targetLang = null)
                    "不翻译（仅转录）" -> startRecording(recordOnly = false, targetLang = null)
                    "翻译（选择目标语言）" ->
                        TranslationLanguagePickerDialog.show(requireContext()) { lang ->
                            startRecording(recordOnly = false, targetLang = lang.code)
                        }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateRecordingButtons(st: com.lsl.kotlin_agent_app.media.MusicNowPlayingState) {
        val agentsPath = st.agentsPath?.trim()?.ifBlank { null }
        val isRadio = st.isLive && agentsPath != null && agentsPath.endsWith(".radio", ignoreCase = true) && isInRadiosTree(agentsPath)
        val isRecordingCurrent = isRadio && recordingByAgentsPath.containsKey(agentsPath!!)

        binding.buttonMusicRecord.visibility = if (isRadio) View.VISIBLE else View.GONE
        binding.buttonMusicRecord.setImageResource(
            if (isRecordingCurrent) com.lsl.kotlin_agent_app.R.drawable.ic_stop else com.lsl.kotlin_agent_app.R.drawable.ic_record_24
        )
        binding.buttonMusicRecord.contentDescription = if (isRecordingCurrent) "停止录制" else "开始录制"

        val sheet = musicSheetBinding
        if (sheet != null && musicSheetDialog?.isShowing == true) {
            sheet.sheetRecord.visibility = if (isRadio) View.VISIBLE else View.GONE
            sheet.sheetRecord.setImageResource(
                if (isRecordingCurrent) com.lsl.kotlin_agent_app.R.drawable.ic_stop else com.lsl.kotlin_agent_app.R.drawable.ic_record_24
            )
            sheet.sheetRecord.contentDescription = if (isRecordingCurrent) "停止录制" else "开始录制"
        }
    }

    private fun showMicrophoneRecordingsRootMenu() {
        val actions = arrayOf("⏺ 开始录音")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🎙 recordings")
            .setItems(actions) { _, which ->
                when (actions.getOrNull(which)) {
                    "⏺ 开始录音" -> {
                        runCatching {
                            startActivity(com.lsl.kotlin_agent_app.recorder.RecorderActivity.intentStart(requireContext()))
                        }.onFailure { t ->
                            Toast.makeText(requireContext(), "打开失败：${t.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRadioRecordingsRootMenu() {
        fun startRecording(recordOnly: Boolean, targetLang: String?) {
            val appContext = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch radioRootStartLaunch@{
                try {
                    val cmd = RadioCommand(appContext)
                    val argv =
                        buildList {
                            add("radio")
                            add("record")
                            add("start")
                            if (recordOnly) {
                                add("--record_only")
                            } else if (!targetLang.isNullOrBlank()) {
                                add("--target_lang")
                                add(targetLang.trim())
                            }
                        }
                    val out = withContext(Dispatchers.IO) { cmd.run(argv, stdin = null) }
                    if (out.exitCode != 0) {
                        Toast.makeText(requireContext(), out.stderr.ifBlank { out.errorMessage ?: "录制失败" }, Toast.LENGTH_SHORT).show()
                        return@radioRootStartLaunch
                    }
                    Toast.makeText(requireContext(), "已开始电台录制", Toast.LENGTH_SHORT).show()
                    filesViewModel?.refresh(force = true)
                } catch (t: Throwable) {
                    Toast.makeText(requireContext(), "录制失败：${t.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val actions = arrayOf("仅录制（不转录/翻译）", "不翻译（仅转录）", "翻译（选择目标语言）")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("📻 radio_recordings")
            .setItems(actions) { _, which ->
                when (actions.getOrNull(which)) {
                    "仅录制（不转录/翻译）" -> startRecording(recordOnly = true, targetLang = null)
                    "不翻译（仅转录）" -> startRecording(recordOnly = false, targetLang = null)
                    "翻译（选择目标语言）" ->
                        TranslationLanguagePickerDialog.show(requireContext()) { lang ->
                            startRecording(recordOnly = false, targetLang = lang.code)
                        }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRecordingSessionTranscriptMenu(
        sessionRef: RecordingSessionRef,
        displayName: String,
    ) {
        val sid = sessionRef.sessionId.trim()
        if (sid.isBlank()) return
        val vm = filesViewModel ?: return
        val appContext = requireContext().applicationContext
        val ws = AgentsWorkspace(appContext)

        viewLifecycleOwner.lifecycleScope.launch {
            val (state, tasks, hasChunks) =
                withContext(Dispatchers.IO) {
                    val raw =
                        try {
                            if (!ws.exists(sessionRef.metaPath)) return@withContext Triple(null, emptyList(), false)
                            ws.readTextFile(sessionRef.metaPath, maxBytes = 256 * 1024)
                        } catch (_: Throwable) {
                            return@withContext Triple(null, emptyList(), false)
                        }
                    val meta =
                        try {
                            RecordingMetaV1.parse(raw)
                        } catch (_: Throwable) {
                            return@withContext Triple(null, emptyList(), false)
                        }
                    val loadedTasks =
                        try {
                            if (!ws.exists(sessionRef.transcriptTasksIndexPath)) emptyList()
                            else {
                                val idxRaw = ws.readTextFile(sessionRef.transcriptTasksIndexPath, maxBytes = 256 * 1024)
                                TranscriptTasksIndexV1.parse(idxRaw).tasks
                            }
                        } catch (_: Throwable) {
                            emptyList()
                        }

                    val hasOgg =
                        try {
                            ws.listDir(sessionRef.sessionDir)
                                .any { e ->
                                    e.type == AgentsDirEntryType.File &&
                                        e.name.trim().startsWith("chunk_", ignoreCase = true) &&
                                        e.name.trim().endsWith(".ogg", ignoreCase = true)
                                }
                        } catch (_: Throwable) {
                            false
                        }

                    Triple(meta.state.trim().lowercase(), loadedTasks, hasOgg)
                }

            val stillRecording = (state == "recording" || state == "pending")
            val hasAnyTasks = tasks.isNotEmpty()
            val failedTasks = tasks.filter { it.state == "failed" }
            val activeTasks = tasks.filter { it.state == "pending" || it.state == "running" }
            val primary = if (hasAnyTasks) "📝 重新转录" else "📝 开始转录"
            val actions =
                buildList {
                    add("▶ 播放")
                    add(primary)
                    add("🌐 转录+翻译")
                    add("🎧 双语播放")
                    if (failedTasks.isNotEmpty()) add("重跑失败")
                    add("✏️ 重命名")
                    add("进入目录")
                    add("删除会话")
                    add("复制路径")
                }.toTypedArray()

            val adapter =
                object : android.widget.ArrayAdapter<String>(requireContext(), android.R.layout.select_dialog_item, actions) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val v = super.getView(position, convertView, parent) as TextView
                        val label = actions.getOrNull(position).orEmpty()
                        if (label == "🎧 双语播放" && !hasChunks) {
                            v.setTextColor(MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSurfaceVariant))
                        }
                        return v
                    }
                }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(displayName)
                .setAdapter(adapter) { _, which ->
                    when (actions.getOrNull(which)) {
                        "▶ 播放" -> {
                            if (!hasChunks) {
                                Toast.makeText(requireContext(), "无录音文件", Toast.LENGTH_SHORT).show()
                                return@setAdapter
                            }
                            viewLifecycleOwner.lifecycleScope.launch playChunkLaunch@{
                                val chunkPath =
                                    withContext(Dispatchers.IO) {
                                        val rx = Regex("^chunk_(\\d{3})\\.ogg$", RegexOption.IGNORE_CASE)
                                        val names =
                                            runCatching { ws.listDir(sessionRef.sessionDir) }.getOrNull().orEmpty()
                                                .filter { it.type == AgentsDirEntryType.File }
                                                .mapNotNull { e ->
                                                    val m = rx.matchEntire(e.name.trim()) ?: return@mapNotNull null
                                                    val idx = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                                                    idx to e.name.trim()
                                                }
                                                .sortedBy { it.first }
                                        val first = names.firstOrNull()?.second ?: return@withContext null
                                        "${sessionRef.sessionDir}/$first"
                                    }
                                if (chunkPath == null) {
                                    Toast.makeText(requireContext(), "无录音文件", Toast.LENGTH_SHORT).show()
                                    return@playChunkLaunch
                                }
                                MusicPlayerControllerProvider.get().playAgentsRecordingOgg(chunkPath)
                            }
                        }
                        "✏️ 重命名" -> {
                            if (stillRecording) {
                                Toast.makeText(requireContext(), "录制中，暂不支持重命名。", Toast.LENGTH_SHORT).show()
                                return@setAdapter
                            }
                            viewLifecycleOwner.lifecycleScope.launch {
                                val meta =
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            if (!ws.exists(sessionRef.metaPath)) return@runCatching null
                                            val raw = ws.readTextFile(sessionRef.metaPath, maxBytes = 2L * 1024L * 1024L)
                                            RecordingMetaV1.parse(raw)
                                        }.getOrNull()
                                    }
                                val initial =
                                    meta?.title?.trim()?.ifBlank { null }
                                        ?: meta?.station?.name?.trim()?.ifBlank { null }
                                        ?: displayName
                                val input =
                                    com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
                                        setText(initial)
                                        setSelection(text?.length ?: 0)
                                    }
                                val box =
                                    com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
                                        hint = "名称"
                                        addView(input)
                                    }
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("重命名")
                                    .setView(box)
                                    .setNegativeButton("取消", null)
                                    .setPositiveButton("保存") { _, _ ->
                                        val nextTitle = input.text?.toString()?.trim().orEmpty()
                                        if (nextTitle.isBlank()) return@setPositiveButton
                                        viewLifecycleOwner.lifecycleScope.launch {
                                            withContext(Dispatchers.IO) {
                                                val raw = ws.readTextFile(sessionRef.metaPath, maxBytes = 2L * 1024L * 1024L)
                                                val prev = RecordingMetaV1.parse(raw)
                                                val store = com.lsl.kotlin_agent_app.radio_recordings.RadioRecordingsStore(ws, rootDir = sessionRef.rootDir)
                                                store.writeSessionMeta(sid, prev.copy(title = nextTitle, updatedAt = RecordingMetaV1.nowIso()))
                                            }
                                            filesViewModel?.refresh(force = true)
                                        }
                                    }
                                    .show()
                            }
                        }
                        "进入目录" -> vm.goTo(sessionRef.sessionDir)
                        "复制路径" -> copyTextToClipboard("path", sessionRef.sessionDir)
                        "删除会话" -> {
                            if (stillRecording) {
                                Toast.makeText(requireContext(), "录制中，无法删除。请先停止录制。", Toast.LENGTH_SHORT).show()
                                return@setAdapter
                            }
                            val warning =
                                if (activeTasks.isNotEmpty()) {
                                    "\n\n提示：检测到 ${activeTasks.size} 个转录任务仍在进行中，删除后这些任务会失败。"
                                } else {
                                    ""
                                }
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("删除确认")
                                .setMessage("将递归删除该录制会话目录下所有文件（录音/转录产物等），此操作不可恢复。确定删除吗？$warning")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("删除") { _, _ ->
                                    vm.deletePath(sessionRef.sessionDir, recursive = true)
                                }
                                .show()
                        }
                        "🎧 双语播放" -> {
                            if (!hasChunks) {
                                Toast.makeText(requireContext(), "无录音文件", Toast.LENGTH_SHORT).show()
                                return@setAdapter
                            }
                            MusicPlayerControllerProvider.get().stop()
                            runCatching {
                                startActivity(BilingualPlayerActivity.intentOf(requireContext(), sid))
                            }.onFailure { t ->
                                Toast.makeText(requireContext(), "打开失败：${t.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        "📝 开始转录", "📝 重新转录" -> {
                            if (stillRecording) {
                                Toast.makeText(requireContext(), "请先停止录制", Toast.LENGTH_SHORT).show()
                            } else {
                                promptSourceLangAndStartTranscript(sessionId = sid, mayOverwrite = hasAnyTasks)
                            }
                        }
                        "🌐 转录+翻译" -> {
                            if (stillRecording) {
                                Toast.makeText(requireContext(), "请先停止录制", Toast.LENGTH_SHORT).show()
                            } else {
                                TranslationLanguagePickerDialog.show(requireContext()) { lang ->
                                    startOfflinePipelineTranslation(sessionId = sid, targetLang = lang.code)
                                }
                            }
                        }
                        "重跑失败" -> {
                            if (stillRecording) {
                                Toast.makeText(requireContext(), "请先停止录制", Toast.LENGTH_SHORT).show()
                            } else {
                                promptRetryFailedTranscriptTask(sessionId = sid, failedTasks = failedTasks)
                            }
                        }
                    }
                }
                .show()
        }
    }

    private fun startOfflinePipelineTranslation(
        sessionId: String,
        targetLang: String,
    ) {
        val sid = sessionId.trim()
        val tgt = targetLang.trim().ifBlank { return }
        val appContext = requireContext().applicationContext
        val ws = AgentsWorkspace(appContext)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val ref = com.lsl.kotlin_agent_app.recordings.RecordingSessionResolver.resolve(ws, sid) ?: return@withContext
                    val raw = ws.readTextFile(ref.metaPath, maxBytes = 2L * 1024L * 1024L)
                    val meta = RecordingMetaV1.parse(raw)
                    val prev = meta.pipeline
                    val nextPipe =
                        (prev ?: RecordingMetaV1.Pipeline())
                            .copy(
                                targetLanguage = tgt,
                                transcriptState = prev?.transcriptState?.ifBlank { "pending" } ?: "pending",
                                translationState = "pending",
                                lastError = null,
                            )
                    val store = com.lsl.kotlin_agent_app.radio_recordings.RadioRecordingsStore(ws, rootDir = ref.rootDir)
                    store.writeSessionMeta(sid, meta.copy(updatedAt = RecordingMetaV1.nowIso(), pipeline = nextPipe))
                }
                RecordingPipelineManager(appContext = appContext).enqueue(sessionId = sid, targetLanguage = tgt, replace = false)
                Toast.makeText(requireContext(), "已开始：转录+翻译（$tgt）", Toast.LENGTH_SHORT).show()
                filesViewModel?.refresh(force = true)
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "启动失败：${t.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun promptRetryFailedTranscriptTask(
        sessionId: String,
        failedTasks: List<TranscriptTasksIndexV1.TaskEntry>,
    ) {
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        if (failedTasks.isEmpty()) {
            Toast.makeText(requireContext(), "没有失败任务", Toast.LENGTH_SHORT).show()
            return
        }

        val appContext = requireContext().applicationContext
        val items =
            failedTasks.map { t ->
                val lang = t.sourceLanguage?.trim()?.ifBlank { null } ?: "auto"
                "$lang · ${t.taskId}"
            }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("重跑失败任务")
            .setItems(items) { _, which ->
                val taskId = failedTasks.getOrNull(which)?.taskId?.trim().orEmpty()
                if (taskId.isBlank()) return@setItems
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val mgr = TranscriptTaskManager(appContext = appContext)
                        withContext(Dispatchers.IO) {
                            mgr.retry(sessionId = sid, taskId = taskId, replace = true)
                        }
                        Toast.makeText(requireContext(), "已重跑：$taskId", Toast.LENGTH_SHORT).show()
                        filesViewModel?.refresh(force = true)
                    } catch (t: TranscriptCliException) {
                        showTranscriptErrorAndMaybeGoSettings(t)
                    } catch (t: Throwable) {
                        Toast.makeText(requireContext(), "重跑失败：${t.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptSourceLangAndStartTranscript(
        sessionId: String,
        mayOverwrite: Boolean,
    ) {
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        val appContext = requireContext().applicationContext

        data class LangOpt(
            val code: String,
            val label: String,
        )

        val opts =
            listOf(
                LangOpt(code = "auto", label = "auto (Detect)"),
                LangOpt(code = "zh", label = "🇨🇳 Chinese"),
                LangOpt(code = "ja", label = "🇯🇵 Japanese"),
                LangOpt(code = "en", label = "🇺🇸 English"),
                LangOpt(code = "fr", label = "🇫🇷 French"),
                LangOpt(code = "ru", label = "🇷🇺 Russian"),
            )

        val labels = opts.map { it.label }.toTypedArray()
        var selectedIndex = 0

        val doStartWithLang: (Boolean, String) -> Unit = { force, langCode ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val mgr = TranscriptTaskManager(appContext = appContext)
                    val task =
                        withContext(Dispatchers.IO) {
                            mgr.start(sessionId = sid, sourceLanguage = langCode, force = force)
                        }
                    Toast.makeText(requireContext(), "已创建转录任务：${task.taskId}", Toast.LENGTH_SHORT).show()
                    filesViewModel?.refresh(force = true)
                } catch (t: TranscriptCliException) {
                    showTranscriptErrorAndMaybeGoSettings(t)
                } catch (t: Throwable) {
                    Toast.makeText(requireContext(), "转录失败：${t.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("开始转录")
            .setSingleChoiceItems(labels, 0) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("开始") { _, _ ->
                val langCode = opts.getOrNull(selectedIndex)?.code ?: "auto"
                if (!mayOverwrite) {
                    doStartWithLang(false, langCode)
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("覆盖确认")
                        .setMessage("已有转录结果，是否覆盖？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("覆盖") { _, _ -> doStartWithLang(true, langCode) }
                        .show()
                }
            }
            .show()
    }

    private fun showTranscriptErrorAndMaybeGoSettings(t: TranscriptCliException) {
        val msg = t.message?.trim().orEmpty()
        val looksLikeMissingKey = t.errorCode == "InvalidArgs" && msg.contains("DASHSCOPE_API_KEY", ignoreCase = true)
        if (!looksLikeMissingKey) {
            Toast.makeText(requireContext(), msg.ifBlank { "转录失败" }, Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("需要配置 ASR Key")
            .setMessage("请先在 Files 中编辑：workspace/recordings/.env（或 workspace/radio_recordings/.env）\n\n$msg")
            .setNegativeButton("知道了", null)
            .setPositiveButton("去打开") { _, _ ->
                runCatching { findNavController().navigate(com.lsl.kotlin_agent_app.R.id.navigation_dashboard) }
                filesViewModel?.goTo(".agents/workspace/recordings")
                filesViewModel?.openFile(
                    AgentsDirEntry(
                        name = ".env",
                        type = AgentsDirEntryType.File,
                    ),
                )
            }
            .show()
    }

    private fun updateMusicSheetIfVisible(st: com.lsl.kotlin_agent_app.media.MusicNowPlayingState) {
        val sheet = musicSheetBinding ?: return
        if (musicSheetDialog?.isShowing != true) return

        sheet.sheetTitle.text = st.title?.trim()?.ifBlank { null } ?: "unknown"
        val artist = st.artist?.trim()?.ifBlank { null }
        val album = st.album?.trim()?.ifBlank { null }
        sheet.sheetSubtitle.text = listOfNotNull(artist, album).joinToString(" · ")
        sheet.sheetMode.text = modeLabel(st.playbackMode)

        sheet.sheetPlayPause.setImageResource(
            if (st.isPlaying) com.lsl.kotlin_agent_app.R.drawable.ic_pause_24 else com.lsl.kotlin_agent_app.R.drawable.ic_play_arrow_24
        )

        val canSkip = (st.queueSize ?: 0) > 1
        sheet.sheetPrev.isEnabled = canSkip
        sheet.sheetPrev.alpha = if (canSkip) 1.0f else 0.4f
        sheet.sheetNext.isEnabled = canSkip
        sheet.sheetNext.alpha = if (canSkip) 1.0f else 0.4f

        sheet.sheetMute.setImageResource(
            if (st.isMuted) com.lsl.kotlin_agent_app.R.drawable.ic_volume_off_24 else com.lsl.kotlin_agent_app.R.drawable.ic_volume_up_24
        )
        if (!sheetIsSlidingVolume) {
            sheet.sheetVolumeSlider.value = st.volume.coerceIn(0f, 1f)
        }

        val cover = st.coverArtBytes
        if (cover != null && cover.isNotEmpty()) {
            if (lastCoverBytesRef !== cover) {
                lastCoverBytesRef = cover
                lastCoverBitmap =
                    runCatching { BitmapFactory.decodeByteArray(cover, 0, cover.size) }.getOrNull()
            }
            sheet.sheetCover.imageTintList = null
            sheet.sheetCover.setImageBitmap(lastCoverBitmap)
        } else {
            sheet.sheetCover.setImageResource(com.lsl.kotlin_agent_app.R.drawable.ic_insert_drive_file_24)
            val tint = MaterialColors.getColor(sheet.sheetCover, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.GRAY)
            sheet.sheetCover.imageTintList = ColorStateList.valueOf(tint)
        }

        updateRecordingButtons(st)

        val rawLyrics = st.lyrics?.trim()?.ifBlank { null }
        if (rawLyrics == null) {
            sheet.sheetLyricsList.visibility = View.GONE
            sheet.sheetLyricsPlainContainer.visibility = View.GONE
            sheet.sheetLyricsEmpty.visibility = View.VISIBLE
            sheetLyricsRaw = null
            sheetLyricsTimed = null
            sheetLastHighlightedIndex = -1
            return
        }

        if (rawLyrics != sheetLyricsRaw) {
            sheetLyricsRaw = rawLyrics
            sheetLastHighlightedIndex = -1
            val timed = LrcParser.parseTimedLinesOrNull(rawLyrics)
            sheetLyricsTimed = timed
            if (timed != null) {
                sheet.sheetLyricsEmpty.visibility = View.GONE
                sheet.sheetLyricsPlainContainer.visibility = View.GONE
                sheet.sheetLyricsList.visibility = View.VISIBLE
                sheetLyricsAdapter?.submitLines(timed)
            } else {
                sheet.sheetLyricsEmpty.visibility = View.GONE
                sheet.sheetLyricsList.visibility = View.GONE
                sheet.sheetLyricsPlainContainer.visibility = View.VISIBLE
                sheet.sheetLyricsPlain.text = rawLyrics
            }
        }

        val timed = sheetLyricsTimed
        if (timed != null) {
            val idx = findLyricIndex(timed, st.positionMs)
            if (idx != sheetLastHighlightedIndex) {
                sheetLastHighlightedIndex = idx
                sheetLyricsAdapter?.setHighlightedIndex(idx)
                if (idx >= 0) {
                    sheet.sheetLyricsList.post {
                        sheet.sheetLyricsList.scrollToPosition(idx)
                    }
                }
            }
        }
    }

    private fun findLyricIndex(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        val p = positionMs.coerceAtLeast(0L)
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val t = lines[mid].timeMs
            if (t <= p) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun isAudioName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.endsWith(".mp3") ||
            n.endsWith(".m4a") ||
            n.endsWith(".aac") ||
            n.endsWith(".flac") ||
            n.endsWith(".wav") ||
            n.endsWith(".ogg") ||
            n.endsWith(".opus")
    }

    private fun isVideoName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.endsWith(".mp4") ||
            n.endsWith(".m4v") ||
            n.endsWith(".mkv") ||
            n.endsWith(".webm") ||
            n.endsWith(".mov") ||
            n.endsWith(".avi") ||
            n.endsWith(".3gp") ||
            n.endsWith(".ts") ||
            n.endsWith(".flv") ||
            n.endsWith(".wmv") ||
            n.endsWith(".mpg") ||
            n.endsWith(".mpeg")
    }

    private fun isPdfName(name: String): Boolean {
        return name.trim().lowercase().endsWith(".pdf")
    }

    private fun isImageName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.endsWith(".jpg") ||
            n.endsWith(".jpeg") ||
            n.endsWith(".png") ||
            n.endsWith(".webp") ||
            n.endsWith(".gif") ||
            n.endsWith(".bmp") ||
            n.endsWith(".heic") ||
            n.endsWith(".heif")
    }

    private fun isOfficeName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.endsWith(".doc") ||
            n.endsWith(".docx") ||
            n.endsWith(".xls") ||
            n.endsWith(".xlsx") ||
            n.endsWith(".ppt") ||
            n.endsWith(".pptx")
    }

    private fun isArchiveName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.endsWith(".zip") ||
            n.endsWith(".tar") ||
            n.endsWith(".gz") ||
            n.endsWith(".tgz") ||
            n.endsWith(".7z") ||
            n.endsWith(".rar")
    }

    private fun isTextLikeName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.endsWith(".txt") ||
            n.endsWith(".md") ||
            n.endsWith(".markdown") ||
            n.endsWith(".json") ||
            n.endsWith(".jsonl") ||
            n.endsWith(".yaml") ||
            n.endsWith(".yml") ||
            n.endsWith(".xml") ||
            n.endsWith(".ini") ||
            n.endsWith(".conf") ||
            n.endsWith(".properties") ||
            n.endsWith(".env") ||
            n.endsWith(".log") ||
            n.endsWith(".csv") ||
            n.endsWith(".tsv") ||
            n.endsWith(".kt") ||
            n.endsWith(".kts") ||
            n.endsWith(".java") ||
            n.endsWith(".py") ||
            n.endsWith(".js") ||
            n.endsWith(".ts") ||
            n.endsWith(".css") ||
            n.endsWith(".html") ||
            n.endsWith(".htm") ||
            n.endsWith(".sql") ||
            n.endsWith(".sh") ||
            n.endsWith(".bash") ||
            n.endsWith(".ps1") ||
            n.endsWith(".gradle") ||
            n.endsWith(".gitignore")
    }

    private fun shouldOpenExternalByDefault(name: String): Boolean {
        if (isOfficeName(name) || isArchiveName(name)) return true
        if (isAudioName(name) || isVideoName(name) || isImageName(name) || isPdfName(name) || isRadioName(name)) return false
        if (isTextLikeName(name)) return false
        val ext = name.trim().substringAfterLast('.', missingDelimiterValue = "").lowercase().trim()
        if (ext.isBlank()) return false
        return true
    }

    private fun isInNasSmbTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        if (p == ".agents/nas_smb") return true
        if (!p.startsWith(".agents/nas_smb/")) return false
        if (p.startsWith(".agents/nas_smb/secrets")) return false
        return true
    }

    private fun isInMusicsTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        return p == ".agents/workspace/musics" || p.startsWith(".agents/workspace/musics/")
    }

    private fun isOggName(name: String): Boolean {
        return name.trim().lowercase().endsWith(".ogg")
    }

    private fun isRadioName(name: String): Boolean {
        return name.trim().lowercase().endsWith(".radio")
    }

    private fun isInRadiosTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        return p == ".agents/workspace/radios" || p.startsWith(".agents/workspace/radios/")
    }

    private fun isInRadioRecordingsTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        return p == ".agents/workspace/radio_recordings" ||
            p.startsWith(".agents/workspace/radio_recordings/") ||
            p == ".agents/workspace/recordings" ||
            p.startsWith(".agents/workspace/recordings/")
    }

    private fun isInRadioFavorites(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        return p == ".agents/workspace/radios/favorites" || p.startsWith(".agents/workspace/radios/favorites/")
    }

    private fun isEnvName(name: String): Boolean {
        val n = name.trim().lowercase()
        if (n == ".env") return true
        if (n.startsWith(".env.")) return true
        return false
    }

    private fun addRadioFavorite(agentsRadioPath: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val appContext = requireContext().applicationContext
            val ws = AgentsWorkspace(appContext)
            try {
                val msg =
                    withContext(Dispatchers.IO) {
                        ws.ensureInitialized()
                        val raw = ws.readTextFile(agentsRadioPath, maxBytes = 128 * 1024)
                        val st = RadioStationFileV1.parse(raw)
                        val uuid = st.id.substringAfter(':', missingDelimiterValue = st.id)
                        val fileName = RadioPathNaming.stationFileName(stationName = st.name, stationUuid = uuid)
                        val dest = ".agents/workspace/radios/favorites/$fileName"
                        if (ws.exists(dest)) return@withContext "已在收藏：$fileName"
                        ws.writeTextFile(dest, raw.trimEnd() + "\n")
                        "已收藏：$fileName"
                    }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                filesViewModel?.refresh()
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "收藏失败：${t.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeRadioFavorite(agentsRadioPath: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val appContext = requireContext().applicationContext
            val ws = AgentsWorkspace(appContext)
            try {
                withContext(Dispatchers.IO) {
                    ws.ensureInitialized()
                    if (!isInRadioFavorites(agentsRadioPath)) error("仅允许从 favorites/ 移出收藏")
                    ws.deletePath(agentsRadioPath, recursive = false)
                }
                Toast.makeText(requireContext(), "已移出收藏", Toast.LENGTH_SHORT).show()
                filesViewModel?.refresh()
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "移出收藏失败：${t.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
            }
        }
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
        TranslationPreview,
    }

    private fun showTranslationPreview(path: String, rawJson: String, onAction: (EditorAction) -> Unit) {
        val chunk = runCatching { TranslationChunkV1.parse(rawJson) }.getOrNull()
        if (chunk == null) {
            showPlainPreview(path, rawJson, allowEdit = true, onAction = onAction)
            return
        }

        fun fmt(ms: Long): String {
            val v = ms.coerceAtLeast(0L)
            val totalSec = (v / 1000L).toInt()
            val m = totalSec / 60
            val s = totalSec % 60
            return "%02d:%02d".format(m, s)
        }

        val rendered =
            buildString {
                for (seg in chunk.segments) {
                    appendLine("[${fmt(seg.startMs)} - ${fmt(seg.endMs)}]")
                    appendLine(seg.sourceText.trim())
                    appendLine(seg.translatedText.trim())
                    appendLine()
                }
            }.trimEnd()

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
                text = rendered
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
                .setPositiveButton("编辑JSON") { _, _ -> onAction(EditorAction.Edit) }
                .create()
        dialog.setOnDismissListener { handleDialogDismiss(onAction, closeAction = EditorAction.Close) }
        editorDialog = dialog
        editorDialogPath = path
        editorDialogKind = EditorDialogKind.TranslationPreview
        dialog.show()
    }

    private fun isTranslationChunk(path: String, rawJson: String): Boolean {
        val p = path.lowercase()
        if (!p.endsWith(".translation.json")) return false
        return runCatching { TranslationChunkV1.parse(rawJson) }.isSuccess
    }

    private fun showPlainPreview(path: String, content: String, allowEdit: Boolean = true, onAction: (EditorAction) -> Unit) {
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
                text = buildPrettyHighlightedText(this, path = path, content = content)
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
                .apply {
                    if (allowEdit) {
                        setPositiveButton("编辑") { _, _ -> onAction(EditorAction.Edit) }
                    }
                }
                .create()
        dialog.setOnDismissListener { handleDialogDismiss(onAction, closeAction = EditorAction.Close) }
        editorDialog = dialog
        editorDialogPath = path
        editorDialogKind = EditorDialogKind.PlainPreview
        dialog.show()
    }

    private fun buildPrettyHighlightedText(
        tv: TextView,
        path: String,
        content: String,
    ): CharSequence {
        val p = path.trim().lowercase()
        val tooLargeForHighlight = content.length > 120_000

        if (tooLargeForHighlight) return content

        val onSurface = MaterialColors.getColor(tv, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK)
        val onSurfaceVariant =
            MaterialColors.getColor(
                tv,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                onSurface,
            )
        val primary = MaterialColors.getColor(tv, androidx.appcompat.R.attr.colorPrimary, onSurface)
        val secondary = MaterialColors.getColor(tv, com.google.android.material.R.attr.colorSecondary, onSurfaceVariant)
        val error = 0xFFB00020.toInt()

        if (p.endsWith(".json")) {
            val pretty = prettyJsonOrNull(content) ?: content
            return highlightJson(pretty, primary = primary, stringColor = secondary, numberColor = onSurfaceVariant, keywordColor = error)
        }
        if (p.endsWith(".jsonl")) {
            return highlightJson(content, primary = primary, stringColor = secondary, numberColor = onSurfaceVariant, keywordColor = error)
        }
        if (p.endsWith(".py")) {
            return highlightPython(content, keywordColor = primary, stringColor = secondary, commentColor = onSurfaceVariant, numberColor = error)
        }
        if (p.endsWith(".css")) {
            return highlightCss(content, selectorColor = primary, propColor = secondary, commentColor = onSurfaceVariant, literalColor = error)
        }
        return content
    }

    private fun prettyJsonOrNull(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return runCatching {
            val pretty =
                Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
            pretty.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), pretty.parseToJsonElement(trimmed))
        }.getOrNull()
    }

    private fun highlightJson(
        raw: String,
        primary: Int,
        stringColor: Int,
        numberColor: Int,
        keywordColor: Int,
    ): CharSequence {
        val sb = SpannableStringBuilder(raw)

        val stringRx = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
        val keyRx = Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)")
        val numberRx = Regex("(?<![\\w.])-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?(?![\\w.])")
        val keywordRx = Regex("\\b(true|false|null)\\b")

        applyColorSpans(sb, stringRx, stringColor)
        applyColorSpans(sb, numberRx, numberColor)
        applyColorSpans(sb, keywordRx, keywordColor, bold = true)
        applyColorSpans(sb, keyRx, primary, bold = true)
        return sb
    }

    private fun highlightPython(
        raw: String,
        keywordColor: Int,
        stringColor: Int,
        commentColor: Int,
        numberColor: Int,
    ): CharSequence {
        val sb = SpannableStringBuilder(raw)
        val stringRx =
            Regex(
                "(?s)('''.*?'''|\"\"\".*?\"\"\"|'([^'\\\\]|\\\\.)*'|\"([^\"\\\\]|\\\\.)*\")",
            )
        val commentRx = Regex("#.*$", setOf(RegexOption.MULTILINE))
        val numberRx = Regex("(?<![\\w.])\\d+(?:\\.\\d+)?(?![\\w.])")
        val kw =
            listOf(
                "and",
                "as",
                "assert",
                "break",
                "class",
                "continue",
                "def",
                "del",
                "elif",
                "else",
                "except",
                "False",
                "finally",
                "for",
                "from",
                "global",
                "if",
                "import",
                "in",
                "is",
                "lambda",
                "None",
                "nonlocal",
                "not",
                "or",
                "pass",
                "raise",
                "return",
                "True",
                "try",
                "while",
                "with",
                "yield",
            ).joinToString("|") { Regex.escape(it) }
        val keywordRx = Regex("\\b($kw)\\b")

        applyColorSpans(sb, stringRx, stringColor)
        applyColorSpans(sb, commentRx, commentColor)
        applyColorSpans(sb, numberRx, numberColor)
        applyColorSpans(sb, keywordRx, keywordColor, bold = true)
        return sb
    }

    private fun highlightCss(
        raw: String,
        selectorColor: Int,
        propColor: Int,
        commentColor: Int,
        literalColor: Int,
    ): CharSequence {
        val sb = SpannableStringBuilder(raw)
        val commentRx = Regex("(?s)/\\*.*?\\*/")
        val stringRx = Regex("'([^'\\\\]|\\\\.)*'|\"([^\"\\\\]|\\\\.)*\"")
        val hexRx = Regex("#[0-9a-fA-F]{3,8}\\b")
        val propRx = Regex("\\b([a-zA-Z_-][a-zA-Z0-9_-]*)\\s*:")
        val selectorRx = Regex("^\\s*([^\\{\\n]+)\\{", setOf(RegexOption.MULTILINE))

        applyColorSpans(sb, commentRx, commentColor)
        applyColorSpans(sb, stringRx, literalColor)
        applyColorSpans(sb, hexRx, literalColor)
        applyColorGroup1Spans(sb, propRx, propColor, bold = true)
        applyColorGroup1Spans(sb, selectorRx, selectorColor, bold = true)
        return sb
    }

    private fun applyColorSpans(
        sb: SpannableStringBuilder,
        rx: Regex,
        color: Int,
        bold: Boolean = false,
    ) {
        for (m in rx.findAll(sb)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (start >= end) continue
            sb.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) {
                sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun applyColorGroup1Spans(
        sb: SpannableStringBuilder,
        rx: Regex,
        color: Int,
        bold: Boolean = false,
    ) {
        for (m in rx.findAll(sb)) {
            val group = m.groups[1] ?: continue
            val range = group.range
            val start = range.first
            val end = range.last + 1
            if (start >= end) continue
            sb.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) {
                sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun showMarkdownPreview(path: String, text: String, allowEdit: Boolean = true, onAction: (EditorAction) -> Unit) {
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
                .apply {
                    if (allowEdit) {
                        setPositiveButton("编辑") { _, _ -> onAction(EditorAction.Edit) }
                    }
                }
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
        return p.endsWith(".json") || p.endsWith(".jsonl") || p.endsWith(".radio")
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

    private fun openAgentsImageExternal(
        agentsPath: String,
        displayName: String,
    ) {
        val rel = agentsPath.replace('\\', '/').trim()
        if (!rel.startsWith(".agents/")) return

        val file = File(requireContext().filesDir, rel)
        if (!file.exists() || !file.isFile) {
            Toast.makeText(requireContext(), "文件不存在：$displayName", Toast.LENGTH_SHORT).show()
            return
        }

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
                ?: "image/*"

        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(requireContext().contentResolver, file.name, uri)
            }

        try {
            startActivity(Intent.createChooser(intent, "打开图片"))
        } catch (t: Throwable) {
            Toast.makeText(requireContext(), t.message ?: "无法打开预览", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAgentsFileExternal(
        agentsPath: String,
        displayName: String,
        chooserTitle: String,
    ) {
        val rel = agentsPath.replace('\\', '/').trim()
        if (!rel.startsWith(".agents/")) return

        val file = File(requireContext().filesDir, rel)
        if (!file.exists() || !file.isFile) {
            Toast.makeText(requireContext(), "文件不存在：$displayName", Toast.LENGTH_SHORT).show()
            return
        }

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
                ?: guessMimeFromName(file.name)
                ?: URLConnection.guessContentTypeFromName(file.name)
                ?: "application/octet-stream"

        SmbMediaActions.openContentExternal(
            context = requireContext(),
            uri = uri,
            mime = mime,
            chooserTitle = chooserTitle,
        )
    }

    private fun guessMimeFromName(name: String): String? {
        val ext = name.trim().substringAfterLast('.', missingDelimiterValue = "").lowercase().trim()
        if (ext.isBlank()) return null
        return when (ext) {
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "tgz" -> "application/gzip"
            "7z" -> "application/x-7z-compressed"
            "rar" -> "application/vnd.rar"
            else -> null
        }
    }

    private fun openAgentsImageInternal(
        agentsPath: String,
        displayName: String,
    ) {
        val rel = agentsPath.replace('\\', '/').trim()
        if (!rel.startsWith(".agents/")) return

        val file = File(requireContext().filesDir, rel)
        if (!file.exists() || !file.isFile) {
            Toast.makeText(requireContext(), "文件不存在：$displayName", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = Uri.fromFile(file)
        val ext = file.extension.lowercase().takeIf { it.isNotBlank() }
        val mimeFromMap =
            ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        val mime =
            mimeFromMap
                ?: URLConnection.guessContentTypeFromName(file.name)
                ?: "image/*"

        startActivity(
            ImageViewerActivity.intentOf(
                context = requireContext(),
                uri = uri,
                displayName = displayName,
                mime = mime,
                agentsPath = agentsPath,
            )
        )
    }

    private fun openAgentsVideoInternal(
        agentsPath: String,
        displayName: String,
    ) {
        val rel = agentsPath.replace('\\', '/').trim()
        if (!rel.startsWith(".agents/")) return

        val file = File(requireContext().filesDir, rel)
        if (!file.exists() || !file.isFile) {
            Toast.makeText(requireContext(), "文件不存在：$displayName", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = Uri.fromFile(file)
        val mime = "video/*"

        startActivity(
            VideoPlayerActivity.intentOf(
                context = requireContext(),
                uri = uri,
                displayName = displayName,
                mime = mime,
                agentsPath = agentsPath,
            )
        )
    }

    private fun openAgentsPdfInternal(
        agentsPath: String,
        displayName: String,
    ) {
        val rel = agentsPath.replace('\\', '/').trim()
        if (!rel.startsWith(".agents/")) return

        val file = File(requireContext().filesDir, rel)
        if (!file.exists() || !file.isFile) {
            Toast.makeText(requireContext(), "文件不存在：$displayName", Toast.LENGTH_SHORT).show()
            return
        }

        val uri =
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file,
            )

        startActivity(
            PdfViewerActivity.intentOf(
                context = requireContext(),
                uri = uri,
                displayName = displayName,
                agentsPath = agentsPath,
            )
        )
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
