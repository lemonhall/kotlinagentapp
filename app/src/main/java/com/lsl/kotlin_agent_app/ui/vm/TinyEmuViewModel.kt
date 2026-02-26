package com.lsl.kotlin_agent_app.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lemonhall.jediterm.android.tinyemu.RomManager
import com.lemonhall.jediterm.android.tinyemu.TinyEmuTtyConnector
import com.lsl.kotlin_agent_app.vm.VmMode
import com.lsl.kotlin_agent_app.vm.VmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class TinyEmuUiState(
    val isLoading: Boolean = false,
    val isBooted: Boolean = false,
    val errorMessage: String? = null,
    val profiles: List<RomManager.RomProfile> = emptyList(),
    val selectedProfileId: String? = null,
)

internal class TinyEmuViewModel(
    appContext: Context,
) : ViewModel() {
    private val ctx = appContext.applicationContext

    private val _uiState = MutableStateFlow(TinyEmuUiState())
    val uiState: StateFlow<TinyEmuUiState> = _uiState

    val connector: StateFlow<TinyEmuTtyConnector?> = VmService.connector

    fun start() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val cat = withContext(Dispatchers.IO) {
                    RomManager.ensureExtracted(ctx)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        profiles = cat.profiles,
                        selectedProfileId = cat.defaultProfileId,
                        isBooted = VmService.isRunning.value,
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "ROM 解压失败: ${t.message}")
                }
            }
        }
    }

    fun selectProfile(profileId: String) {
        _uiState.update { it.copy(selectedProfileId = profileId) }
    }

    @Suppress("UNUSED_PARAMETER")
    fun boot(columns: Int, rows: Int) {
        val profileId = _uiState.value.selectedProfileId ?: return
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        VmService.boot(ctx, profileId)
        // Observe connector from VmService; once it becomes non-null, we're booted
        viewModelScope.launch {
            VmService.connector.collect { conn ->
                if (conn != null) {
                    _uiState.update { it.copy(isLoading = false, isBooted = true) }
                    VmService.switchMode(VmMode.TERMINAL)
                    return@collect
                }
            }
        }
    }

    fun shutdown() {
        VmService.shutdown(ctx)
        _uiState.update { it.copy(isBooted = false) }
    }

    fun onTerminalVisible() {
        if (VmService.isRunning.value) VmService.switchMode(VmMode.TERMINAL)
    }

    fun onTerminalHidden() {
        if (VmService.mode.value == VmMode.TERMINAL) VmService.switchMode(VmMode.IDLE)
    }

    override fun onCleared() {
        super.onCleared()
        onTerminalHidden()
    }

    internal class Factory(
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TinyEmuViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return TinyEmuViewModel(appContext = appContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }
}