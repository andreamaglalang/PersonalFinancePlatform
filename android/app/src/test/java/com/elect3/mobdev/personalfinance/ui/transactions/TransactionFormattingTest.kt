package com.elect3.mobdev.personalfinance.ui.transactions

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.ParsePosition
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class TransactionFormattingTest {
    // Parse using JVM locale data, not Android ICU's potentially different symbol/spacing.
    private fun parsedAmount(text: String): BigDecimal {
        val parser = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH")) as DecimalFormat
        parser.isParseBigDecimal = true
        val position = ParsePosition(0)
        val value = parser.parse(text, position)
        assertEquals(text.length, position.index)
        assertTrue(value is BigDecimal)
        return value as BigDecimal
    }

    @Test
    fun phpFormattingPreservesZeroFractionsAndLargeValues() {
        for (value in listOf("0.00", "35", "185.50", "3500.75", "9999999999999999.99")) {
            val amount = BigDecimal(value)
            val text = formatPhpAmount(amount)
            assertEquals(0, amount.compareTo(parsedAmount(text)))
            assertTrue(text.endsWith(amount.setScale(2).toPlainString().takeLast(3)))
        }
    }

    @Test
    fun groupingAndNegativeSignFollowPhpLocaleWithoutLosingPrecision() {
        val symbols = (NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH")) as DecimalFormat).decimalFormatSymbols
        val positive = formatPhpAmount(BigDecimal("1234567.89"))
        val negative = formatPhpAmount(BigDecimal("-1234567.89"))
        assertTrue(positive.contains(symbols.groupingSeparator))
        assertFalse(positive.contains(symbols.minusSign))
        assertTrue(negative.contains(symbols.minusSign))
        assertEquals(0, BigDecimal("-1234567.89").compareTo(parsedAmount(negative)))
        // Explicit income plus / expense minus are string-resource presentation, not formatter behavior.
    }

    @Test
    fun roundingIsRejectedButTrailingZerosAreAllowed() {
        for (value in listOf("1.001", "-1.009")) {
            assertThrows(ArithmeticException::class.java) { formatPhpAmount(BigDecimal(value)) }
        }
        assertEquals(0, BigDecimal("1.00").compareTo(parsedAmount(formatPhpAmount(BigDecimal("1.000")))))
    }

    @Test
    fun dateFormattingUsesExplicitLocaleAndPreservesCalendarDate() {
        val date = LocalDate.of(2026, 11, 7)
        assertEquals("Nov 7, 2026", formatTransactionDate(date, Locale.US))
        assertEquals("07.11.2026", formatTransactionDate(date, Locale.GERMANY))
        assertEquals("Feb 29, 2024", formatTransactionDate(LocalDate.of(2024, 2, 29), Locale.US))
        assertEquals(LocalDate.of(2026, 11, 7), date)
    }
}
