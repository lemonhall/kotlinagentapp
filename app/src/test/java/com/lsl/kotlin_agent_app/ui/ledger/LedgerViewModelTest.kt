package com.lsl.kotlin_agent_app.ui.ledger

import com.lsl.kotlin_agent_app.MainDispatcherRule
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun refresh_whenNotInitialized_setsNeedsInit() = runTest {
        val context = RuntimeEnvironment.getApplication()

        val ledgerDir = File(context.filesDir, ".agents/workspace/ledger")
        if (ledgerDir.exists()) ledgerDir.deleteRecursively()

        val vm = LedgerViewModel(appContext = context, ioDispatcher = Dispatchers.Main)
        vm.refresh()

        assertTrue(vm.uiState.value.needsInit)
    }

    @Test
    fun initLedger_createsWorkspaceFiles_andRefreshClearsNeedsInit() = runTest {
        val context = RuntimeEnvironment.getApplication()

        val ledgerDir = File(context.filesDir, ".agents/workspace/ledger")
        if (ledgerDir.exists()) ledgerDir.deleteRecursively()

        val vm = LedgerViewModel(appContext = context, ioDispatcher = Dispatchers.Main)
        vm.refresh()
        assertTrue(vm.uiState.value.needsInit)

        vm.initLedger(confirmReset = false)
        vm.refresh()

        assertFalse(vm.uiState.value.needsInit)
        assertTrue(File(context.filesDir, ".agents/workspace/ledger/meta.json").exists())
        assertTrue(File(context.filesDir, ".agents/workspace/ledger/categories.json").exists())
        assertTrue(File(context.filesDir, ".agents/workspace/ledger/accounts.json").exists())
        assertTrue(File(context.filesDir, ".agents/workspace/ledger/transactions.jsonl").exists())
    }
}

