package com.elect3.mobdev.personalfinance.domain.dashboard

import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Where it went" source data: complete per-category expense totals, ranked deterministically. */
class DashboardCategoryRankingTest {
    private val calculator = DashboardSummaryCalculator()
    private val fallback = YearMonth.of(2026, 11)

    private fun tx(id: Int, date: String, amount: String, type: TransactionType, categoryId: Int, name: String): Transaction =
        TransactionFixtures.items.first().copy(
            transactionId = id, transactionDate = LocalDate.parse(date), fxRateDate = LocalDate.parse(date),
            amount = BigDecimal(amount), amountBase = BigDecimal(amount), type = type, categoryId = categoryId, categoryName = name,
        )
    private fun expense(id: Int, date: String, amount: String, categoryId: Int, name: String) =
        tx(id, date, amount, TransactionType.Expense, categoryId, name)

    @Test fun onlyExpensesAreRankedAndIncomeIsIgnored() {
        val s = calculator.calculate(listOf(
            tx(1, "2026-11-02", "9999.00", TransactionType.Income, 50, "Salary"),
            expense(2, "2026-11-03", "10.00", 1, "Food"),
        ), fallback)
        assertEquals(listOf("Food"), s.monthlyExpenseCategoryTotals.map { it.categoryName })
        assertEquals(listOf("Food"), s.expenseCategoryTotals.map { it.categoryName })
    }

    @Test fun rankingIsByExactTotalThenNameThenId() {
        val s = calculator.calculate(listOf(
            expense(1, "2026-11-01", "5.00", 3, "Transport"),
            expense(2, "2026-11-02", "20.00", 1, "Utilities"),
            expense(3, "2026-11-03", "5.0", 2, "Food"),          // ties Transport at 5 (scale ignored) -> name order
            expense(4, "2026-11-04", "12.50", 4, "Shopping"),
        ), fallback)
        assertEquals(listOf("Utilities", "Shopping", "Food", "Transport"), s.monthlyExpenseCategoryTotals.map { it.categoryName })
    }

    @Test fun categoryTotalsSumExactlyToTotalExpensesForBothPeriods() {
        val s = calculator.calculate(TransactionFixtures.items, fallback)
        val allSum = s.expenseCategoryTotals.fold(BigDecimal.ZERO) { a, c -> a + c.total }
        val monthSum = s.monthlyExpenseCategoryTotals.fold(BigDecimal.ZERO) { a, c -> a + c.total }
        assertEquals(0, allSum.compareTo(s.totalExpenses))
        assertEquals(0, monthSum.compareTo(s.monthlyExpenses))
    }

    @Test fun reportingMonthExcludesOtherMonthsButAllTimeIncludesThem() {
        val s = calculator.calculate(listOf(
            expense(1, "2026-10-31", "100.00", 1, "Rent"),
            expense(2, "2026-11-01", "40.00", 2, "Food"),
        ), fallback)
        assertEquals(listOf("Food"), s.monthlyExpenseCategoryTotals.map { it.categoryName })
        assertEquals(listOf("Rent", "Food"), s.expenseCategoryTotals.map { it.categoryName })
    }

    @Test fun categoriesGroupByIdAndUseTheNewestNameInTheWindow() {
        val s = calculator.calculate(listOf(
            expense(1, "2026-11-01", "10.00", 7, "Old name"),
            expense(2, "2026-11-05", "15.00", 7, "Renamed"),
        ), fallback)
        val only = s.monthlyExpenseCategoryTotals.single()
        assertEquals("Renamed", only.categoryName)
        assertEquals(0, BigDecimal("25.00").compareTo(only.total))
    }

    @Test fun verifiedDemoFixturesMatchTheApprovedMockupRanking() {
        // Owlight reference: November 2026 expenses Utilities 1,849.00 · Shopping 1,149.00 · Food 811.25 ·
        // Healthcare 275.00 · Transport 225.00 out of 4,309.25.
        val s = calculator.calculate(TransactionFixtures.items, fallback)
        val month = s.monthlyExpenseCategoryTotals
        assertTrue(month.size >= 5)
        assertEquals(listOf("Utilities", "Shopping", "Food", "Healthcare", "Transport"), month.take(5).map { it.categoryName })
        assertEquals(0, BigDecimal("1849.00").compareTo(month[0].total))
        assertEquals(0, BigDecimal("4309.25").compareTo(s.monthlyExpenses))
    }

    @Test fun noExpensesYieldsEmptyRankings() {
        val s = calculator.calculate(listOf(tx(1, "2026-11-02", "10.00", TransactionType.Income, 50, "Salary")), fallback)
        assertTrue(s.expenseCategoryTotals.isEmpty())
        assertTrue(s.monthlyExpenseCategoryTotals.isEmpty())
    }
}
