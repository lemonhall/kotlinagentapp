package com.lsl.kotlin_agent_app.ui.env_editor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EnvEditorViewModel(
    private val appContext: Context,
) : ViewModel() {

    private val ws = AgentsWorkspace(appContext)

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _rawText = MutableStateFlow("")
    val rawText: StateFlow<String> = _rawText

    private val _entries = MutableStateFlow<List<EnvEntry>>(emptyList())
    val entries: StateFlow<List<EnvEntry>> = _entries

    private var doc: EnvDocument? = null
    private var agentsPath: String? = null

    fun load(path: String) {
        agentsPath = path.trim()
        _isLoading.value = true
        _errorMessage.value = null
        viewModelScope.launch {
            try {
                val raw =
                    withContext(Dispatchers.IO) {
                        ws.ensureInitialized()
                        ws.readTextFile(path, maxBytes = 256 * 1024)
                    }
                val parsed = EnvParser.parse(raw)
                doc = parsed
                _rawText.value = raw
                _entries.value =
                    parsed.pairs()
                        .map { EnvEntry(key = it.key, value = it.value) }
                _isLoading.value = false
            } catch (t: Throwable) {
                _errorMessage.value = t.message ?: "load failed"
                _isLoading.value = false
            }
        }
    }

    fun updateEntry(index: Int, key: String? = null, value: String? = null) {
        val cur = _entries.value.toMutableList()
        val old = cur.getOrNull(index) ?: return
        cur[index] =
            EnvEntry(
                key = key ?: old.key,
                value = value ?: old.value,
            )
        _entries.value = cur
    }

    fun addEntry() {
        _entries.value = _entries.value + EnvEntry(key = "", value = "")
    }

    fun removeEntry(index: Int) {
        val cur = _entries.value.toMutableList()
        if (index !in cur.indices) return
        cur.removeAt(index)
        _entries.value = cur
    }

    fun saveForm(onDone: (ok: Boolean, msg: String) -> Unit) {
        val path = agentsPath ?: run {
            onDone(false, "缺少路径")
            return
        }
        val baseDoc = doc ?: run {
            onDone(false, "尚未加载")
            return
        }
        val entries = _entries.value
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val outText =
                    withContext(Dispatchers.Default) {
                        EnvRenderer.render(baseDoc, updatedPairs = entries)
                    }
                withContext(Dispatchers.IO) {
                    ws.writeTextFile(path, outText)
                }
                _rawText.value = outText
                doc = EnvParser.parse(outText)
                _isLoading.value = false
                onDone(true, "已保存")
            } catch (t: Throwable) {
                _isLoading.value = false
                onDone(false, t.message ?: "save failed")
            }
        }
    }

    fun saveRaw(raw: String, onDone: (ok: Boolean, msg: String) -> Unit) {
        val path = agentsPath ?: run {
            onDone(false, "缺少路径")
            return
        }
        viewModelScope.launch {
            try {
                _isLoading.value = true
                withContext(Dispatchers.IO) {
                    ws.writeTextFile(path, raw)
                }
                _rawText.value = raw
                val parsed = EnvParser.parse(raw)
                doc = parsed
                _entries.value = parsed.pairs().map { EnvEntry(it.key, it.value) }
                _isLoading.value = false
                onDone(true, "已保存")
            } catch (t: Throwable) {
                _isLoading.value = false
                onDone(false, t.message ?: "save failed")
            }
        }
    }

    class Factory(
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(EnvEditorViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return EnvEditorViewModel(appContext.applicationContext) as T
            }
            error("Unknown model: $modelClass")
        }
    }
}

