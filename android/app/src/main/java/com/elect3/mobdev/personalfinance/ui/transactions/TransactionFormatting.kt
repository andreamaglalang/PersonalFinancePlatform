package com.elect3.mobdev.personalfinance.ui.transactions

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/** Formats the original PHP value directly; rejects excess precision instead of rounding it. */
fun formatPhpAmount(amount: BigDecimal): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH"))
    formatter.currency = Currency.getInstance("PHP")
    formatter.minimumFractionDigits = 2
    formatter.maximumFractionDigits = 2
    formatter.roundingMode = RoundingMode.UNNECESSARY
    return formatter.format(amount)
}

/** Display original amounts, never convert or round. Backend money has at most two decimals. */
fun formatTransactionAmount(amount: BigDecimal, currencyCode: String, locale: Locale = Locale.forLanguageTag("en-PH")): String {
    require(currencyCode.matches(Regex("[A-Z]{3}"))) { "Invalid currency code" }
    if (currencyCode == "PHP") return formatPhpAmount(amount)
    val formatter = NumberFormat.getNumberInstance(locale)
    formatter.minimumFractionDigits = 2
    formatter.maximumFractionDigits = 2
    formatter.roundingMode = RoundingMode.UNNECESSARY
    // ISO code avoids ambiguous dollar symbols and supports backend-accepted codes
    // absent from a particular Android version's currency catalogue.
    return "$currencyCode ${formatter.format(amount)}"
}

/** A date-only value is never converted through an instant or time zone. */
fun formatTransactionDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
