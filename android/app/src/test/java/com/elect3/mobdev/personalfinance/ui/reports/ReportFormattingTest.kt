package com.elect3.mobdev.personalfinance.ui.reports

import com.elect3.mobdev.personalfinance.domain.reports.MonthTrendPoint
import com.elect3.mobdev.personalfinance.domain.reports.ReportCategoryTotal
import java.math.BigDecimal
import java.time.YearMonth
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFormattingTest {
    private fun m(v: String) = BigDecimal(v)

    @Test fun exactAmountsInTheBaseCurrency() {
        assertEquals("₱1,234.50", formatReportAmount(m("1234.5"), "PHP"))
        assertEquals("USD 1,234.50", formatReportAmount(m("1234.50"), "USD"))
        assertEquals("₱0.00", formatReportAmount(m("0"), "PHP"))
        // Excess precision is a contract error, never silently rounded.
        assertThrows(ArithmeticException::class.java) { formatReportAmount(m("1.005"), "PHP") }
    }

    @Test fun signedNetUsesExplicitSigns() {
        assertEquals("+₱500.00", formatSignedReportAmount(m("500"), "PHP"))
        assertEquals("−₱500.00", formatSignedReportAmount(m("-500"), "PHP"))
        assertEquals("₱0.00", formatSignedReportAmount(m("0.00"), "PHP"))
        assertEquals("−EUR 2.25", formatSignedReportAmount(m("-2.25"), "EUR"))
    }

    @Test fun compactAxisLabels() {
        assertEquals("₱0", formatCompactReportAmount(m("0"), "PHP"))
        assertEquals("₱950", formatCompactReportAmount(m("949.50"), "PHP"))
        assertEquals("₱1K", formatCompactReportAmount(m("999.60"), "PHP"))
        assertEquals("₱12.5K", formatCompactReportAmount(m("12500"), "PHP"))
        assertEquals("₱10K", formatCompactReportAmount(m("10000"), "PHP"))
        assertEquals("₱1M", formatCompactReportAmount(m("999960"), "PHP"))
        assertEquals("USD 2.3M", formatCompactReportAmount(m("2345678.90"), "USD"))
        assertEquals("−₱1.5K", formatCompactReportAmount(m("-1500"), "PHP"))
    }

    @Test fun sharesUseTheFullTotal() {
        assertEquals("42.9%", reportShareLabel(m("300"), m("700")))
        assertEquals("100.0%", reportShareLabel(m("5"), m("5")))
        assertEquals("0.0%", reportShareLabel(m("5"), m("0")))
    }

    @Test fun monthLabels() {
        assertEquals("November 2026", reportMonthLabel(YearMonth.of(2026, 11), Locale.US))
        assertEquals("Nov", reportShortMonthLabel(YearMonth.of(2026, 11), Locale.US))
    }

    private fun point(month: Int, expenses: String) = MonthTrendPoint(YearMonth.of(2026, month), m("0"), m(expenses), 1)

    @Test fun spendingChangeComparesWithThePreviousMonth() {
        assertEquals(SpendingChange.Up(YearMonth.of(2026, 10), "25.0%"), spendingChange(listOf(point(10, "100"), point(11, "125"))))
        assertEquals(SpendingChange.Down(YearMonth.of(2026, 10), "50.0%"), spendingChange(listOf(point(10, "100"), point(11, "50"))))
        assertEquals(SpendingChange.Same(YearMonth.of(2026, 10)), spendingChange(listOf(point(10, "100.00"), point(11, "100"))))
        assertEquals(SpendingChange.NoBaseline, spendingChange(listOf(point(10, "0"), point(11, "50"))))
        assertEquals(SpendingChange.NoBaseline, spendingChange(listOf(point(11, "50"))))
    }

    @Test fun slicesGroupTheRemainderAndSumToTheTotal() {
        val cats = (1..7).map { ReportCategoryTotal(it, "C$it", m("${80 - it * 10}"), 1) } // 70,60,...,10 = 280
        val slices = reportSlices(cats, m("280"))
        assertEquals(6, slices.size)
        assertEquals((1..5).toList(), slices.take(5).map { it.rank })
        val other = slices.last()
        assertNull(other.rank); assertEquals(2, other.otherCount); assertEquals(0, m("30").compareTo(other.total))
        assertTrue(kotlin.math.abs(slices.sumOf { it.fraction.toDouble() } - 1.0) < 1e-4)
        assertEquals(slices.size, slices.map { it.hex }.toSet().size)
        assertTrue(reportSlices(emptyList(), m("0")).isEmpty())
    }
}
