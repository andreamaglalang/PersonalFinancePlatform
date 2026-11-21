package com.elect3.mobdev.personalfinance.domain.reports

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportPeriodTest {
    @Test fun windowCoversSixWholeMonthsEndingOnTheSelectedMonthsLastDay() {
        val w = ReportPeriod.window(YearMonth.of(2026, 11))
        assertEquals(LocalDate.of(2026, 6, 1), w.start)
        assertEquals(LocalDate.of(2026, 11, 30), w.endInclusive)
    }

    @Test fun windowCrossesYearsAndHandlesLeapFebruary() {
        val w = ReportPeriod.window(YearMonth.of(2028, 2))
        assertEquals(LocalDate.of(2027, 9, 1), w.start)
        assertEquals(LocalDate.of(2028, 2, 29), w.endInclusive)
        assertEquals((9..12).map { YearMonth.of(2027, it) } + listOf(YearMonth.of(2028, 1), YearMonth.of(2028, 2)),
            ReportPeriod.trendMonths(YearMonth.of(2028, 2)))
    }

    @Test fun futureMonthsAreNeverSelectable() {
        val now = YearMonth.of(2026, 11)
        assertEquals(now, ReportPeriod.clamp(YearMonth.of(2027, 1), now))
        assertFalse(ReportPeriod.canGoForward(now, now))
        assertTrue(ReportPeriod.canGoForward(now.minusMonths(1), now))
        assertTrue(ReportPeriod.canGoBack(now))
    }

    @Test fun earliestMonthKeepsTheWindowValid() {
        assertFalse(ReportPeriod.canGoBack(ReportPeriod.EARLIEST))
        assertEquals(1, ReportPeriod.window(ReportPeriod.EARLIEST).start.year)
        assertEquals(ReportPeriod.EARLIEST, ReportPeriod.clamp(YearMonth.of(1, 1), YearMonth.of(2026, 11)))
    }
}
