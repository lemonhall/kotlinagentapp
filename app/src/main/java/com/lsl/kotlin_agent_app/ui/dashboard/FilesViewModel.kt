package com.lsl.kotlin_agent_app.ui.dashboard

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lsl.kotlin_agent_app.agent.AgentsDirEntry
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FilesViewModel(
    private val workspace: AgentsWorkspace,
) : ViewModel() {

    private val _state = MutableLiveData(FilesUiState())
    val state: LiveData<FilesUiState> = _state

    fun refresh() {
        val prev = _state.value ?: FilesUiState()
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    workspace.ensureInitialized()
                }
                val cwd = (_state.value ?: prev).cwd
                val entries = withContext(Dispatchers.IO) { workspace.listDir(cwd) }
                _state.value = (_state.value ?: prev).copy(entries = entries, isLoading = false, errorMessage = null)
            } catch (t: Throwable) {
                _state.value = (_state.value ?: prev).copy(isLoading = false, errorMessage = t.message ?: "Unknown error")
            }
        }
    }

    fun goInto(entry: AgentsDirEntry) {
        if (entry.type != AgentsDirEntryType.Dir) return
        val prev = _state.value ?: FilesUiState()
        val nextCwd = workspace.joinPath(prev.cwd, entry.name)
        _state.value = prev.copy(cwd = nextCwd)
        refresh()
    }

    fun goUp() {
        val prev = _state.value ?: FilesUiState()
        val parent = workspace.parentDir(prev.cwd) ?: return
        _state.value = prev.copy(cwd = parent)
        refresh()
    }

    fun goRoot() {
        val prev = _state.value ?: FilesUiState()
        _state.value = prev.copy(cwd = ".agents")
        refresh()
    }

    fun openFile(entry: AgentsDirEntry) {
        if (entry.type != AgentsDirEntryType.File) return
        val prev = _state.value ?: FilesUiState()
        val filePath = workspace.joinPath(prev.cwd, entry.name)
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { workspace.readTextFile(filePath) }
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, openFilePath = filePath, openFileText = text)
            } catch (t: Throwable) {
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, errorMessage = t.message ?: "Open failed")
            }
        }
    }

    fun closeEditor() {
        val prev = _state.value ?: FilesUiState()
        _state.value = prev.copy(openFilePath = null, openFileText = null)
    }

    fun saveEditor(text: String) {
        val prev = _state.value ?: FilesUiState()
        val path = prev.openFilePath ?: return
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { workspace.writeTextFile(path, text) }
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, openFileText = text)
                refresh()
            } catch (t: Throwable) {
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, errorMessage = t.message ?: "Save failed")
            }
        }
    }

    fun createFile(name: String) {
        val prev = _state.value ?: FilesUiState()
        val filePath = workspace.joinPath(prev.cwd, name)
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { workspace.writeTextFile(filePath, "") }
                refresh()
            } catch (t: Throwable) {
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, errorMessage = t.message ?: "Create file failed")
            }
        }
    }

    fun createFolder(name: String) {
        val prev = _state.value ?: FilesUiState()
        val folderPath = workspace.joinPath(prev.cwd, name)
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { workspace.mkdir(folderPath) }
                refresh()
            } catch (t: Throwable) {
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, errorMessage = t.message ?: "Create folder failed")
            }
        }
    }

    fun deleteEntry(entry: AgentsDirEntry, recursive: Boolean) {
        val prev = _state.value ?: FilesUiState()
        val path = workspace.joinPath(prev.cwd, entry.name)
        _state.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { workspace.deletePath(path, recursive = recursive) }
                refresh()
            } catch (t: Throwable) {
                val now = _state.value ?: prev
                _state.value = now.copy(isLoading = false, errorMessage = t.message ?: "Delete failed")
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(FilesViewModel::class.java)) {
                return FilesViewModel(AgentsWorkspace(context)) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
