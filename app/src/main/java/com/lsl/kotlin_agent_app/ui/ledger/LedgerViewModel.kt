package com.lsl.kotlin_agent_app.ui.ledger

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.agent.tools.ledger.LedgerStore
import com.lsl.kotlin_agent_app.agent.tools.ledger.NotInitialized
import com.lsl.kotlin_agent_app.agent.tools.terminal.commands.archive.ConfirmRequired
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class LedgerUiState(
    val isLoading: Boolean = false,
    val needsInit: Boolean = false,
    val confirmResetRequired: Boolean = false,
    val errorMessage: String? = null,
    val selectedMonth: String = "",
    val summaryBy: String = "category",
    val summary: LedgerSummaryUi? = null,
    val transactions: List<LedgerTxUi> = emptyList(),
)

data class LedgerTxUi(
    val txId: String,
    val type: String,
    val amountYuan: String,
    val currency: String,
    val category: String?,
    val account: String?,
    val fromAccount: String?,
    val toAccount: String?,
    val note: String?,
    val atIso: String,
    val month: String,
)

data class LedgerSummaryUi(
    val month: String,
    val by: String,
    val expenseTotalFen: Long,
    val incomeTotalFen: Long,
    val groups: JsonArray,
)

class LedgerViewModel(
    appContext: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val appContext = appContext.applicationContext
    private val ioDispatcher = ioDispatcher
    private val workspace =
        AgentsWorkspace(this.appContext).also {
            it.ensureInitialized()
        }
    private val store = LedgerStore(agentsRoot = workspace.toFile(".agents"))

    private val _uiState = MutableStateFlow(LedgerUiState())
    val uiState: StateFlow<LedgerUiState> = _uiState

    init {
        if (_uiState.value.selectedMonth.isBlank()) {
            _uiState.value = _uiState.value.copy(selectedMonth = currentMonth())
        }
    }

    fun refresh() {
        val prev = _uiState.value
        _uiState.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { store.requireInitialized() }
                val month = _uiState.value.selectedMonth.ifBlank { currentMonth() }
                val by = _uiState.value.summaryBy.ifBlank { "category" }
                val list =
                    withContext(ioDispatcher) {
                        store.list(filters = LedgerStore.ListFilters(month = null, account = null, category = null), max = 100)
                    }
                val summary =
                    withContext(ioDispatcher) {
                        store.summary(month = month, by = by)
                    }
                val txUi =
                    list.emitted.mapNotNull { obj -> obj.toTxUiOrNull() }
                val now = _uiState.value
                _uiState.value =
                    now.copy(
                        isLoading = false,
                        needsInit = false,
                        confirmResetRequired = false,
                        selectedMonth = month,
                        summaryBy = by,
                        summary =
                            LedgerSummaryUi(
                                month = summary.month,
                                by = summary.by,
                                expenseTotalFen = summary.expenseTotalFen,
                                incomeTotalFen = summary.incomeTotalFen,
                                groups = summary.groups,
                            ),
                        transactions = txUi,
                    )
            } catch (_: NotInitialized) {
                val now = _uiState.value
                _uiState.value =
                    now.copy(
                        isLoading = false,
                        needsInit = true,
                        transactions = emptyList(),
                        summary = null,
                    )
            } catch (t: Throwable) {
                val now = _uiState.value
                _uiState.value =
                    now.copy(
                        isLoading = false,
                        errorMessage = t.message?.trim()?.ifBlank { "未知错误" } ?: "未知错误",
                    )
            }
        }
    }

    fun initLedger(confirmReset: Boolean) {
        val prev = _uiState.value
        _uiState.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { store.init(confirm = confirmReset) }
                refresh()
            } catch (_: ConfirmRequired) {
                val now = _uiState.value
                _uiState.value = now.copy(isLoading = false, needsInit = true, confirmResetRequired = true)
            } catch (t: Throwable) {
                val now = _uiState.value
                _uiState.value =
                    now.copy(
                        isLoading = false,
                        errorMessage = t.message?.trim()?.ifBlank { "未知错误" } ?: "未知错误",
                    )
            }
        }
    }

    fun setSelectedMonth(month: String) {
        val m = month.trim()
        val prev = _uiState.value
        _uiState.value = prev.copy(selectedMonth = m)
        refresh()
    }

    fun setSummaryBy(by: String) {
        val b = by.trim().ifBlank { "category" }
        val prev = _uiState.value
        _uiState.value = prev.copy(summaryBy = b)
        refresh()
    }

    fun addExpenseOrIncome(
        type: String,
        amountYuan: String,
        category: String,
        account: String,
        note: String?,
        atIso: String?,
    ) {
        val prev = _uiState.value
        _uiState.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    store.addExpenseOrIncome(
                        type = type,
                        amountYuanRaw = amountYuan,
                        category = category,
                        account = account,
                        note = note,
                        atIso = atIso,
                    )
                }
                refresh()
            } catch (t: Throwable) {
                val now = _uiState.value
                _uiState.value =
                    now.copy(
                        isLoading = false,
                        errorMessage = t.message?.trim()?.ifBlank { "未知错误" } ?: "未知错误",
                    )
            }
        }
    }

    fun addTransfer(
        amountYuan: String,
        fromAccount: String,
        toAccount: String,
        note: String?,
        atIso: String?,
    ) {
        val prev = _uiState.value
        _uiState.value = prev.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    store.addTransfer(
                        amountYuanRaw = amountYuan,
                        fromAccount = fromAccount,
                        toAccount = toAccount,
                        note = note,
                        atIso = atIso,
                    )
                }
                refresh()
            } catch (t: Throwable) {
                val now = _uiState.value
                _uiState.value =
                    now.copy(
                        isLoading = false,
                        errorMessage = t.message?.trim()?.ifBlank { "未知错误" } ?: "未知错误",
                    )
            }
        }
    }

    class Factory(
        private val appContext: Context,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LedgerViewModel::class.java)) {
                return LedgerViewModel(appContext = appContext, ioDispatcher = ioDispatcher) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: $modelClass")
        }
    }

    private fun currentMonth(): String {
        val now = OffsetDateTime.now()
        return "%04d-%02d".format(now.year, now.monthValue)
    }

    private fun JsonObject.toTxUiOrNull(): LedgerTxUi? {
        val txId = this["tx_id"]?.asString()?.trim().orEmpty()
        val type = this["type"]?.asString()?.trim().orEmpty()
        val amountYuan = this["amount_yuan"]?.asString()?.trim().orEmpty()
        val currency = this["currency"]?.asString()?.trim()?.ifBlank { "CNY" } ?: "CNY"
        val at = this["at"]?.asString()?.trim().orEmpty()
        val month = this["month"]?.asString()?.trim().orEmpty()
        if (txId.isBlank() || type.isBlank() || amountYuan.isBlank() || at.isBlank() || month.isBlank()) return null
        return LedgerTxUi(
            txId = txId,
            type = type,
            amountYuan = amountYuan,
            currency = currency,
            category = this["category"]?.asString(),
            account = this["account"]?.asString(),
            fromAccount = this["from_account"]?.asString(),
            toAccount = this["to_account"]?.asString(),
            note = this["note"]?.asString(),
            atIso = at,
            month = month,
        )
    }
}

private fun kotlinx.serialization.json.JsonElement.asString(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.let {
        try {
            it.content
        } catch (_: Throwable) {
            null
        }
    }
