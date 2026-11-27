package com.elect3.mobdev.personalfinance.ui.reports

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.domain.reports.FinancialReportCalculator
import com.elect3.mobdev.personalfinance.domain.reports.FinancialReportResult
import com.elect3.mobdev.personalfinance.domain.reports.ReportTestData
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val nov = YearMonth.of(2026, 11)
    private val oct = YearMonth.of(2026, 10)
    private val stores = mutableListOf<ViewModelStore>()

    /** Each load waits for the test to answer it. */
    private class Loader {
        val calls = mutableListOf<Pair<YearMonth, CompletableDeferred<FinancialReportResult>>>()
        suspend fun load(month: YearMonth): FinancialReportResult =
            CompletableDeferred<FinancialReportResult>().also { calls += month to it }.await()
        fun answer(index: Int, result: FinancialReportResult) = calls[index].second.complete(result)
    }

    private fun success(month: YearMonth, vararg amounts: String) = FinancialReportResult.Success(FinancialReportCalculator().calculate(
        amounts.mapIndexed { i, a -> ReportTestData.expense(i + 1, a, month.atDay(1)) }, month, "PHP"))

    private fun model(loader: Loader, expired: () -> Unit = {}, current: () -> Boolean = { true }): ReportsViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, ReportsViewModel.Factory({ loader.load(it) }, { nov }, current, expired))
            .get(ReportsViewModel::class.java)
    }

    private fun verify(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) {
        try { block() } finally { stores.forEach { it.clear() }; runCurrent() }
    }

    @Test fun initialLoadingThenReadyForTheCurrentMonth() = verify {
        val loader = Loader(); val vm = model(loader)
        assertEquals(ReportsUiState.Loading(nov), vm.state.value)
        runCurrent()
        assertEquals(nov, loader.calls.single().first)
        loader.answer(0, success(nov, "12.00")); runCurrent()
        val ready = vm.state.value as ReportsUiState.Ready
        assertEquals(nov, ready.month)
        assertEquals(nov, ready.report.selected.month)
        assertFalse(vm.canGoForward)
        assertTrue(vm.canGoBack)
    }

    @Test fun changingMonthNeverShowsTheOldMonthsFiguresUnderTheNewLabel() = verify {
        val loader = Loader(); val vm = model(loader); runCurrent()
        loader.answer(0, success(nov, "12.00")); runCurrent()
        vm.previousMonth()
        assertEquals(ReportsUiState.Loading(oct), vm.state.value)
        runCurrent()
        // A late November answer for a superseded request is ignored.
        vm.nextMonth(); runCurrent()
        vm.previousMonth(); runCurrent()
        assertEquals(listOf(nov, oct, nov, oct), loader.calls.map { it.first })
        loader.answer(1, success(oct, "1.00")); runCurrent()
        assertEquals(ReportsUiState.Loading(oct), vm.state.value)
        loader.answer(3, success(oct, "3.00")); runCurrent()
        val ready = vm.state.value as ReportsUiState.Ready
        assertEquals(oct, ready.month)
        assertEquals(0, java.math.BigDecimal("3.00").compareTo(ready.report.selected.totalExpenses))
    }

    @Test fun futureMonthsAreClampedAndNextStopsAtTheCurrentMonth() = verify {
        val loader = Loader(); val vm = model(loader); runCurrent()
        loader.answer(0, success(nov)); runCurrent()
        vm.nextMonth(); vm.selectMonth(YearMonth.of(2027, 3)); runCurrent()
        assertEquals(1, loader.calls.size)
        assertEquals(nov, vm.state.value.month)
    }

    @Test fun emptyMonthIsReadyWithAnEmptyReport() = verify {
        val loader = Loader(); val vm = model(loader); runCurrent()
        loader.answer(0, success(nov)); runCurrent()
        val ready = vm.state.value as ReportsUiState.Ready
        assertTrue(ready.report.selected.isEmpty)
        assertTrue(ready.report.windowIsEmpty)
    }

    @Test fun errorThenRetrySucceeds() = verify {
        val loader = Loader(); val vm = model(loader); runCurrent()
        loader.answer(0, FinancialReportResult.RepositoryFailure(TransactionFailure.Unavailable)); runCurrent()
        assertEquals(ReportsUiState.Failure(nov, ReportsError.Unavailable), vm.state.value)
        vm.retry()
        assertEquals(ReportsUiState.Loading(nov), vm.state.value)
        runCurrent()
        loader.answer(1, success(nov, "5.00")); runCurrent()
        assertTrue(vm.state.value is ReportsUiState.Ready)
    }

    @Test fun invalidDataAndUnknownCurrencyAreDistinctFailures() = verify {
        val loader = Loader(); val vm = model(loader); runCurrent()
        loader.answer(0, FinancialReportResult.InvalidData("x")); runCurrent()
        assertEquals(ReportsUiState.Failure(nov, ReportsError.InvalidData), vm.state.value)
        vm.retry(); runCurrent()
        loader.answer(1, FinancialReportResult.CurrencyUnavailable); runCurrent()
        assertEquals(ReportsUiState.Failure(nov, ReportsError.CurrencyUnavailable), vm.state.value)
    }

    @Test fun sessionExpiryClearsFiguresNotifiesOnceAndStopsLoading() = verify {
        var expiries = 0
        val loader = Loader(); val vm = model(loader, expired = { expiries++ }); runCurrent()
        loader.answer(0, FinancialReportResult.RepositoryFailure(TransactionFailure.SessionExpired)); runCurrent()
        assertEquals(ReportsUiState.SessionExpired(nov), vm.state.value)
        assertEquals(1, expiries)
        vm.retry(); vm.refresh(); vm.previousMonth(); runCurrent()
        assertEquals(1, loader.calls.size)
    }

    @Test fun refreshFailureKeepsThisSessionsFiguresWithAnError() = verify {
        val loader = Loader(); val vm = model(loader); runCurrent()
        loader.answer(0, success(nov, "7.00")); runCurrent()
        vm.refresh()
        assertTrue((vm.state.value as ReportsUiState.Ready).isRefreshing)
        runCurrent()
        loader.answer(1, FinancialReportResult.RepositoryFailure(TransactionFailure.Unavailable)); runCurrent()
        val ready = vm.state.value as ReportsUiState.Ready
        assertEquals(ReportsError.Unavailable, ready.refreshError)
        assertFalse(ready.isRefreshing)
        // Unknown currency never keeps earlier figures.
        vm.refresh(); runCurrent()
        loader.answer(2, FinancialReportResult.CurrencyUnavailable); runCurrent()
        assertEquals(ReportsUiState.Failure(nov, ReportsError.CurrencyUnavailable), vm.state.value)
    }

    @Test fun logoutOrStaleSessionDropsLateResults() = verify {
        var current = true
        val loader = Loader(); val vm = model(loader, current = { current }); runCurrent()
        current = false
        loader.answer(0, success(nov, "9.00")); runCurrent()
        assertEquals(ReportsUiState.SessionExpired(nov), vm.state.value)
        val loader2 = Loader(); val vm2 = model(loader2); runCurrent()
        vm2.invalidateSession()
        loader2.answer(0, success(nov, "9.00")); runCurrent()
        assertEquals(ReportsUiState.SessionExpired(nov), vm2.state.value)
    }

    @Test fun loaderExceptionBecomesUnavailable() = verify {
        val store = ViewModelStore().also { stores += it }
        val vm = ViewModelProvider(store, ReportsViewModel.Factory({ error("boom") }, { nov })).get(ReportsViewModel::class.java)
        runCurrent()
        assertEquals(ReportsUiState.Failure(nov, ReportsError.Unavailable), vm.state.value)
    }

    @Test fun readyStateRejectsAMismatchedMonthLabel() {
        val report = (success(nov) as FinancialReportResult.Success).report
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ReportsUiState.Ready(oct, report) }
        assertEquals(LocalDate.of(2026, 11, 1), nov.atDay(1))
    }

    private fun snapshotModel(result: suspend () -> com.elect3.mobdev.personalfinance.domain.repository.FinanceResult<List<com.elect3.mobdev.personalfinance.domain.reports.MonthlySnapshot>>,
                              expired: () -> Unit = {}): ReportsViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, ReportsViewModel.Factory({ success(it) }, { nov }, onSessionExpired = expired, snapshots = result))
            .get(ReportsViewModel::class.java)
    }

    @Test fun snapshotsAreHiddenWithoutALoader() = verify {
        val vm = model(Loader()); runCurrent()
        assertEquals(SnapshotsUiState.Hidden, vm.snapshots.value)
    }

    @Test fun snapshotsEmptyReadyAndErrorStates() = verify {
        var next: com.elect3.mobdev.personalfinance.domain.repository.FinanceResult<List<com.elect3.mobdev.personalfinance.domain.reports.MonthlySnapshot>> =
            com.elect3.mobdev.personalfinance.domain.repository.FinanceResult.Success(emptyList())
        val vm = snapshotModel({ next })
        assertEquals(SnapshotsUiState.Loading, vm.snapshots.value)
        runCurrent()
        assertEquals(SnapshotsUiState.Ready(emptyList()), vm.snapshots.value)
        next = com.elect3.mobdev.personalfinance.domain.repository.FinanceResult.Failure(com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure.Unavailable)
        vm.retrySnapshots(); runCurrent()
        assertEquals(SnapshotsUiState.Failure(ReportsError.Unavailable), vm.snapshots.value)
        next = com.elect3.mobdev.personalfinance.domain.repository.FinanceResult.Failure(com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure.InvalidResponse)
        vm.retrySnapshots(); runCurrent()
        assertEquals(SnapshotsUiState.Failure(ReportsError.InvalidData), vm.snapshots.value)
        val item = com.elect3.mobdev.personalfinance.domain.reports.MonthlySnapshot(oct, java.math.BigDecimal("10.00"), java.math.BigDecimal("4.00"),
            java.math.BigDecimal("6.00"), java.math.BigDecimal.ZERO, java.time.Instant.parse("2026-11-01T00:30:00Z"), "PHP")
        next = com.elect3.mobdev.personalfinance.domain.repository.FinanceResult.Success(listOf(item))
        vm.refresh(); runCurrent()
        assertEquals(SnapshotsUiState.Ready(listOf(item)), vm.snapshots.value)
    }

    @Test fun snapshotSessionExpiryEndsTheReportSession() = verify {
        var expiries = 0
        val vm = snapshotModel({ com.elect3.mobdev.personalfinance.domain.repository.FinanceResult.Failure(
            com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure.SessionExpired) }, expired = { expiries++ })
        runCurrent()
        assertEquals(ReportsUiState.SessionExpired(nov), vm.state.value)
        assertEquals(SnapshotsUiState.Hidden, vm.snapshots.value)
        assertEquals(1, expiries)
    }
}
