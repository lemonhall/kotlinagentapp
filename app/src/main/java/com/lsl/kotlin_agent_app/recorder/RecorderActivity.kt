package com.lsl.kotlin_agent_app.recorder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.databinding.ActivityRecorderBinding
import com.lsl.kotlin_agent_app.radio_recordings.RadioRecordingsStore
import com.lsl.kotlin_agent_app.radio_recordings.RecordingMetaV1
import com.lsl.kotlin_agent_app.recordings.RecordingRoots
import com.lsl.kotlin_agent_app.recordings.RecordingSessionResolver
import com.lsl.kotlin_agent_app.radio_transcript.RecordingPipelineManager
import com.lsl.kotlin_agent_app.ui.dashboard.TranslationLanguagePickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class RecorderActivity : ComponentActivity() {
    private lateinit var binding: ActivityRecorderBinding
    private var sessionId: String? = null
    private var state: String = "idle"
    private var saveDialogShown: Boolean = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "需要录音权限（RECORD_AUDIO）", Toast.LENGTH_SHORT).show()
                finish()
                return@registerForActivityResult
            }
            maybeStartNewSession()
        }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val i = intent ?: return
                if (i.action != RecorderService.ACTION_STATE) return
                val sid = i.getStringExtra(RecorderService.EXTRA_SESSION_ID)?.trim().orEmpty()
                if (sid.isBlank() || sid != sessionId) return
                val st = i.getStringExtra(RecorderService.EXTRA_STATE)?.trim().orEmpty()
                val elapsed = i.getLongExtra(RecorderService.EXTRA_ELAPSED_MS, 0L).coerceAtLeast(0L)
                val level = i.getFloatExtra(RecorderService.EXTRA_LEVEL_01, 0f).coerceIn(0f, 1f)
                state = st.ifBlank { state }
                renderState(elapsedMs = elapsed, level01 = level, state = st)
                if (st == "completed") {
                    showSaveDialogIfNeeded()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecorderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.trim()?.ifBlank { null }
        sessionId?.let { binding.textSession.text = it }

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.buttonPause.setOnClickListener {
            val sid = sessionId ?: return@setOnClickListener
            if (state == "paused") {
                RecorderService.requestResume(this, sid)
            } else {
                RecorderService.requestPause(this, sid)
            }
        }
        binding.buttonStop.setOnClickListener {
            val sid = sessionId ?: return@setOnClickListener
            RecorderService.requestStop(this, sid)
        }

        if (sessionId != null) {
            return
        }

        if (!hasRecordPermission()) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        maybeStartNewSession()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RecorderService.ACTION_STATE)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
        } catch (_: Throwable) {
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
        super.onStop()
    }

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun maybeStartNewSession() {
        if (sessionId != null) return
        lifecycleScope.launch {
            try {
                val ws = AgentsWorkspace(applicationContext)
                withContext(Dispatchers.IO) {
                    ws.ensureInitialized()
                    val store = RadioRecordingsStore(ws, rootDir = RecordingRoots.MICROPHONE_ROOT_DIR)
                    store.ensureRoot()
                    val sid = store.allocateSessionId(prefix = "rec")
                    ws.mkdir("${RecordingRoots.MICROPHONE_ROOT_DIR}/$sid")
                    val nowIso = RecordingMetaV1.nowIso()
                    val fmt = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
                    val title = "新录音_${fmt.format(Date())}"
                    val meta =
                        RecordingMetaV1(
                            schema = RecordingMetaV1.SCHEMA_V1,
                            sessionId = sid,
                            source = "microphone",
                            title = title,
                            station = null,
                            chunkDurationMin = 10,
                            outputFormat = "ogg_opus_128kbps",
                            state = "pending",
                            createdAt = nowIso,
                            updatedAt = nowIso,
                            chunks = emptyList(),
                            sampleRate = 48_000,
                            bitrate = 128_000,
                            durationMs = null,
                            pipeline = null,
                        )
                    store.writeSessionMeta(sid, meta)
                    store.writeSessionStatus(sid, ok = true, note = "pending")
                    sessionId = sid
                }

                val sid = sessionId ?: return@launch
                binding.textSession.text = sid
                RecorderService.requestStart(this@RecorderActivity, sid)
            } catch (t: Throwable) {
                Toast.makeText(this@RecorderActivity, "启动失败：${t.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun renderState(elapsedMs: Long, level01: Float, state: String) {
        binding.textTimer.text = formatHms(elapsedMs)
        binding.waveform.pushLevel(level01)
        val st =
            when (state) {
                "paused" -> "已暂停"
                "completed" -> "已停止"
                "recording" -> "录音中"
                else -> state.ifBlank { "—" }
            }
        binding.textStatus.text = st
        binding.buttonPause.setIconResource(
            if (state == "paused") com.lsl.kotlin_agent_app.R.drawable.ic_play_arrow_24 else com.lsl.kotlin_agent_app.R.drawable.ic_pause_24,
        )
        binding.buttonPause.text = if (state == "paused") "继续" else "暂停"
    }

    private fun showSaveDialogIfNeeded() {
        if (saveDialogShown) return
        saveDialogShown = true
        val sid = sessionId ?: return
        val ws = AgentsWorkspace(applicationContext)
        lifecycleScope.launch {
            val meta =
                withContext(Dispatchers.IO) {
                    val ref = RecordingSessionResolver.resolve(ws, sid) ?: return@withContext null
                    val raw = ws.readTextFile(ref.metaPath, maxBytes = 2L * 1024L * 1024L)
                    RecordingMetaV1.parse(raw)
                } ?: return@launch

            var selectedLang: String? = "zh"

            val input =
                com.google.android.material.textfield.TextInputEditText(this@RecorderActivity).apply {
                    setText(meta.title?.trim().orEmpty())
                    setSelection(text?.length ?: 0)
                }
            val box =
                com.google.android.material.textfield.TextInputLayout(this@RecorderActivity).apply {
                    hint = "录音名称"
                    addView(input)
                }

            val checkbox = com.google.android.material.checkbox.MaterialCheckBox(this@RecorderActivity).apply {
                text = "自动转录+翻译"
                isChecked = false
            }
            val langButton = com.google.android.material.button.MaterialButton(this@RecorderActivity).apply {
                text = "目标语言：${selectedLang ?: "—"}"
                isEnabled = false
                setOnClickListener {
                    TranslationLanguagePickerDialog.show(this@RecorderActivity) { lang ->
                        selectedLang = lang.code
                        text = "目标语言：${selectedLang ?: "—"}"
                    }
                }
            }
            checkbox.setOnCheckedChangeListener { _, checked ->
                langButton.isEnabled = checked
            }

            val container = android.widget.LinearLayout(this@RecorderActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val pad = (resources.displayMetrics.density * 16).toInt()
                setPadding(pad, pad, pad, pad)
                addView(box)
                addView(checkbox)
                addView(langButton)
            }

            MaterialAlertDialogBuilder(this@RecorderActivity)
                .setTitle("录制完成")
                .setView(container)
                .setNegativeButton("丢弃") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val ref = RecordingSessionResolver.resolve(ws, sid) ?: return@withContext
                            ws.deletePath(ref.sessionDir, recursive = true)
                        }
                        finish()
                    }
                }
                .setPositiveButton("保存") { _, _ ->
                    val title = input.text?.toString()?.trim()?.ifBlank { null }
                    val auto = checkbox.isChecked
                    val tgt = selectedLang?.trim()?.ifBlank { null }
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val ref = RecordingSessionResolver.resolve(ws, sid) ?: return@withContext
                            val raw = ws.readTextFile(ref.metaPath, maxBytes = 2L * 1024L * 1024L)
                            val prev = RecordingMetaV1.parse(raw)
                            val pipe =
                                if (!auto) {
                                    null
                                } else {
                                    RecordingMetaV1.Pipeline(
                                        targetLanguage = tgt ?: "zh",
                                        transcriptState = "pending",
                                        translationState = "pending",
                                        transcribedChunks = 0,
                                        translatedChunks = 0,
                                        failedChunks = 0,
                                        lastError = null,
                                    )
                                }
                            val store = RadioRecordingsStore(ws, rootDir = ref.rootDir)
                            store.writeSessionMeta(sid, prev.copy(title = title ?: prev.title, updatedAt = RecordingMetaV1.nowIso(), pipeline = pipe))
                        }
                        if (auto) {
                            RecordingPipelineManager(appContext = applicationContext).enqueue(sessionId = sid, targetLanguage = tgt ?: "zh", replace = false)
                        }
                        Toast.makeText(this@RecorderActivity, "已保存", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun formatHms(ms: Long): String {
        val total = (ms / 1000L).toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    companion object {
        private const val EXTRA_SESSION_ID = "session_id"

        fun intentStart(context: Context): Intent {
            return Intent(context, RecorderActivity::class.java)
        }

        fun intentOpen(
            context: Context,
            sessionId: String,
        ): Intent {
            return Intent(context, RecorderActivity::class.java).putExtra(EXTRA_SESSION_ID, sessionId)
        }
    }
}
