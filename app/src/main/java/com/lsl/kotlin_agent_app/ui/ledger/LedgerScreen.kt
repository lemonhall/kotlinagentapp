package com.lsl.kotlin_agent_app.ui.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
) {
    val st by vm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.refresh()
    }

    var showAddDialog by remember { mutableStateOf(false) }

    if (st.needsInit && !st.confirmResetRequired) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("初始化账本") },
            text = { Text("检测到账本未初始化，是否一键初始化？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.initLedger(confirmReset = false)
                    },
                ) { Text("初始化") }
            },
            dismissButton = {
                TextButton(onClick = onBack) { Text("取消") }
            },
        )
    }

    if (st.confirmResetRequired) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("确认重置") },
            text = { Text("检测到账本目录非空，初始化将重置账本内容。继续吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.initLedger(confirmReset = true)
                    },
                ) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = onBack) { Text("取消") }
            },
        )
    }

    if (showAddDialog) {
        LedgerAddDialog(
            onDismiss = { showAddDialog = false },
            onSubmit = { type, amount, category, account, note, atIso ->
                showAddDialog = false
                if (type == "transfer") {
                    vm.addTransfer(
                        amountYuan = amount,
                        fromAccount = account,
                        toAccount = category,
                        note = note,
                        atIso = atIso,
                    )
                } else {
                    vm.addExpenseOrIncome(
                        type = type,
                        amountYuan = amount,
                        category = category,
                        account = account,
                        note = note,
                        atIso = atIso,
                    )
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账本") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
            )
        },
        floatingActionButton = {
            if (!st.needsInit) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Text("+")
                }
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp),
        ) {
            if (st.errorMessage != null) {
                Text(
                    text = st.errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }

            if (st.isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
                Spacer(Modifier.height(12.dp))
            }

            if (!st.needsInit) {
                SummaryCard(
                    month = st.selectedMonth,
                    by = st.summaryBy,
                    summary = st.summary,
                    onChangeMonth = { vm.setSelectedMonth(it) },
                    onChangeBy = { vm.setSummaryBy(it) },
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    text = "最近流水",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(st.transactions, key = { it.txId }) { tx ->
                        TransactionCard(tx)
                    }
                    if (st.transactions.isEmpty()) {
                        item {
                            Text(
                                text = "暂无流水（点右下角“+”记一笔）",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    month: String,
    by: String,
    summary: LedgerSummaryUi?,
    onChangeMonth: (String) -> Unit,
    onChangeBy: (String) -> Unit,
) {
    var monthInput by remember(month) { mutableStateOf(month) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "汇总", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = monthInput,
                    onValueChange = { monthInput = it },
                    label = { Text("月份（YYYY-MM）") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                TextButton(onClick = { onChangeMonth(monthInput) }) { Text("刷新") }
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "分组：", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onChangeBy("category") }) { Text(if (by == "category") "按分类 ✓" else "按分类") }
                TextButton(onClick = { onChangeBy("account") }) { Text(if (by == "account") "按账户 ✓" else "按账户") }
            }

            Spacer(Modifier.height(8.dp))
            if (summary == null) {
                Text(text = "暂无汇总", style = MaterialTheme.typography.bodyMedium)
            } else {
                val expYuan = fenToYuan(summary.expenseTotalFen)
                val incYuan = fenToYuan(summary.incomeTotalFen)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = "支出：¥$expYuan", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(text = "收入：¥$incYuan", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun TransactionCard(tx: LedgerTxUi) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            val sign =
                when (tx.type) {
                    "expense" -> "-"
                    "income" -> "+"
                    else -> ""
                }
            val title =
                when (tx.type) {
                    "expense" -> "支出"
                    "income" -> "收入"
                    "transfer" -> "转账"
                    else -> tx.type
                }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "$sign¥${tx.amountYuan}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))

            val line =
                when (tx.type) {
                    "transfer" -> "${tx.fromAccount.orEmpty()} → ${tx.toAccount.orEmpty()}"
                    else -> listOfNotNull(tx.category, tx.account).joinToString(" · ")
                }.ifBlank { "—" }
            Text(text = line, style = MaterialTheme.typography.bodyMedium)

            val note = tx.note?.trim().orEmpty()
            if (note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(text = note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = tx.atIso.replace('T', ' ').replace("Z", ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LedgerAddDialog(
    onDismiss: () -> Unit,
    onSubmit: (type: String, amount: String, category: String, account: String, note: String?, atIso: String?) -> Unit,
) {
    var type by remember { mutableStateOf("expense") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var atIso by remember { mutableStateOf("") }

    val categoryLabel = if (type == "transfer") "转入账户（to）" else "分类"
    val accountLabel = if (type == "transfer") "转出账户（from）" else "账户"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记一笔") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { type = "expense" }) { Text(if (type == "expense") "支出 ✓" else "支出") }
                    TextButton(onClick = { type = "income" }) { Text(if (type == "income") "收入 ✓" else "收入") }
                    TextButton(onClick = { type = "transfer" }) { Text(if (type == "transfer") "转账 ✓" else "转账") }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text(categoryLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text(accountLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = atIso,
                    onValueChange = { atIso = it },
                    label = { Text("时间 ISO（可选）") },
                    placeholder = { Text("例如 2026-02-18T12:00:00+08:00") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(
                        type,
                        amount.trim(),
                        category.trim(),
                        account.trim(),
                        note.trim().ifBlank { null },
                        atIso.trim().ifBlank { null },
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun fenToYuan(fen: Long): String {
    val sign = if (fen < 0) "-" else ""
    val abs = kotlin.math.abs(fen)
    val yuan = abs / 100
    val rest = abs % 100
    return sign + "%d.%02d".format(yuan, rest)
}
