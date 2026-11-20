package com.elect3.mobdev.personalfinance.domain.currency

import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * A live, read-only quote from the OWLI server (GET api/exchange-rates/quote). The server applies the same
 * provider and policy as transaction saving: rate with 8 decimals, converted amount rounded to 2 decimals.
 * A quote is never a saved transaction; a later save gets its own validated rate.
 */
data class ExchangeRateQuote(
    val from: String,
    val to: String,
    val amount: BigDecimal,
    val rate: BigDecimal,
    val rateDate: LocalDate,
    val requestedDate: LocalDate,
    val convertedAmount: BigDecimal,
    val source: String,
    val retrievedAt: Instant,
)

fun interface ExchangeRateQuoteRepository {
    suspend fun quote(from: String, to: String, amount: BigDecimal): FinanceResult<ExchangeRateQuote>
}
