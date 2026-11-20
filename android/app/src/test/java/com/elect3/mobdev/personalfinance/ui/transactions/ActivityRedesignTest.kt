package com.elect3.mobdev.personalfinance.ui.transactions

import com.elect3.mobdev.personalfinance.data.mock.MockTransactionRepository
import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Activity redesign: complete month reads, paged all time, and list lines that never misstate partial data. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityRedesignTest {
    @get:Rule val main = MainDispatcherRule()
    private val nov = YearMonth.of(2026, 11)

    private fun tx(id: Int, date: LocalDate, amount: String, type: TransactionType = TransactionType.Expense, cat: String = "Food", desc: String? = null,
                   currency: String = "PHP", base: String = amount) =
        Transaction(id, UUID(0, id.toLong()), type, 3, cat, BigDecimal(amount), currency, if (currency == "PHP") BigDecimal.ONE else BigDecimal("56"),
            date, BigDecimal(base), date, desc, Instant.EPOCH, null, null)

    /** Filters the complete list before paging (like the API) and records every request. */
    private class Server(var rows: List<Transaction>) : FilterableTransactionRepository {
        val requests = mutableListOf<Triple<Int, Int, TransactionFilter>>()
        var growAfterFirst = false
        override suspend fun readPage(page: Int, pageSize: Int) = readPage(page, pageSize, TransactionFilter())
        override suspend fun readPage(page: Int, pageSize: Int, filter: TransactionFilter): TransactionPageResult {
            requests += Triple(page, pageSize, filter)
            if (growAfterFirst && page == 2) rows = rows + rows.first().copy(transactionId = 99_999)
            val all = rows.filter { (filter.fromDate == null || it.transactionDate >= filter.fromDate) && (filter.toDate == null || it.transactionDate <= filter.toDate) &&
                (filter.type == null || it.type == filter.type) }.sortedWith(compareByDescending<Transaction> { it.transactionDate }.thenByDescending { it.transactionId })
            return TransactionPageResult.Success(TransactionPage(all.drop((page - 1) * pageSize).take(pageSize), page, pageSize, all.size.toLong()))
        }
    }
    private fun history(n: Int, month: YearMonth = nov) = (1..n).map { tx(it, month.atDay(1 + it % month.lengthOfMonth()), "${10 + it % 90}.25") }

    @Test fun monthIsReadCompletelyWithLargePagesAndMarkedComplete() = runTest(main.dispatcher) {
        val server = Server(history(230))
        val vm = TransactionListViewModel(server, initialFilter = TransactionFilter().forMonth(nov))
        advanceUntilIdle()
        val content = vm.state.value as TransactionListUiState.Content
        assertTrue(content.complete); assertEquals(230, content.items.size); assertEquals(TransactionAppendState.End, content.append)
        assertEquals(listOf(1, 2, 3), server.requests.map { it.first }); assertTrue(server.requests.all { it.second == 100 })
        assertEquals(230, content.items.map { it.transactionId }.toSet().size)
    }

    @Test fun periodsAboveTheCapStayPagedWithoutTotals() = runTest(main.dispatcher) {
        val server = Server(history(620))
        val vm = TransactionListViewModel(server, initialFilter = TransactionFilter().forMonth(nov))
        advanceUntilIdle()
        val content = vm.state.value as TransactionListUiState.Content
        assertFalse(content.complete); assertEquals(100, content.items.size); assertEquals(TransactionAppendState.Ready, content.append)
        assertEquals(1, server.requests.size)
        vm.loadMore(); advanceUntilIdle()
        assertEquals(200, (vm.state.value as TransactionListUiState.Content).items.size)
    }

    @Test fun aCountChangeDuringTheReadNeverClaimsCompleteness() = runTest(main.dispatcher) {
        val server = Server(history(150)).apply { growAfterFirst = true }
        val vm = TransactionListViewModel(server, initialFilter = TransactionFilter().forMonth(nov))
        advanceUntilIdle()
        val content = vm.state.value as TransactionListUiState.Content
        assertFalse(content.complete); assertFalse(content.isRefreshing)
        assertEquals(TransactionAppendState.Ready, content.append)
    }

    @Test fun largeAllTimeHistoryIsPagedTwentyAtATimeWithoutDuplicates() = runTest(main.dispatcher) {
        val rows = (0 until 24).flatMap { m -> history(50, nov.minusMonths(m.toLong())).map { it.copy(transactionId = it.transactionId + m * 1000) } }
        val server = Server(rows)
        val vm = TransactionListViewModel(server)
        advanceUntilIdle()
        repeat(4) { vm.loadMore(); advanceUntilIdle() }
        val content = vm.state.value as TransactionListUiState.Content
        assertFalse(content.complete); assertEquals(100, content.items.size); assertEquals(1200L, content.totalCount)
        assertTrue(server.requests.all { it.second == 20 })
        assertEquals(content.items.sortedWith(compareByDescending<Transaction> { it.transactionDate }.thenByDescending { it.transactionId }), content.items)
        val lines = activityLines(content.items, complete = false, order = AmountOrder.Largest, query = "zzz", monthHeaders = true)
        // Partial data: no search, no amount order, no day totals; month headers appear.
        assertEquals(100, lines.count { it is ActivityLine.Row })
        assertTrue(lines.filterIsInstance<ActivityLine.DayHeader>().all { it.net == null })
        assertTrue(lines.any { it is ActivityLine.MonthHeader })
    }

    @Test fun changingMonthOrTypeSupersedesAndRefreshAfterCreationRereads() = runTest(main.dispatcher) {
        val server = Server(history(40) + history(10, nov.minusMonths(1)).map { it.copy(transactionId = it.transactionId + 500, type = TransactionType.Income) })
        val vm = TransactionListViewModel(server, initialFilter = TransactionFilter().forMonth(nov))
        vm.applyFilter(TransactionFilter().forMonth(nov.minusMonths(1)))
        advanceUntilIdle()
        assertEquals(10, (vm.state.value as TransactionListUiState.Content).items.size)
        vm.applyFilter(vm.activeFilter.value.copy(type = TransactionType.Expense)); advanceUntilIdle()
        assertEquals(TransactionListUiState.Empty, vm.state.value)
        vm.applyFilter(TransactionFilter().forMonth(nov)); advanceUntilIdle()
        server.rows = server.rows + tx(777, nov.atDay(19), "50.00")
        vm.refreshAfterConfirmedCreation(); advanceUntilIdle()
        assertTrue((vm.state.value as TransactionListUiState.Content).items.any { it.transactionId == 777 })
    }

    @Test fun demoMonthTotalsMatchTheSampleDashboardRules() = runTest(main.dispatcher) {
        val vm = TransactionListViewModel(MockTransactionRepository(), initialFilter = TransactionFilter().forMonth(nov))
        advanceUntilIdle()
        val content = vm.state.value as TransactionListUiState.Content
        assertTrue(content.complete)
        val totals = periodTotals(content.items)!!
        val expected = TransactionFixtures.items.filter { YearMonth.from(it.transactionDate) == nov }
        assertEquals(expected.size, totals.count)
        assertEquals(0, expected.filter { it.type == TransactionType.Income }.fold(BigDecimal.ZERO) { a, t -> a + t.amount }.compareTo(totals.income))
        assertEquals(0, BigDecimal("24191.50").compareTo(totals.net))
    }

    @Test fun completeLinesGroupByDayWithExactNetsSearchAndAmountOrder() {
        val d1 = nov.atDay(7); val d2 = nov.atDay(6)
        val items = listOf(tx(5, d1, "185.50", desc = "Lunch at the campus canteen"), tx(4, d1, "35.00", cat = "Transport"),
            tx(3, d2, "3500.75", TransactionType.Income, "Freelance"), tx(2, d2, "1250.00", cat = "Utilities"))
        val lines = activityLines(items, complete = true, order = null, query = "", monthHeaders = false)
        val days = lines.filterIsInstance<ActivityLine.DayHeader>()
        assertEquals(listOf(BigDecimal("-220.50"), BigDecimal("2250.75")), days.map { it.net!!.stripTrailingZeros().setScale(2) })
        assertTrue(lines.none { it is ActivityLine.MonthHeader })
        val rows = lines.filterIsInstance<ActivityLine.Row>()
        assertTrue(rows.first().first && rows[1].last)
        assertEquals(listOf(5), activityLines(items, true, null, "canteen", false).filterIsInstance<ActivityLine.Row>().map { it.transaction.transactionId })
        assertEquals(listOf(2), activityLines(items, true, null, "1,250", false).filterIsInstance<ActivityLine.Row>().map { it.transaction.transactionId })
        assertEquals(listOf(4), activityLines(items, true, null, "TRANS", false).filterIsInstance<ActivityLine.Row>().map { it.transaction.transactionId })
        val largest = activityLines(items, true, AmountOrder.Largest, "", false)
        assertEquals(ActivityLine.OrderHeader(AmountOrder.Largest, 4), largest.first())
        assertEquals(listOf(3, 2, 5, 4), largest.filterIsInstance<ActivityLine.Row>().map { it.transaction.transactionId })
        assertEquals(listOf(4, 5, 2, 3), activityLines(items, true, AmountOrder.Smallest, "", false).filterIsInstance<ActivityLine.Row>().map { it.transaction.transactionId })
        assertEquals(lines.size, lines.map { it.key }.toSet().size)
    }

    @Test fun convertedAmountsDisableTotalsAndDayNets() {
        val items = listOf(tx(1, nov.atDay(3), "10.00", currency = "USD", base = "560.00"), tx(2, nov.atDay(3), "20.00"))
        assertNull(periodTotals(items))
        assertNull(activityLines(items, true, null, "", false).filterIsInstance<ActivityLine.DayHeader>().single().net)
        assertNotNull(periodTotals(items.drop(1)))
    }

    @Test fun filterMonthHelpers() {
        val f = TransactionFilter(type = TransactionType.Income).forMonth(nov)
        assertEquals(nov, f.month); assertTrue(f.isBounded)
        assertNull(f.allTime().month); assertFalse(f.allTime().isBounded)
        assertNull(f.copy(toDate = nov.atDay(20)).month)
        assertEquals(TransactionFilter().forMonth(nov), f.withoutCriteria())
    }
}
