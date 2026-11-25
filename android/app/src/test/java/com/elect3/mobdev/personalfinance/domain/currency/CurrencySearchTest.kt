package com.elect3.mobdev.personalfinance.domain.currency

import org.junit.Assert.*
import org.junit.Test

class CurrencySearchTest {
    @Test fun searchMatchesCodeNameAndSymbolWithoutChangingOfferedOrder() {
        val offered = SupportedCurrencies.options("PHP")
        assertEquals(offered, SupportedCurrencies.search(offered, "  "))
        assertEquals(listOf("USD"), SupportedCurrencies.search(offered, " usd ").map { it.code })
        assertEquals(listOf("PHP"), SupportedCurrencies.search(offered, "PESO").map { it.code })
        assertTrue(SupportedCurrencies.search(offered, "$" ).all { it.symbol.contains("$") })
        assertTrue(SupportedCurrencies.search(offered, "not-a-currency").isEmpty())
    }
    @Test fun searchNeverAddsUnsupportedRecordsAndRetainsCustomBase() {
        val offered = SupportedCurrencies.options("NZD")
        assertEquals("NZD", SupportedCurrencies.search(offered, "nzd").single().code)
        assertTrue(SupportedCurrencies.search(offered, "eur").all { it in offered })
        assertTrue(SupportedCurrencies.search(emptyList(), "USD").isEmpty())
    }
}
