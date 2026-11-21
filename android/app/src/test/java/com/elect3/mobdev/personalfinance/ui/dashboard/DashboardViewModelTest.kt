package com.elect3.mobdev.personalfinance.ui.dashboard

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.domain.dashboard.LoadDashboardSummary
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.testutil.ControlledTransactionRepository
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val stores = mutableListOf<ViewModelStore>()
    private val repositories = mutableListOf<ControlledTransactionRepository>()
    private val row = TransactionFixtures.items.first()

    private fun repository(nonCooperative: Boolean = false) = ControlledTransactionRepository(nonCooperative)
        .also { repositories += it }

    private fun model(repository: ControlledTransactionRepository): DashboardViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, DashboardViewModel.Factory(LoadDashboardSummary(repository), YearMonth.of(2026, 11)))
            .get(DashboardViewModel::class.java)
    }

    private fun verify(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) {
        try { block() } finally {
            stores.forEach { it.clear() }
            repositories.flatMap { it.requests }.filterNot { it.isCompleted }.forEach { it.fail() }
            runCurrent()
        }
    }

    private fun ControlledTransactionRepository.Request.complete(items: List<Transaction> = listOf(row), total: Long = items.size.toLong()) =
        succeed(TransactionPage(items, page, pageSize, total))

    private fun content(vm: DashboardViewModel) = vm.state.value as DashboardUiState.Content

    @Test fun initialLoadingWaitsForCompleteSnapshotAndIgnoresDuplicateActions() = verify {
        val repo = repository()
        val vm = model(repo)
        assertEquals(DashboardUiState.InitialLoading, vm.state.value)
        vm.retry(); vm.refresh()
        runCurrent()
        assertEquals(1, repo.requests.size)
        repo.requests[0].complete(TransactionFixtures.items.take(20), 45)
        runCurrent()
        assertEquals(DashboardUiState.InitialLoading, vm.state.value)
        repo.requests[1].complete(TransactionFixtures.items.drop(20).take(20), 45)
        runCurrent()
        repo.requests[2].complete(TransactionFixtures.items.drop(40), 45)
        runCurrent()
        assertEquals(45L, content(vm).summary.recordCount)
        assertFalse(content(vm).isRefreshing)
    }

    @Test fun emptyCanRefreshToContent() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.last().complete(emptyList())
        runCurrent()
        assertEquals(DashboardUiState.Empty, vm.state.value)
        vm.refresh(); vm.refresh()
        runCurrent()
        assertEquals(2, repo.requests.size)
        repo.requests.last().complete()
        runCurrent()
        assertEquals(1L, content(vm).summary.recordCount)
    }

    @Test fun initialFailureRetriesOnceAndRecovers() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.last().fail()
        runCurrent()
        assertEquals(DashboardUiState.InitialFailure(DashboardError.Repository(TransactionFailure.Unavailable)), vm.state.value)
        vm.retry(); vm.retry()
        runCurrent()
        assertEquals(2, repo.requests.size)
        repo.requests.last().complete()
        runCurrent()
        assertEquals(1L, content(vm).summary.recordCount)
    }

    @Test fun invalidSnapshotHasDistinctErrorAndNoPartialContent() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.last().complete(listOf(row, row))
        runCurrent()
        assertEquals(DashboardUiState.InitialFailure(DashboardError.InvalidSnapshot), vm.state.value)
    }

    @Test fun sessionExpirationReplacesContentAndBlocksActions() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.last().complete()
        runCurrent()
        vm.refresh()
        runCurrent()
        repo.requests.last().fail(TransactionFailure.SessionExpired)
        runCurrent()
        assertEquals(DashboardUiState.SessionExpired, vm.state.value)
        vm.refresh(); vm.retry()
        runCurrent()
        assertEquals(2, repo.requests.size)
    }

    @Test fun initialSessionExpirationIsExplicit() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.last().fail(TransactionFailure.SessionExpired)
        runCurrent()
        assertEquals(DashboardUiState.SessionExpired, vm.state.value)
    }

    @Test fun refreshRetainsThenAtomicallyReplacesSummary() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.last().complete()
        runCurrent()
        val old = content(vm).summary
        vm.refresh(); vm.refresh(); vm.retry()
        assertSame(old, content(vm).summary)
        assertTrue(content(vm).isRefreshing)
        runCurrent()
        assertEquals(2, repo.requests.size)
        repo.requests.last().complete(listOf(TransactionFixtures.items[1]))
        runCurrent()
        assertFalse(content(vm).isRefreshing)
        assertNull(content(vm).refreshError)
        assertEquals(44, content(vm).summary.recentTransactions.single().transactionId)
    }

    @Test fun refreshFailureRetainsCompleteSummaryAndCanRetry() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.last().complete()
        runCurrent()
        val old = content(vm).summary
        vm.refresh()
        runCurrent()
        repo.requests.last().fail()
        runCurrent()
        assertSame(old, content(vm).summary)
        assertFalse(content(vm).isRefreshing)
        assertEquals(DashboardError.Repository(TransactionFailure.Unavailable), content(vm).refreshError)
        vm.refresh()
        assertNull(content(vm).refreshError)
        runCurrent()
        repo.requests.last().complete(emptyList())
        runCurrent()
        assertEquals(DashboardUiState.Empty, vm.state.value)
    }

    @Test fun invalidRefreshRetainsSummary() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.last().complete()
        runCurrent()
        val old = content(vm).summary
        vm.refresh()
        runCurrent()
        repo.requests.last().complete(listOf(row.copy(currencyCode = "USD")))
        runCurrent()
        assertSame(old, content(vm).summary)
        assertEquals(DashboardError.InvalidSnapshot, content(vm).refreshError)
    }

    @Test fun clearedOwnerCancelsRequestAndLateResponseCannotOverwriteState() = verify {
        val repo = repository(nonCooperative = true)
        val vm = model(repo)
        runCurrent()
        stores.last().clear()
        assertTrue(repo.requests.single().isCancelled)
        val cancelledRequest = repo.requests.single()
        // A fresh owner finishes first; a late old response must not overwrite either owner.
        val fresh = model(repo)
        runCurrent()
        repo.requests.last().complete(listOf(TransactionFixtures.items[1]))
        runCurrent()
        assertEquals(44, content(fresh).summary.recentTransactions.single().transactionId)
        cancelledRequest.complete()
        runCurrent()
        assertEquals(44, content(fresh).summary.recentTransactions.single().transactionId)
        assertEquals(DashboardUiState.InitialLoading, vm.state.value)
    }
}
