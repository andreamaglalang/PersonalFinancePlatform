package com.elect3.mobdev.personalfinance.domain.currency

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.ui.transactions.currency.isConfirmedSnapshot
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionAmount
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CurrencyConverterTest {
    private val day = LocalDate.of(2026, 11, 15)
    private fun tx(currency: String, amount: String, rate: String, base: String, rateDate: LocalDate = day.minusDays(1), date: LocalDate = day, id: Int = 1) =
        Transaction(id, UUID.randomUUID(), TransactionType.Expense, 2, "Travel", BigDecimal(amount), currency, BigDecimal(rate), rateDate,
            BigDecimal(base), date, null, Instant.parse("2026-11-15T01:02:03Z"), null, null)

    @Test fun amountInputFollowsTheTransactionMoneyRulesWithoutRounding() {
        assertEquals(BigDecimal("10.00"), CurrencyConverter.parseAmount("10"))
        assertEquals(BigDecimal("10.50"), CurrencyConverter.parseAmount(" 10,5 "))
        assertEquals(BigDecimal("9999999999999999.99"), CurrencyConverter.parseAmount("9999999999999999.99"))
        for (bad in listOf("", "0", "0.00", "-1", "1.234", "abc", "1e3", "12345678901234567", "1.", ".5", "1,2,3"))
            assertNull(bad, CurrencyConverter.parseAmount(bad))
    }

    @Test fun resultStatesAreEnterInvalidSameCurrencyOrHonestlyUnavailable() {
        assertEquals(ConverterResult.EnterAmount, CurrencyConverter.result("  ", "USD", "PHP", emptyList()))
        assertEquals(ConverterResult.InvalidAmount, CurrencyConverter.result("1.234", "USD", "PHP", emptyList()))
        assertEquals(ConverterResult.SameCurrency(BigDecimal("1234.50"), "PHP"), CurrencyConverter.result("1234.5", "PHP", "PHP", emptyList()))
        // Different currencies never produce a number: there is no quote endpoint and no estimated rate.
        assertEquals(ConverterResult.QuoteUnavailable(null), CurrencyConverter.result("100", "USD", "PHP", emptyList()))
    }

    @Test fun aConfirmedRateIsOnlyAHistoricalReferenceForTheExactPairAndNeverInverted() {
        val usd = ConfirmedRate("USD", "PHP", BigDecimal("58.12345678"), day.minusDays(1), day)
        val r = CurrencyConverter.result("100", "USD", "PHP", listOf(usd))
        assertEquals(ConverterResult.QuoteUnavailable(usd), r)
        assertEquals(ConverterResult.QuoteUnavailable(null), CurrencyConverter.result("100", "PHP", "USD", listOf(usd)))
        assertEquals(ConverterResult.QuoteUnavailable(null), CurrencyConverter.result("100", "EUR", "PHP", listOf(usd)))
        assertFalse(r.toString().contains("5812")) // no converted amount exists anywhere in the result
    }

    @Test fun confirmedRatesComeOnlyFromSelfConsistentServerSnapshots() {
        val rows = listOf(
            tx("USD", "10.00", "58.12345678", "581.23", rateDate = day.minusDays(1), id = 1),
            tx("USD", "20.00", "58.12345678", "1162.47", rateDate = day.minusDays(1), id = 2), // same rate and date: one entry
            tx("EUR", "5.00", "63.00000000", "315.00", rateDate = day.minusDays(3), id = 3),
            tx("PHP", "100.00", "1.00000000", "100.00", rateDate = day, id = 4),                  // base currency: no rate to show
            tx("JPY", "1000.00", "0.37000000", "999.99", id = 5),                                 // inconsistent snapshot: ignored
            tx("GBP", "1.00", "74.123456789", "74.12", id = 6),                                   // more than 8 rate decimals: ignored
            tx("CHF", "1.00", "66.00000000", "66.00", rateDate = day.plusDays(1), id = 7),        // rate dated after the transaction: ignored
        )
        val rates = CurrencyConverter.confirmedRates(rows, "PHP")
        assertEquals(listOf("USD", "EUR"), rates.map { it.currencyCode }) // newest rate date first
        assertEquals(BigDecimal("58.12345678"), rates.first().rate)
        assertEquals(8, rates.first().rate.scale())
        assertTrue(rates.all { it.baseCurrency == "PHP" })
        assertTrue(CurrencyConverter.confirmedRates(rows, null).isEmpty())
        assertEquals(1, CurrencyConverter.confirmedRates(rows, "PHP", limit = 1).size)
    }

    @Test fun conversionDetailsAreShownOnlyForTheApisOwnSnapshotAndNeverConvertTwice() {
        val ok = tx("USD", "10.25", "58.50000000", "599.63")
        assertTrue(isConfirmedSnapshot(ok))
        assertFalse(isConfirmedSnapshot(tx("USD", "10.25", "58.50000000", "599.62")))
        assertFalse(isConfirmedSnapshot(tx("USD", "10.25", "58.500000001", "599.63")))
        assertFalse(isConfirmedSnapshot(tx("USD", "10.25", "58.50000000", "599.63", rateDate = day.plusDays(1))))
        // The original is shown in its own currency and the base amount as stored: no second conversion.
        assertEquals("USD 10.25", formatTransactionAmount(ok.amount, ok.currencyCode))
        val base = formatTransactionAmount(ok.amountBase, "PHP")
        assertEquals(com.elect3.mobdev.personalfinance.ui.transactions.formatPhpAmount(BigDecimal("599.63")), base)
        assertTrue(base.contains("599.63"))
        assertEquals("JPY 1,000.00", formatTransactionAmount(BigDecimal("1000.00"), "JPY"))
    }
}
