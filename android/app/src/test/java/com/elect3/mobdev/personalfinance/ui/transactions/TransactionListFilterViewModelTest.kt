package com.elect3.mobdev.personalfinance.ui.transactions

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.mock.MockTransactionRepository
import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter
import com.elect3.mobdev.personalfinance.domain.repository.TransactionListSort
import com.elect3.mobdev.personalfinance.testutil.ControlledTransactionRepository
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Server-side filters flow through first page, load-more, refresh and reset; unsupported sources ignore them. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionListFilterViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val store = ViewModelStore()
    @After fun clear() = store.clear()

    private fun model(repo: com.elect3.mobdev.personalfinance.domain.repository.TransactionRepository = MockTransactionRepository()) =
        ViewModelProvider(store, TransactionListViewModel.Factory(repo)).get(TransactionListViewModel::class.java)
    private fun content(vm: TransactionListViewModel) = vm.state.value as TransactionListUiState.Content

    private fun loadAll(vm: TransactionListViewModel) {
        while (true) {
            val c = content(vm)
            if (c.append != TransactionAppendState.Ready) return
            vm.loadMore()
            main.dispatcher.scheduler.advanceUntilIdle()
        }
    }

    @Test fun filterReloadsFromPageOneAndLoadMoreStaysWithinTheFilteredSet() = runTest(main.dispatcher) {
        val vm = model(); advanceUntilIdle()
        vm.applyFilter(TransactionFilter(type = TransactionType.Expense)); advanceUntilIdle()
        val expected = TransactionFixtures.items.filter { it.type == TransactionType.Expense }
        assertEquals(expected.size.toLong(), content(vm).totalCount)
        loadAll(vm)
        val items = content(vm).items
        assertEquals(expected.size, items.size)
        assertTrue(items.all { it.type == TransactionType.Expense })
        assertEquals(TransactionAppendState.End, content(vm).append)
    }

    @Test fun refreshKeepsTheActiveFilter() = runTest(main.dispatcher) {
        val vm = model(); advanceUntilIdle()
        val filter = TransactionFilter(type = TransactionType.Income)
        vm.applyFilter(filter); advanceUntilIdle()
        vm.refresh(); advanceUntilIdle()
        assertEquals(filter, vm.activeFilter.value)
        assertTrue(content(vm).items.all { it.type == TransactionType.Income })
    }

    @Test fun resetRestoresTheFullDefaultList() = runTest(main.dispatcher) {
        val vm = model(); advanceUntilIdle()
        val fullTotal = content(vm).totalCount
        vm.applyFilter(TransactionFilter(type = TransactionType.Income)); advanceUntilIdle()
        vm.applyFilter(TransactionFilter()); advanceUntilIdle()
        assertTrue(vm.activeFilter.value.isDefault)
        assertEquals(fullTotal, content(vm).totalCount)
    }

    @Test fun oldestFirstKeepsServerOrderAcrossPages() = runTest(main.dispatcher) {
        val vm = model(); advanceUntilIdle()
        vm.applyFilter(TransactionFilter(sort = TransactionListSort.Oldest)); advanceUntilIdle()
        loadAll(vm)
        val dates = content(vm).items.map { it.transactionDate }
        assertEquals(dates.sorted(), dates)
    }

    @Test fun emptyFilteredResultIsEmptyStateNotAnError() = runTest(main.dispatcher) {
        val vm = model(); advanceUntilIdle()
        vm.applyFilter(TransactionFilter(fromDate = LocalDate.of(1999, 1, 1), toDate = LocalDate.of(1999, 1, 2))); advanceUntilIdle()
        assertEquals(TransactionListUiState.Empty, vm.state.value)
        assertTrue(vm.activeFilter.value.narrows)
    }

    @Test fun sourcesWithoutServerFilteringNeverPretendToFilter() = runTest(main.dispatcher) {
        val vm = model(ControlledTransactionRepository())
        assertFalse(vm.supportsFiltering)
        vm.applyFilter(TransactionFilter(type = TransactionType.Income))
        assertTrue(vm.activeFilter.value.isDefault)
    }
}
