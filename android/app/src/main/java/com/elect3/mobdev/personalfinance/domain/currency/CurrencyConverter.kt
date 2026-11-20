package com.elect3.mobdev.personalfinance.domain.currency

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * An exchange rate the OWLI server already applied to one of the account's own transactions. It is authoritative
 * for its rate date only; the converter shows it as historical reference and never applies it to a new amount.
 */
data class ConfirmedRate(
    val currencyCode: String,
    val baseCurrency: String,
    val rate: BigDecimal,
    val rateDate: LocalDate,
    val transactionDate: LocalDate,
)

/** What the converter can honestly show for the current input. */
sealed interface ConverterResult {
    data object EnterAmount : ConverterResult
    data object InvalidAmount : ConverterResult
    /** Same source and target currency: no conversion is needed (the API's same-currency rule is rate 1). */
    data class SameCurrency(val amount: BigDecimal, val currencyCode: String) : ConverterResult
    /**
     * Different currencies: the API has no read-only quote endpoint, so no converted value exists to show.
     * [reference] is the most recent rate the server confirmed on this account's transactions for exactly this
     * pair (foreign -> account currency), if any; it is historical and labelled as such.
     */
    data class QuoteUnavailable(val reference: ConfirmedRate?) : ConverterResult
}

object CurrencyConverter {
    private val maximum = BigDecimal("9999999999999999.99")
    private val pattern = Regex("[0-9]{1,16}([.,][0-9]{1,2})?")

    /** Same money rules as transactions: positive, at most 16 integer digits and 2 decimals. Never rounds. */
    fun parseAmount(raw: String): BigDecimal? {
        val text = raw.trim()
        if (!text.matches(pattern)) return null
        val value = BigDecimal(text.replace(',', '.'))
        if (value.signum() <= 0 || value > maximum) return null
        return value.setScale(2, RoundingMode.UNNECESSARY)
    }

    /**
     * Distinct rates from server-confirmed transactions in a currency other than [baseCurrency], newest rate date
     * first. A row whose stored snapshot is not self-consistent (base = round(amount × rate, 2)) is ignored, so only
     * the API's own figures are ever shown.
     */
    fun confirmedRates(transactions: List<Transaction>, baseCurrency: String?, limit: Int = 8): List<ConfirmedRate> {
        val base = baseCurrency?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: return emptyList()
        return transactions.asSequence()
            .filter { it.currencyCode != base && it.currencyCode.matches(Regex("[A-Z]{3}")) }
            .filter { it.fxRate.signum() > 0 && it.fxRate.stripTrailingZeros().scale() <= 8 && it.fxRateDate <= it.transactionDate }
            .filter { it.amount.multiply(it.fxRate).setScale(2, RoundingMode.HALF_UP).compareTo(it.amountBase) == 0 }
            .map { ConfirmedRate(it.currencyCode, base, it.fxRate.setScale(8, RoundingMode.UNNECESSARY), it.fxRateDate, it.transactionDate) }
            .distinctBy { Triple(it.currencyCode, it.rateDate, it.rate) }
            .sortedWith(compareByDescending<ConfirmedRate> { it.rateDate }.thenBy { it.currencyCode })
            .take(limit)
            .toList()
    }

    fun result(raw: String, from: String, to: String, confirmed: List<ConfirmedRate>): ConverterResult {
        if (raw.isBlank()) return ConverterResult.EnterAmount
        val amount = parseAmount(raw) ?: return ConverterResult.InvalidAmount
        if (from == to) return ConverterResult.SameCurrency(amount, from)
        // Only the exact direction the server confirmed (foreign -> account currency); never inverted or chained.
        return ConverterResult.QuoteUnavailable(confirmed.firstOrNull { it.currencyCode == from && it.baseCurrency == to })
    }
}
