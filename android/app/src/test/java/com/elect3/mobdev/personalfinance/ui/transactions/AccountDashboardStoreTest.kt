package com.elect3.mobdev.personalfinance.ui.transactions

import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.testutil.ControlledTransactionRepository
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** M2: the account Dashboard lives and dies with the login scope. Fakes only. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountDashboardStoreTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = ControlledTransactionRepository()
    private val row = TransactionFixtures.items.first()

    private fun store(current: () -> Boolean = { true }) =
        AccountTransactionStore(repo, current, {}, accountCurrency = { "PHP" })

    private fun cleanup() = repo.requests.filterNot { it.isCompleted }.forEach { it.fail() }

    @Test fun dashboardIsLazyUsesFullPagesAndIsReusedWithinOneLogin() = runTest(main.dispatcher) {
        val s = store()
        try {
            runCurrent(); assertTrue(repo.requests.isEmpty())
            val first = s.dashboardModel(); runCurrent()
            assertEquals(100, repo.requests.single().pageSize)
            repo.requests.single().succeed(TransactionPage(listOf(row), 1, 100, 1)); runCurrent()
            assertTrue(first.state.value is DashboardUiState.Content)
            assertSame(first, s.dashboardModel())
        } finally { s.close(); cleanup(); runCurrent() }
    }

    @Test fun sessionChangeDropsDashboardFiguresImmediately() = runTest(main.dispatcher) {
        val s = store()
        try {
            val vm = s.dashboardModel(); runCurrent()
            repo.requests.single().succeed(TransactionPage(listOf(row), 1, 100, 1)); runCurrent()
            s.invalidateOnSessionChange(flowOf("another-login"), "this-login")
            assertEquals(DashboardUiState.SessionExpired, vm.state.value)
            s.refreshDashboard(); runCurrent(); assertEquals(1, repo.requests.size)
        } finally { s.close(); cleanup(); runCurrent() }
    }

    @Test fun closeErasesTheDashboardAndForbidsReuse() = runTest(main.dispatcher) {
        val s = store()
        val vm = s.dashboardModel(); runCurrent()
        repo.requests.single().succeed(TransactionPage(listOf(row), 1, 100, 1)); runCurrent()
        s.close()
        assertEquals(DashboardUiState.SessionExpired, vm.state.value)
        assertThrows(IllegalStateException::class.java) { s.dashboardModel() }
        cleanup(); runCurrent()
    }

    @Test fun refreshAfterSaveOnlyReloadsAnAlreadyOpenedDashboard() = runTest(main.dispatcher) {
        val s = store()
        try {
            s.refreshDashboard(); runCurrent(); assertTrue(repo.requests.isEmpty())
            s.dashboardModel(); runCurrent()
            repo.requests.single().succeed(TransactionPage(listOf(row), 1, 100, 1)); runCurrent()
            s.refreshDashboard(); runCurrent()
            assertEquals(2, repo.requests.size)
        } finally { s.close(); cleanup(); runCurrent() }
    }
}
