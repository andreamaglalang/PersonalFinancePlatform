package com.elect3.mobdev.personalfinance.ui.transactions

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionRepository
import com.elect3.mobdev.personalfinance.testutil.ControlledTransactionRepository
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionListViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val stores = mutableListOf<ViewModelStore>()
    private val repositories = mutableListOf<ControlledTransactionRepository>()

    private fun repository(nonCooperative: Boolean = false) =
        ControlledTransactionRepository(nonCooperative).also { repositories += it }

    private fun model(
        repository: TransactionRepository,
        replacement: (() -> TransactionRepository)? = null,
    ): TransactionListViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, TransactionListViewModel.Factory(repository, replacement))
            .get(TransactionListViewModel::class.java)
    }

    /** Cleanup is inside runTest and finishes before MainDispatcherRule resets Main. */
    private fun verify(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) {
        try {
            block()
        } finally {
            stores.forEach { it.clear() }
            // Release even deliberately non-cooperative producers after assertion failures.
            repositories.flatMap { it.requests }.filterNot { it.isCompleted }.forEach { it.fail() }
            runCurrent()
        }
    }

    private fun content(vm: TransactionListViewModel): TransactionListUiState.Content {
        assertTrue(vm.state.value is TransactionListUiState.Content)
        return vm.state.value as TransactionListUiState.Content
    }

    private fun ids(vm: TransactionListViewModel) = content(vm).items.map { it.transactionId }

    private fun ControlledTransactionRepository.Request.complete(
        items: List<Transaction> = TransactionFixtures.items.take(20),
        total: Long = 45L,
    ) = succeed(TransactionPage(items, page, pageSize, total))

    private fun assertRequest(request: ControlledTransactionRepository.Request, page: Int) {
        assertEquals(page, request.page)
        assertEquals(20, request.pageSize)
    }

    @Test
    fun initialLoadingRemainsObservableUntilFirstPageCompletes() = verify {
        val repo = repository()
        val vm = model(repo)
        assertEquals(TransactionListUiState.InitialLoading, vm.state.value)
        runCurrent()
        assertRequest(repo.requests.single(), 1)
        assertEquals(TransactionListUiState.InitialLoading, vm.state.value)
        repo.requests.single().complete()
        runCurrent()
        assertEquals((45 downTo 26).toList(), ids(vm))
        assertEquals(1, content(vm).lastSuccessfulPage)
        assertEquals(45L, content(vm).totalCount)
        assertEquals(TransactionAppendState.Ready, content(vm).append)
        assertFalse(content(vm).isRefreshing)
        assertNull(content(vm).refreshError)
    }

    @Test
    fun emptyFirstPageProducesEmptyAndRefreshCanReloadIt() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.single().complete(emptyList(), 0)
        runCurrent()
        assertEquals(TransactionListUiState.Empty, vm.state.value)
        vm.refresh()
        runCurrent()
        assertRequest(repo.requests.last(), 1)
        assertEquals(TransactionListUiState.InitialLoading, vm.state.value)
    }

    @Test
    fun initialFailureRetriesPageOneWithoutDuplicateRequests() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.single().fail()
        runCurrent()
        assertEquals(TransactionFailure.Unavailable, (vm.state.value as TransactionListUiState.InitialError).reason)
        vm.retryInitialLoad()
        vm.retryInitialLoad()
        runCurrent()
        assertEquals(2, repo.requests.size)
        assertRequest(repo.requests.last(), 1)
        repo.requests.last().complete()
        runCurrent()
        assertEquals(20, content(vm).items.size)
    }

    @Test
    fun refreshRetainsRowsThenReplacesThemAndRejectsRepeatedRefresh() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.single().complete()
        runCurrent()
        val oldIds = ids(vm)
        vm.refresh()
        vm.refresh()
        vm.loadMore()
        assertEquals(oldIds, ids(vm))
        assertTrue(content(vm).isRefreshing)
        runCurrent()
        assertEquals(2, repo.requests.size)
        assertRequest(repo.requests.last(), 1)
        repo.requests.last().complete(TransactionFixtures.items.takeLast(2), 2)
        runCurrent()
        assertEquals(listOf(2, 1), ids(vm))
        assertEquals(1, content(vm).lastSuccessfulPage)
        assertEquals(2L, content(vm).totalCount)
        assertFalse(content(vm).isRefreshing)
        assertEquals(TransactionAppendState.End, content(vm).append)
    }

    @Test
    fun refreshFailurePreservesContentAndCanBeRetried() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.single().complete()
        runCurrent()
        val before = ids(vm)
        vm.refresh()
        runCurrent()
        repo.requests.last().fail()
        runCurrent()
        assertEquals(before, ids(vm))
        assertEquals(1, content(vm).lastSuccessfulPage)
        assertEquals(45L, content(vm).totalCount)
        assertEquals(TransactionFailure.Unavailable, content(vm).refreshError)
        assertFalse(content(vm).isRefreshing)
        vm.refresh()
        assertNull(content(vm).refreshError)
        runCurrent()
        repo.requests.last().complete()
        runCurrent()
        assertNull(content(vm).refreshError)
    }

    @Test
    fun appendDeduplicatesSortsAndStopsAtUniqueTotal() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.single().complete(TransactionFixtures.items.take(2), 3)
        runCurrent()
        vm.loadMore()
        vm.loadMore()
        vm.retryAppend()
        assertEquals(TransactionAppendState.Loading, content(vm).append)
        assertEquals(1, content(vm).lastSuccessfulPage)
        runCurrent()
        assertEquals(2, repo.requests.size)
        assertRequest(repo.requests.last(), 2)
        repo.requests.last().complete(listOf(TransactionFixtures.items[2], TransactionFixtures.items[1]), 3)
        runCurrent()
        assertEquals(listOf(45, 44, 43), ids(vm))
        assertEquals(2, content(vm).lastSuccessfulPage)
        assertEquals(3L, content(vm).totalCount)
        assertEquals(TransactionAppendState.End, content(vm).append)
        vm.loadMore()
        vm.retryAppend()
        runCurrent()
        assertEquals(2, repo.requests.size)
    }

    @Test
    fun appendFailureRetainsRowsAndRetriesSamePage() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.single().complete()
        runCurrent()
        val before = ids(vm)
        vm.loadMore()
        runCurrent()
        repo.requests.last().fail()
        runCurrent()
        assertEquals(before, ids(vm))
        assertEquals(1, content(vm).lastSuccessfulPage)
        assertEquals(TransactionFailure.Unavailable, (content(vm).append as TransactionAppendState.Error).reason)
        vm.loadMore() // Error requires the explicit retry action.
        vm.retryAppend()
        vm.retryAppend()
        runCurrent()
        assertEquals(listOf(1, 2, 2), repo.requests.map { it.page })
        repo.requests.last().complete(TransactionFixtures.items.drop(20).take(20))
        runCurrent()
        assertEquals(40, content(vm).items.size)
        assertEquals(2, content(vm).lastSuccessfulPage)
        assertEquals(TransactionAppendState.Ready, content(vm).append)
    }

    @Test
    fun emptyAppendEndsPaginationEvenWhenReportedTotalIsLarger() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.single().complete()
        runCurrent()
        val before = ids(vm)
        vm.loadMore()
        runCurrent()
        repo.requests.last().complete(emptyList())
        runCurrent()
        assertEquals(before, ids(vm))
        assertEquals(2, content(vm).lastSuccessfulPage)
        assertEquals(45L, content(vm).totalCount)
        assertEquals(TransactionAppendState.End, content(vm).append)
        vm.loadMore()
        runCurrent()
        assertEquals(2, repo.requests.size)
    }

    @Test
    fun refreshCancelsAppendAndLateResultsCannotOverwriteRefreshedContent() = verify {
        for (nonCooperative in listOf(false, true)) {
            val repo = repository(nonCooperative)
            val vm = model(repo)
            runCurrent()
            repo.requests.single().complete()
            runCurrent()
            vm.loadMore()
            runCurrent()
            val obsolete = repo.requests.last()
            vm.refresh()
            runCurrent()
            assertTrue(obsolete.isCancelled)
            assertEquals(listOf(1, 2, 1), repo.requests.map { it.page })
            repo.requests.last().complete(TransactionFixtures.items.takeLast(2), 2)
            runCurrent()
            val refreshed = vm.state.value
            obsolete.complete(TransactionFixtures.items.drop(20).take(20))
            runCurrent()
            assertSame(refreshed, vm.state.value)
            assertEquals(listOf(2, 1), ids(vm))
            assertEquals(1, content(vm).lastSuccessfulPage)
        }
    }

    @Test
    fun sessionExpirationReplacesInitialAppendAndRefreshStates() = verify {
        for (operation in listOf("initial", "append", "refresh")) {
            val repo = repository()
            val vm = model(repo, replacement = { repository() })
            runCurrent()
            if (operation != "initial") {
                repo.requests.single().complete()
                runCurrent()
                if (operation == "append") vm.loadMore() else vm.refresh()
                runCurrent()
            }
            repo.requests.last().fail(TransactionFailure.SessionExpired)
            runCurrent()
            assertEquals(DemoRestartState.Available, (vm.state.value as TransactionListUiState.SessionExpired).restart)
            val count = repo.requests.size
            vm.refresh()
            vm.loadMore()
            vm.retryInitialLoad()
            vm.retryAppend()
            runCurrent()
            assertEquals(count, repo.requests.size)
        }
    }

    @Test
    fun explicitRestartUsesFreshRepositoryAndReentersInitialLoading() = verify {
        val original = repository()
        val replacement = repository()
        var replacements = 0
        val vm = model(original) { replacements++; replacement }
        runCurrent()
        original.requests.single().fail(TransactionFailure.SessionExpired)
        runCurrent()
        assertEquals(0, replacements)
        vm.restartDemoSession()
        assertEquals(1, replacements)
        assertEquals(TransactionListUiState.InitialLoading, vm.state.value)
        vm.restartDemoSession()
        runCurrent()
        assertEquals(1, replacements)
        assertRequest(replacement.requests.single(), 1)
        replacement.requests.single().complete()
        runCurrent()
        assertEquals(1, content(vm).lastSuccessfulPage)
        assertEquals(1, original.requests.size)
    }

    @Test
    fun restartWithoutReplacementRemainsUnsupported() = verify {
        val repo = repository()
        val vm = model(repo)
        runCurrent()
        repo.requests.single().fail(TransactionFailure.SessionExpired)
        runCurrent()
        vm.restartDemoSession()
        runCurrent()
        assertEquals(DemoRestartState.Unsupported, (vm.state.value as TransactionListUiState.SessionExpired).restart)
        assertEquals(1, repo.requests.size)
    }

    @Test
    fun replacementFailureCanBeExplicitlyRetried() = verify {
        val original = repository()
        val replacement = repository()
        var attempts = 0
        val vm = model(original) {
            attempts++
            if (attempts == 1) throw IllegalStateException("Synthetic replacement failure")
            replacement
        }
        runCurrent()
        original.requests.single().fail(TransactionFailure.SessionExpired)
        runCurrent()
        vm.restartDemoSession()
        assertEquals(DemoRestartState.ReplacementFailed, (vm.state.value as TransactionListUiState.SessionExpired).restart)
        assertTrue(replacement.requests.isEmpty())
        vm.restartDemoSession()
        runCurrent()
        assertEquals(2, attempts)
        replacement.requests.single().complete()
        runCurrent()
        assertEquals(20, content(vm).items.size)
    }

    @Test
    fun replacementReturningSameInstanceIsRejected() = verify {
        val repo = repository()
        val vm = model(repo) { repo }
        runCurrent()
        repo.requests.single().fail(TransactionFailure.SessionExpired)
        runCurrent()
        vm.restartDemoSession()
        runCurrent()
        assertEquals(DemoRestartState.ReplacementFailed, (vm.state.value as TransactionListUiState.SessionExpired).restart)
        assertEquals(1, repo.requests.size)
    }

    @Test
    fun replacementCancellationPropagatesWithoutNormalFailureState() = verify {
        val repo = repository()
        val vm = model(repo) { throw CancellationException("Synthetic cancellation") }
        runCurrent()
        repo.requests.single().fail(TransactionFailure.SessionExpired)
        runCurrent()
        val before = vm.state.value
        assertThrows(CancellationException::class.java) { vm.restartDemoSession() }
        assertSame(before, vm.state.value)
    }

    @Test
    fun clearingStoreCancelsWorkAndRejectsLateCompletion() = verify {
        for (nonCooperative in listOf(false, true)) {
            val repo = repository(nonCooperative)
            val vm = model(repo)
            runCurrent()
            val pending = repo.requests.single()
            val before = vm.state.value
            stores.last().clear()
            runCurrent()
            assertTrue(pending.isCancelled)
            pending.complete()
            runCurrent()
            assertSame(before, vm.state.value)
        }
    }
}
