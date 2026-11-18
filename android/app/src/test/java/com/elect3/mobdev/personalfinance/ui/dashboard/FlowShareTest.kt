package com.elect3.mobdev.personalfinance.ui.dashboard

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

/** Owl-eye ring shares: exact BigDecimal ratios, rounding for display only. */
class FlowShareTest {
    private fun shares(income: String, expenses: String) = flowShares(BigDecimal(income), BigDecimal(expenses))

    @Test fun verifiedDemoAllTimeFiguresRoundTo82And18() {
        // Owlight reference: income 46,250.75 and expenses 10,202.50 -> 82% / 18%.
        val (inc, exp) = shares("46250.75", "10202.50")
        assertEquals("82%", inc.label)
        assertEquals("18%", exp.label)
        assertEquals(0.8193f, inc.fraction, 0.0001f)
    }

    @Test fun verifiedDemoMonthFiguresRoundTo87And13() {
        val (inc, exp) = shares("28500.75", "4309.25")
        assertEquals("87%", inc.label)
        assertEquals("13%", exp.label)
    }

    @Test fun zeroTotalShowsZeroForBothWithoutDividing() {
        val (inc, exp) = shares("0.00", "0.00")
        assertEquals("0%", inc.label); assertEquals(0f, inc.fraction)
        assertEquals("0%", exp.label); assertEquals(0f, exp.fraction)
    }

    @Test fun onlyExpensesGiveZeroAndHundred() {
        val (inc, exp) = shares("0", "1250.00")
        assertEquals("0%", inc.label)
        assertEquals("100%", exp.label)
        assertEquals(1f, exp.fraction, 0f)
    }

    @Test fun scaleDoesNotChangeTheResult() {
        assertEquals(shares("100", "300"), shares("100.00", "300.0000"))
        assertEquals("25%", shares("100", "300").first.label)
    }

    @Test fun halfPercentRoundsHalfUpForDisplay() {
        // 1 / 200 = 0.5% -> "1%" (display rounding only).
        assertEquals("1%", shares("1", "199").first.label)
    }

    @Test fun categoryShareUsesFullPeriodTotalWithOneDecimalHalfUp() {
        // Owlight reference: Utilities 1,849.00 of 4,309.25 -> 42.9%; Transport 225.00 -> 5.2%.
        assertEquals("42.9%", categoryShareLabel(BigDecimal("1849.00"), BigDecimal("4309.25")))
        assertEquals("5.2%", categoryShareLabel(BigDecimal("225.00"), BigDecimal("4309.25")))
        assertEquals("0.0%", categoryShareLabel(BigDecimal("1.00"), BigDecimal("0")))
        assertEquals("100.0%", categoryShareLabel(BigDecimal("10"), BigDecimal("10.00")))
        assertEquals("0.1%", categoryShareLabel(BigDecimal("1"), BigDecimal("2000")))   // 0.05 -> 0.1 (half-up)
    }
}
