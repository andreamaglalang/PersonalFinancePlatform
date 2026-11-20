package com.elect3.mobdev.personalfinance.ui.reports

import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionAmount
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Exact report amount in the account's base currency (PHP as "₱1,234.50", others as "USD 1,234.50").
 * Shares the Activity formatter, so a report total and a transaction row always look alike.
 */
fun formatReportAmount(amount: BigDecimal, currency: String): String = formatTransactionAmount(amount, currency)

/** Net value with an explicit sign: "+₱500.00", "−₱500.00" (U+2212), or unsigned zero. */
fun formatSignedReportAmount(amount: BigDecimal, currency: String): String {
    val value = formatReportAmount(amount.abs(), currency)
    return when (amount.signum()) { 1 -> "+$value"; -1 -> "−$value"; else -> value }
}

/**
 * Short chart-axis label, e.g. "₱12.5K", "USD 1.2M". Display only; every exact figure is also shown in full text.
 */
fun formatCompactReportAmount(amount: BigDecimal, currency: String): String {
    val prefix = if (currency == "PHP") "₱" else "$currency "
    val magnitude = amount.abs()
    // Thresholds use the rounded value, so 999.6 reads "1K" (not "1000") and 999,960 reads "1M" (not "1000K").
    val whole = magnitude.setScale(0, RoundingMode.HALF_UP)
    val thousands = magnitude.divide(THOUSAND).setScale(1, RoundingMode.HALF_UP)
    val (digits, suffix) = when {
        whole < THOUSAND -> whole to ""
        thousands < THOUSAND -> thousands.stripTrailingZeros() to "K"
        else -> magnitude.divide(MILLION).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros() to "M"
    }
    val sign = if (amount.signum() < 0) "−" else ""
    return sign + prefix + digits.toPlainString() + suffix
}

/** Whole-number-and-one-decimal share of a total, e.g. "42.9%". A zero or negative total yields "0.0%". */
fun reportShareLabel(part: BigDecimal, total: BigDecimal): String {
    if (total.signum() <= 0) return "0.0%"
    return part.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP).toPlainString() + "%"
}

/** "November 2026". */
fun reportMonthLabel(month: YearMonth, locale: Locale = Locale.getDefault()): String =
    month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))

/** "Nov" — chart axis label. */
fun reportShortMonthLabel(month: YearMonth, locale: Locale = Locale.getDefault()): String =
    month.format(DateTimeFormatter.ofPattern("LLL", locale))

private val THOUSAND = BigDecimal(1_000)
private val MILLION = BigDecimal(1_000_000)
