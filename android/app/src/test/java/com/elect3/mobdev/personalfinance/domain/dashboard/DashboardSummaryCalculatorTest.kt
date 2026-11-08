package com.elect3.mobdev.personalfinance.domain.dashboard

import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class DashboardSummaryCalculatorTest {
    private val calculator = DashboardSummaryCalculator()
    private val fallback = YearMonth.of(2026, 11)

    private fun row(id: Int, date: String, amount: String, type: TransactionType = TransactionType.Income): Transaction =
        TransactionFixtures.items.first().copy(
            transactionId = id,
            transactionDate = LocalDate.parse(date),
            fxRateDate = LocalDate.parse(date),
            amount = BigDecimal(amount),
            amountBase = BigDecimal(amount),
            type = type,
        )

    private fun money(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

    @Test fun exactTotalsAndOctoberNovemberBoundary() {
        val summary = calculator.calculate(listOf(
            row(1, "2026-10-31", "100.25"),
            row(2, "2026-11-01", "50.50"),
            row(3, "2026-11-30", "20.10", TransactionType.Expense),
            row(4, "2026-10-31", "10.05", TransactionType.Expense),
        ), fallback)
        assertEquals("PHP", summary.reportingCurrency)
        assertEquals(fallback, summary.reportingMonth)
        assertEquals(4L, summary.recordCount)
        money("150.75", summary.totalIncome)
        money("30.15", summary.totalExpenses)
        money("120.60", summary.netTransactionFlow)
        money("50.50", summary.monthlyIncome)
        money("20.10", summary.monthlyExpenses)
        money("30.40", summary.monthlyNetTransactionFlow)
    }

    @Test fun negativeAndZeroNetFlowsKeepExpenseMagnitudesPositive() {
        for ((expense, expected) in listOf("15.00" to "-5.00", "10.00" to "0.00")) {
            val summary = calculator.calculate(listOf(
                row(1, "2026-11-01", "10.00"),
                row(2, "2026-11-02", expense, TransactionType.Expense),
            ), fallback)
            money(expected, summary.netTransactionFlow)
            money(expected, summary.monthlyNetTransactionFlow)
            money(expense, summary.totalExpenses)
        }
    }

    @Test fun yearBoundaryUsesExclusiveNextMonthStart() {
        val summary = calculator.calculate(listOf(
            row(1, "2026-12-31", "10.00"),
            row(2, "2027-01-01", "20.00"),
        ), fallback, YearMonth.of(2026, 12))
        money("10.00", summary.monthlyIncome)
        money("30.00", summary.totalIncome)
    }

    @Test fun leapDayIsIncludedButMarchFirstIsExcluded() {
        val summary = calculator.calculate(listOf(
            row(1, "2024-02-29", "7.25"),
            row(2, "2024-03-01", "9.00"),
        ), fallback, YearMonth.of(2024, 2))
        money("7.25", summary.monthlyIncome)
    }

    @Test fun emptyDatasetUsesExplicitFallbackAndZeroTotals() {
        val summary = calculator.calculate(emptyList(), YearMonth.of(2020, 4))
        assertEquals(YearMonth.of(2020, 4), summary.reportingMonth)
        assertEquals(0L, summary.recordCount)
        assertTrue(summary.recentTransactions.isEmpty())
        listOf(summary.totalIncome, summary.totalExpenses, summary.netTransactionFlow,
            summary.monthlyIncome, summary.monthlyExpenses, summary.monthlyNetTransactionFlow)
            .forEach { money("0.00", it) }
    }

    @Test fun emptyReportingMonthDoesNotEraseHistoryTotals() {
        val summary = calculator.calculate(listOf(row(1, "2026-11-01", "12.00")), fallback, YearMonth.of(2026, 9))
        money("12.00", summary.totalIncome)
        money("0.00", summary.monthlyIncome)
        money("0.00", summary.monthlyExpenses)
        money("0.00", summary.monthlyNetTransactionFlow)
    }

    @Test fun recentOrderingUsesDateThenDescendingIdAndCapsAtFive() {
        val rows = listOf(
            row(8, "2026-10-31", "1.00"), row(1, "2026-11-01", "1.00"),
            row(3, "2026-11-02", "1.00"), row(2, "2026-11-02", "1.00"),
            row(4, "2026-11-03", "1.00"), row(5, "2026-11-04", "1.00"),
        )
        assertEquals(listOf(5, 4, 3, 2, 1), calculator.calculate(rows, fallback).recentTransactions.map { it.transactionId })
        assertEquals(listOf(1, 8), calculator.calculate(rows.take(2), fallback).recentTransactions.map { it.transactionId })
    }

    @Test fun largeAmountsRemainExactAndTrailingZerosArePreserved() {
        val summary = calculator.calculate(listOf(
            row(1, "2026-11-01", "9999999999999999999999999999.9900"),
            row(2, "2026-11-02", "0.01"),
        ), fallback)
        assertEquals(BigDecimal("10000000000000000000000000000.0000"), summary.totalIncome)
    }

    @Test fun unsupportedCurrenciesAndMixedCurrencyAreRejected() {
        val php = row(1, "2026-11-01", "1.00")
        val usd = php.copy(transactionId = 2, currencyCode = "USD")
        for (rows in listOf(listOf(usd), listOf(php, usd))) {
            assertThrows(IllegalArgumentException::class.java) { calculator.calculate(rows, fallback) }
        }
    }

    @Test fun invalidMagnitudesPrecisionAndConversionContractAreRejected() {
        val valid = row(1, "2026-11-01", "1.00")
        val invalid = listOf(
            valid.copy(amount = BigDecimal("-1.00")),
            valid.copy(amountBase = BigDecimal("-1.00")),
            valid.copy(amount = BigDecimal("1.001")),
            valid.copy(amountBase = BigDecimal("1.001")),
            valid.copy(fxRate = BigDecimal("1.01")),
            valid.copy(amountBase = BigDecimal("2.00")),
            valid.copy(fxRateDate = valid.fxRateDate.minusDays(1)),
        )
        invalid.forEach { transaction ->
            assertThrows(IllegalArgumentException::class.java) { calculator.calculate(listOf(transaction), fallback) }
        }
    }

    @Test fun repeatedIdsAreRejectedAndZeroMagnitudesAreSupported() {
        val zero = row(1, "2026-11-01", "0.00")
        money("0.00", calculator.calculate(listOf(zero), fallback).totalIncome)
        assertThrows(IllegalArgumentException::class.java) { calculator.calculate(listOf(zero, zero), fallback) }
    }

    @Test fun actualFixtureSelectsNovemberAndRetainsAllRecords() {
        val summary = calculator.calculate(TransactionFixtures.items, YearMonth.of(2000, 1))
        assertEquals(YearMonth.of(2026, 11), summary.reportingMonth)
        assertEquals(45L, summary.recordCount)
        // Independently specified fixture income: 25000 + 12500 + 3500.75 + 2200 + 1800 + 1250.
        money("46250.75", summary.totalIncome)
        money("10202.50", summary.totalExpenses)
        money("36048.25", summary.netTransactionFlow)
        money("28500.75", summary.monthlyIncome)
        money("4309.25", summary.monthlyExpenses)
        money("24191.50", summary.monthlyNetTransactionFlow)
        assertEquals(listOf(45, 44, 43, 42, 41), summary.recentTransactions.map { it.transactionId })
    }
}
