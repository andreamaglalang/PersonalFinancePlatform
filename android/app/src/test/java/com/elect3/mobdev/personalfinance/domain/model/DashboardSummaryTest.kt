package com.elect3.mobdev.personalfinance.domain.model

import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import java.math.BigDecimal
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class DashboardSummaryTest {
    @Test fun recentRecordsAreDefensivelyCopiedAndUnmodifiable() {
        val source = mutableListOf(TransactionFixtures.items.first())
        val summary = DashboardSummary("PHP", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            YearMonth.of(2026, 11), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, source, 1)
        source.clear()
        assertEquals(1, summary.recentTransactions.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (summary.recentTransactions as MutableList<Transaction>).clear()
        }
    }

    @Test fun newMonthlyFieldsDefaultSafelyAndTopCategoriesAreDefensivelyCopied() {
        val defaults = DashboardSummary("PHP", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            YearMonth.of(2026, 11), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, emptyList(), 0)
        assertEquals(0L, defaults.monthlyRecordCount)
        assertTrue(defaults.topExpenseCategories.isEmpty())

        val source = mutableListOf(DashboardCategoryTotal(7, "Groceries", BigDecimal("10.00")))
        val summary = DashboardSummary("PHP", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            YearMonth.of(2026, 11), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, emptyList(), 1,
            monthlyRecordCount = 1, topExpenseCategories = source)
        source.clear()
        assertEquals(1, summary.topExpenseCategories.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (summary.topExpenseCategories as MutableList<DashboardCategoryTotal>).clear()
        }
    }
}
