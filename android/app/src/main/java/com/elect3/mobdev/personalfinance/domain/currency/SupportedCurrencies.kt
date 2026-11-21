package com.elect3.mobdev.personalfinance.domain.currency

import java.math.BigDecimal
import java.math.RoundingMode

/** A currency the Add/Edit Transaction forms may offer. */
data class SupportedCurrency(val code: String, val symbol: String, val name: String)

/**
 * Transaction currencies offered by the app.
 *
 * The API accepts any ISO code its exchange-rate provider (Frankfurter, ECB reference rates) supports and offers no
 * endpoint that lists them, so the app offers a fixed set of major currencies that are all published in the ECB
 * reference set, which includes PHP. The server stays authoritative: if it rejects a currency or date, the form shows
 * that rejection; the app never substitutes a rate.
 */
object SupportedCurrencies {
    val all: List<SupportedCurrency> = listOf(
        SupportedCurrency("PHP", "₱", "Philippine peso"),
        SupportedCurrency("USD", "$", "US dollar"),
        SupportedCurrency("EUR", "€", "Euro"),
        SupportedCurrency("JPY", "¥", "Japanese yen"),
        SupportedCurrency("GBP", "£", "British pound"),
        SupportedCurrency("AUD", "A$", "Australian dollar"),
        SupportedCurrency("CAD", "C$", "Canadian dollar"),
        SupportedCurrency("SGD", "S$", "Singapore dollar"),
        SupportedCurrency("HKD", "HK$", "Hong Kong dollar"),
        SupportedCurrency("CNY", "CN¥", "Chinese yuan"),
        SupportedCurrency("KRW", "₩", "South Korean won"),
        SupportedCurrency("CHF", "CHF", "Swiss franc"),
    )
    private val byCode = all.associateBy { it.code }

    fun find(code: String?): SupportedCurrency? = code?.let { byCode[it] }
    fun isSupported(code: String?): Boolean = code != null && code in byCode

    /** The account's base currency first, then the others. A base currency outside the list is still offered (first). */
    fun options(baseCurrency: String?): List<SupportedCurrency> {
        val base = baseCurrency?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: return all
        val head = byCode[base] ?: SupportedCurrency(base, base, base)
        return listOf(head) + all.filter { it.code != base }
    }

    fun symbol(code: String): String = byCode[code]?.symbol ?: code
}

/**
 * Mirrors the API's FinanceValidation.ConvertAmount for an already-stored (8-decimal) rate:
 * round(amount × rate, 2, away from zero). Amounts are positive, so HALF_UP is the same as the API's AwayFromZero.
 * Used only to preview an amount-only edit, where the API keeps the stored rate. It never invents a rate.
 */
fun expectedBaseAmount(amount: BigDecimal, storedRate: BigDecimal): BigDecimal {
    require(amount.signum() > 0 && amount.stripTrailingZeros().scale() <= 2) { "Invalid amount" }
    require(storedRate.signum() > 0 && storedRate.stripTrailingZeros().scale() <= 8) { "Invalid stored rate" }
    return amount.multiply(storedRate).setScale(2, RoundingMode.HALF_UP)
}

/** How an edit affects the stored exchange-rate snapshot, following the API's update rules. */
sealed interface RateImpact {
    /** Nothing that affects conversion changed. */
    data object Unchanged : RateImpact
    /** Amount-only edit: the API keeps the stored rate and date and recomputes the base amount with it. */
    data class KeepsStoredRate(val rate: BigDecimal, val rateDate: java.time.LocalDate, val expectedBase: BigDecimal) : RateImpact
    /** Currency or date changed to the base currency: the API's same-currency rule applies (rate 1). */
    data object SameCurrency : RateImpact
    /** Currency or date changed: the API must obtain a validated rate for the new currency and date when saving. */
    data object NeedsNewRate : RateImpact
}

fun rateImpact(
    originalCurrency: String, originalDate: java.time.LocalDate, originalAmount: BigDecimal,
    storedRate: BigDecimal, storedRateDate: java.time.LocalDate,
    currency: String, date: java.time.LocalDate, amount: BigDecimal?, baseCurrency: String?,
): RateImpact = when {
    currency != originalCurrency || date != originalDate -> if (currency == baseCurrency) RateImpact.SameCurrency else RateImpact.NeedsNewRate
    amount != null && amount.compareTo(originalAmount) != 0 ->
        RateImpact.KeepsStoredRate(storedRate, storedRateDate, expectedBaseAmount(amount, storedRate))
    else -> RateImpact.Unchanged
}
