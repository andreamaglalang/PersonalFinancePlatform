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
}
