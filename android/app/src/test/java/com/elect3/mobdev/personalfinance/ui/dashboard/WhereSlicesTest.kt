package com.elect3.mobdev.personalfinance.ui.dashboard

import com.elect3.mobdev.personalfinance.data.mock.DemoSession
import com.elect3.mobdev.personalfinance.data.mock.MockTransactionRepository
import com.elect3.mobdev.personalfinance.domain.dashboard.DashboardSummaryResult
import com.elect3.mobdev.personalfinance.domain.dashboard.LoadDashboardSummary
import com.elect3.mobdev.personalfinance.domain.model.DashboardCategoryTotal
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.CategoryQuery
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** The donut is built from exactly the ranked totals and period total the category list uses. */
class WhereSlicesTest {
    private fun cat(id: Int, name: String, v: String) = DashboardCategoryTotal(id, name, BigDecimal(v))
    private fun total(vararg c: DashboardCategoryTotal) = c.fold(BigDecimal.ZERO) { a, x -> a + x.total }

    @Test fun slicesMatchTheListRowsAmountsAndPercentages() {
        val ranked = listOf(cat(5, "Utilities", "3174.00"), cat(3, "Food", "2817.25"), cat(6, "Shopping", "2125.75"),
            cat(7, "Healthcare", "1450.00"), cat(4, "Transport", "635.50"))
        val period = total(*ranked.toTypedArray())
        val slices = whereSlices(ranked, period)
        assertEquals(ranked.map { it.categoryName }, slices.map { it.label })
        assertEquals(ranked.map { it.total }, slices.map { it.total })
        // Same label function as the list rows.
        assertEquals(ranked.map { categoryShareLabel(it.total, period) }, slices.map { it.share })
        assertEquals(listOf(1, 2, 3, 4, 5), slices.map { it.rank })
        assertEquals(1f, slices.sumOf { it.fraction.toDouble() }.toFloat(), 0.0001f)
        assertEquals(slices.size, slices.map { it.hex }.toSet().size) // no two slices share a colour
    }

    @Test fun zeroSpendingOrNoCategoriesShowsNoChart() {
        assertTrue(whereSlices(emptyList(), BigDecimal("100")).isEmpty())
        assertTrue(whereSlices(listOf(cat(1, "Food", "0")), BigDecimal.ZERO).isEmpty())
    }

    @Test fun singleCategoryIsTheWholeRing() {
        val s = whereSlices(listOf(cat(3, "Food", "640.50")), BigDecimal("640.50")).single()
        assertEquals(1f, s.fraction, 0f); assertEquals(categoryShareLabel(BigDecimal("640.50"), BigDecimal("640.50")), s.share)
        assertEquals(0f to 360f, sliceAngles(listOf(s)).single())
    }

    @Test fun manyCategoriesGroupTheRestAsOneOtherSlice() {
        val ranked = (1..12).map { cat(it, "C$it", (1300 - it * 100).toString() + ".00") }
        val period = total(*ranked.toTypedArray())
        val slices = whereSlices(ranked, period)
        assertEquals(6, slices.size)
        val other = slices.last()
        assertNull(other.rank); assertEquals(7, other.otherCount); assertEquals(OTHER_SLICE_HEX, other.hex)
        assertEquals(0, ranked.drop(5).fold(BigDecimal.ZERO) { a, c -> a + c.total }.compareTo(other.total))
        assertEquals(0, period.compareTo(slices.fold(BigDecimal.ZERO) { a, s -> a + s.total }))
    }

    @Test fun tinyAndHugeValuesKeepExactSharesAndContiguousAngles() {
        val ranked = listOf(cat(1, "Rent", "9999999999.99"), cat(2, "Gum", "0.01"))
        val period = total(*ranked.toTypedArray())
        val slices = whereSlices(ranked, period)
        assertEquals(categoryShareLabel(BigDecimal("0.01"), period), slices[1].share)
        val angles = sliceAngles(slices)
        assertEquals(0f, angles[0].first, 0f)
        assertEquals(angles[0].first + angles[0].second, angles[1].first, 0.001f) // drawing follows exact cumulative shares
        assertTrue(angles[1].second >= 0f)
    }

    @Test fun periodChangesUseThatPeriodsTotalsAndADemoExpenseUpdatesTheChart() = runTest {
        val session = DemoSession()
        val loader = LoadDashboardSummary(MockTransactionRepository(ledger = session.ledger))
        val before = (loader(YearMonth.of(2026, 11)) as DashboardSummaryResult.Success).summary
        val month = whereSlices(before.monthlyExpenseCategoryTotals, before.monthlyExpenses)
        val all = whereSlices(before.expenseCategoryTotals, before.totalExpenses)
        assertEquals(0, before.monthlyExpenses.compareTo(month.fold(BigDecimal.ZERO) { a, s -> a + s.total }))
        assertEquals(0, before.totalExpenses.compareTo(all.fold(BigDecimal.ZERO) { a, s -> a + s.total }))
        val food = (session.categories.list(CategoryQuery(type = TransactionType.Expense, pageSize = 100)) as FinanceResult.Success)
            .value.items.first { it.name == "Food" }
        session.ledger.add(TransactionType.Expense, food, BigDecimal("5000.00"), LocalDate.of(2026, 11, 19), null)
        val after = (loader(YearMonth.of(2026, 11)) as DashboardSummaryResult.Success).summary
        val updated = whereSlices(after.monthlyExpenseCategoryTotals, after.monthlyExpenses)
        assertEquals("Food", updated.first().label)
        assertEquals(0, after.monthlyExpenses.compareTo(before.monthlyExpenses + BigDecimal("5000.00")))
        assertEquals(categoryShareLabel(updated.first().total, after.monthlyExpenses), updated.first().share)
    }
}
