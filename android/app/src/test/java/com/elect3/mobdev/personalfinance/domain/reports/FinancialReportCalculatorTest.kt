package com.elect3.mobdev.personalfinance.domain.reports

import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.reports.ReportTestData.expense
import com.elect3.mobdev.personalfinance.domain.reports.ReportTestData.income
import com.elect3.mobdev.personalfinance.domain.reports.ReportTestData.row
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialReportCalculatorTest {
    private val calculator = FinancialReportCalculator()
    private val nov = YearMonth.of(2026, 11)
    private fun d(month: Int, day: Int) = LocalDate.of(2026, month, day)
    private fun money(v: String) = BigDecimal(v)
    private fun assertMoney(expected: String, actual: BigDecimal) = assertEquals(0, money(expected).compareTo(actual))

    @Test fun selectedMonthTotalsNetAndCountsUseOnlyThatMonth() {
        val report = calculator.calculate(listOf(
            income(1, "50000.00", d(11, 1)),
            expense(2, "1200.50", d(11, 3)),
            expense(3, "799.50", d(11, 30)),
            expense(4, "9999.99", d(10, 31)), // previous month: trend only
        ), nov, "PHP")
        val s = report.selected
        assertEquals(nov, s.month)
        assertMoney("50000.00", s.totalIncome)
        assertMoney("2000.00", s.totalExpenses)
        assertMoney("48000.00", s.netBalance)
        assertEquals(3, s.transactionCount)
        assertFalse(s.isEmpty)
    }

    @Test fun categoriesAreRankedSumExactlyAndUseNewestName() {
        val report = calculator.calculate(listOf(
            expense(1, "300.00", d(11, 2), categoryId = 7, categoryName = "Groceries"),
            expense(2, "200.00", d(11, 9), categoryId = 7, categoryName = "Food & Groceries"),
            expense(3, "500.00", d(11, 4), categoryId = 3, categoryName = "Rent"),
            expense(4, "100.00", d(11, 5), categoryId = 9, categoryName = "Bus"),
        ), nov, "PHP")
        val cats = report.selected.expenseCategories
        // Ties (500 vs 500) break by name, then ID.
        assertEquals(listOf("Food & Groceries", "Rent", "Bus"), cats.map { it.categoryName })
        assertEquals(listOf(2, 1, 1), cats.map { it.transactionCount })
        assertMoney(report.selected.totalExpenses.toPlainString(), cats.fold(BigDecimal.ZERO) { a, c -> a + c.total })
    }

    @Test fun trendHasSixConsecutiveMonthsIncludingEmptyOnes() {
        val report = calculator.calculate(listOf(income(1, "10.00", d(6, 1)), expense(2, "4.00", d(9, 15))), nov, "PHP")
        assertEquals((6..11).map { YearMonth.of(2026, it) }, report.trend.map { it.month })
        assertEquals(listOf(1, 0, 0, 1, 0, 0), report.trend.map { it.transactionCount })
        assertMoney("10.00", report.trend.first().income)
        assertMoney("-4.00", report.trend[3].net)
        assertTrue(report.selected.isEmpty)
        assertFalse(report.windowIsEmpty)
    }

    @Test fun emptyWindowIsAnHonestZeroReport() {
        val report = calculator.calculate(emptyList(), nov, "PHP")
        assertTrue(report.windowIsEmpty)
        assertTrue(report.selected.isEmpty)
        assertMoney("0", report.selected.netBalance)
        assertTrue(report.selected.expenseCategories.isEmpty())
    }

    @Test fun foreignCurrencyRowsCountOnceAtTheStoredBaseAmount() {
        val usd = row(1, TransactionType.Expense, "10.00", d(11, 10), currency = "USD", rate = "58.12345678")
        val report = calculator.calculate(listOf(usd, expense(2, "100.00", d(11, 11))), nov, "PHP")
        // 10 USD × 58.12345678 = 581.2345678 → 581.23 stored by the server; never the source 10.00, never re-converted.
        assertMoney("681.23", report.selected.totalExpenses)
        assertEquals(1, report.selected.foreignCurrencyCount)
        assertEquals(listOf("USD"), report.selected.foreignCurrencies)
        assertEquals("PHP", report.currency)
    }

    @Test fun nonPhpBaseCurrencyIsRespected() {
        val php = row(1, TransactionType.Income, "1000.00", d(11, 1), currency = "PHP", rate = "0.01712345", rateDate = d(10, 31))
        val usd = row(2, TransactionType.Expense, "5.00", d(11, 2), currency = "USD", rate = "1", rateDate = d(11, 2))
        val report = calculator.calculate(listOf(php, usd), nov, "USD")
        assertEquals("USD", report.currency)
        assertMoney("17.12", report.selected.totalIncome)
        assertMoney("5.00", report.selected.totalExpenses)
        assertEquals(listOf("PHP"), report.selected.foreignCurrencies)
    }

    @Test fun inconsistentSnapshotsAreRejectedNotRepaired() {
        val tampered = row(1, TransactionType.Expense, "10.00", d(11, 10), currency = "USD", rate = "58", amountBase = money("10.00"))
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate(listOf(tampered), nov, "PHP") }
        val sameCurrencyRate = row(2, TransactionType.Expense, "10.00", d(11, 10), currency = "PHP", rate = "2", amountBase = money("20.00"))
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate(listOf(sameCurrencyRate), nov, "PHP") }
    }

    @Test fun rowsOutsideTheWindowOrDuplicatedAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate(listOf(expense(1, "1.00", d(12, 1))), nov, "PHP") }
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate(listOf(expense(1, "1.00", d(5, 31))), nov, "PHP") }
        assertThrows(IllegalArgumentException::class.java) {
            calculator.calculate(listOf(expense(1, "1.00", d(11, 1)), expense(1, "1.00", d(11, 1))), nov, "PHP")
        }
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate(emptyList(), nov, "php") }
    }
}
