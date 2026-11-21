package com.elect3.mobdev.personalfinance.ui.currency

import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.domain.currency.ConfirmedRate
import com.elect3.mobdev.personalfinance.domain.currency.ConverterResult
import com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrencies
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CurrencyConverterViewModelTest {
    private val usd = ConfirmedRate("USD", "PHP", BigDecimal("58.12345678"), LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15))
    private fun model(base: String? = "PHP", demo: Boolean = false, confirmed: List<ConfirmedRate> = listOf(usd), canRecord: Boolean = true) =
        CurrencyConverterViewModel(base, demo, confirmed, canRecord).also { ViewModelStore().put("c", it) }

    @Test fun signedInStartsFromAForeignCurrencyIntoTheAccountCurrency() {
        val s = model().state.value
        assertEquals("PHP", s.baseCurrency); assertEquals("USD", s.from); assertEquals("PHP", s.to)
        assertEquals(SupportedCurrencies.all.size, s.options.size); assertEquals("PHP", s.options.first().code)
        assertEquals(listOf(usd), s.confirmed); assertTrue(s.canRecordTransaction); assertFalse(s.demo)
        assertEquals(ConverterResult.EnterAmount, s.result)
    }

    @Test fun mockDemoIsPhpOnlyWithNoAccountRatesAndNoRecordAction() {
        val s = model(base = "USD", demo = true).state.value
        assertEquals("PHP", s.baseCurrency); assertTrue(s.demo)
        assertTrue(s.confirmed.isEmpty()); assertFalse(s.canRecordTransaction)
    }

    @Test fun onlySupportedCurrenciesCanBeSelectedAndSwapWorks() {
        val m = model()
        m.from("XYZ"); assertEquals("USD", m.state.value.from)
        m.to("jpy"); assertEquals("PHP", m.state.value.to)
        m.to("JPY"); assertEquals("JPY", m.state.value.to)
        m.swap(); assertEquals("JPY", m.state.value.from); assertEquals("USD", m.state.value.to)
    }

    @Test fun amountValidationAndResultStates() {
        val m = model()
        m.amount("abc"); assertEquals(ConverterResult.InvalidAmount, m.state.value.result)
        m.amount("12.345"); assertEquals(ConverterResult.InvalidAmount, m.state.value.result)
        m.amount("1".repeat(25)); assertEquals("12.345", m.state.value.amount) // overly long input is refused
        m.amount("100"); assertEquals(ConverterResult.QuoteUnavailable(usd), m.state.value.result) // historical reference only
        m.to("USD"); assertEquals(ConverterResult.SameCurrency(BigDecimal("100.00"), "USD"), m.state.value.result)
        m.from("EUR"); m.to("PHP"); assertEquals(ConverterResult.QuoteUnavailable(null), m.state.value.result)
    }

    @Test fun confirmedRatesForAnotherBaseAreNotShownAndUnknownBaseFallsBackToPhp() {
        val sek = model(base = "SEK", confirmed = listOf(usd)).state.value
        assertEquals("SEK", sek.baseCurrency); assertEquals("PHP", sek.from); assertEquals("SEK", sek.to)
        assertTrue(sek.confirmed.isEmpty()) // a PHP-based rate is never presented as a SEK rate
        assertEquals("PHP", model(base = null).state.value.baseCurrency)
        assertFalse(model(canRecord = false).state.value.canRecordTransaction)
    }
}
