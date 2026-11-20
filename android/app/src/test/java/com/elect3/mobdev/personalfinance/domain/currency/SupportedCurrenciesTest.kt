package com.elect3.mobdev.personalfinance.domain.currency

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SupportedCurrenciesTest {
    @Test fun baseCurrencyIsFirstAndEveryCodeIsAValidUniqueIsoCode() {
        val options = SupportedCurrencies.options("PHP")
        assertEquals("PHP", options.first().code)
        assertEquals(options.size, options.map { it.code }.distinct().size)
        assertTrue(options.all { it.code.matches(Regex("[A-Z]{3}")) && it.symbol.isNotBlank() && it.name.isNotBlank() })
        assertEquals(SupportedCurrencies.all.size, options.size)
        assertEquals("₱", SupportedCurrencies.symbol("PHP")); assertEquals("$", SupportedCurrencies.symbol("USD"))
        assertEquals("NZD", SupportedCurrencies.symbol("NZD")) // unknown codes fall back to the ISO code, never a guessed symbol
    }

    @Test fun anAccountBaseOutsideTheListIsStillOfferedFirstAndInvalidBasesFallBack() {
        assertEquals(listOf("SEK") + SupportedCurrencies.all.map { it.code }, SupportedCurrencies.options("SEK").map { it.code })
        assertEquals(SupportedCurrencies.all, SupportedCurrencies.options(null))
        assertEquals(SupportedCurrencies.all, SupportedCurrencies.options("usd"))
        assertTrue(SupportedCurrencies.isSupported("JPY")); assertFalse(SupportedCurrencies.isSupported("XYZ")); assertFalse(SupportedCurrencies.isSupported(null))
    }

    @Test fun expectedBaseAmountMatchesTheApiRoundingRuleExactly() {
        // round(amount × rate, 2, away from zero): the API's FinanceValidation.ConvertAmount.
        assertEquals(BigDecimal("581.23"), expectedBaseAmount(BigDecimal("10.00"), BigDecimal("58.12345678")))
        assertEquals(BigDecimal("0.01"), expectedBaseAmount(BigDecimal("0.05"), BigDecimal("0.10000000"))) // 0.005 rounds away from zero
        assertEquals(BigDecimal("0.00"), expectedBaseAmount(BigDecimal("0.01"), BigDecimal("0.00000001")))
        assertEquals(BigDecimal("726.54"), expectedBaseAmount(BigDecimal("12.5"), BigDecimal("58.12345678")))
        assertEquals(2, expectedBaseAmount(BigDecimal("3"), BigDecimal("1")).scale())
        // No floating point anywhere: a value that breaks doubles stays exact.
        assertEquals(BigDecimal("0.30"), expectedBaseAmount(BigDecimal("0.10"), BigDecimal("3.00000000")))
    }

    @Test fun expectedBaseAmountRejectsPrecisionTheApiRejects() {
        assertThrows(IllegalArgumentException::class.java) { expectedBaseAmount(BigDecimal("1.234"), BigDecimal("1")) }
        assertThrows(IllegalArgumentException::class.java) { expectedBaseAmount(BigDecimal("1.00"), BigDecimal("1.123456789")) }
        assertThrows(IllegalArgumentException::class.java) { expectedBaseAmount(BigDecimal.ZERO, BigDecimal("1")) }
        assertThrows(IllegalArgumentException::class.java) { expectedBaseAmount(BigDecimal("1.00"), BigDecimal("-1")) }
    }

    private val day = LocalDate.of(2026, 11, 15)
    private val rateDay = LocalDate.of(2026, 11, 14)
    private val rate = BigDecimal("58.12345678")
    private fun impact(currency: String = "USD", date: LocalDate = day, amount: String? = "10.00") =
        rateImpact("USD", day, BigDecimal("10.00"), rate, rateDay, currency, date, amount?.let(::BigDecimal), "PHP")

    @Test fun amountOnlyEditKeepsTheStoredRateAndDate() {
        assertEquals(RateImpact.KeepsStoredRate(rate, rateDay, BigDecimal("726.54")), impact(amount = "12.50"))
    }

    @Test fun currencyOrDateChangesNeedANewValidatedRateAndBaseCurrencyUsesTheSameCurrencyRule() {
        assertEquals(RateImpact.NeedsNewRate, impact(currency = "EUR"))
        assertEquals(RateImpact.NeedsNewRate, impact(date = day.minusDays(1)))
        assertEquals(RateImpact.NeedsNewRate, impact(currency = "EUR", amount = "99.00"))
        assertEquals(RateImpact.SameCurrency, impact(currency = "PHP"))
    }

    @Test fun insignificantScaleIsNotAnEdit() {
        assertEquals(RateImpact.Unchanged, impact(amount = "10"))
        assertEquals(RateImpact.Unchanged, impact(amount = "10.0"))
        assertEquals(RateImpact.Unchanged, impact(amount = null))
    }
}
