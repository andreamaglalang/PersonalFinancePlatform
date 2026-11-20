package com.elect3.mobdev.personalfinance.domain.reports

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.Collections

/**
 * Financial report for one selected month plus a short monthly trend that ends at that month.
 *
 * Every money value is in [currency], the signed-in account's base currency, and is a sum of the server's
 * stored `amountBase` snapshots. The app never fetches, estimates or re-applies an exchange rate.
 */
class FinancialReport internal constructor(
    val currency: String,
    val selected: MonthlyReport,
    trend: List<MonthTrendPoint>,
) {
    /** Oldest first; always [ReportPeriod.TREND_MONTHS] consecutive months, the last being [selected]'s month. */
    val trend: List<MonthTrendPoint> = Collections.unmodifiableList(ArrayList(trend))

    init {
        require(this.trend.isNotEmpty() && this.trend.last().month == selected.month) { "Trend must end at the selected month" }
        require(this.trend.zipWithNext().all { (a, b) -> a.month.plusMonths(1) == b.month }) { "Trend months must be consecutive" }
        require(selected.currency == currency) { "Mixed report currencies" }
    }

    /** True when no transaction exists in the whole trend window (not just the selected month). */
    val windowIsEmpty: Boolean get() = trend.all { it.transactionCount == 0 }
}

class MonthlyReport internal constructor(
    val month: YearMonth,
    val currency: String,
    val totalIncome: BigDecimal,
    val totalExpenses: BigDecimal,
    val transactionCount: Int,
    expenseCategories: List<ReportCategoryTotal>,
    /** Rows in this month recorded in a currency other than [currency]; their totals use the stored base amount. */
    val foreignCurrencyCount: Int,
    foreignCurrencies: Collection<String>,
) {
    val netBalance: BigDecimal get() = totalIncome.subtract(totalExpenses)
    val isEmpty: Boolean get() = transactionCount == 0

    /** Ranked by exact total (descending), then name, then ID. Totals sum exactly to [totalExpenses]. */
    val expenseCategories: List<ReportCategoryTotal> = Collections.unmodifiableList(ArrayList(expenseCategories))

    /** Source currency codes other than [currency], sorted. */
    val foreignCurrencies: List<String> = Collections.unmodifiableList(foreignCurrencies.sorted())
}

data class ReportCategoryTotal(
    val categoryId: Int,
    val categoryName: String,
    val total: BigDecimal,
    val transactionCount: Int,
)

data class MonthTrendPoint(
    val month: YearMonth,
    val income: BigDecimal,
    val expenses: BigDecimal,
    val transactionCount: Int,
) {
    val net: BigDecimal get() = income.subtract(expenses)
}

/** Calendar rules for the reporting period: whole months only, never a future month. */
object ReportPeriod {
    /** Months in the trend chart, ending with the selected month. */
    const val TREND_MONTHS = 6

    /** The earliest month whose trend window still has valid (year >= 1) dates. */
    val EARLIEST: YearMonth = YearMonth.of(1, 1).plusMonths((TREND_MONTHS - 1).toLong())

    /** Inclusive date range read from the API for [month]'s report: the first day of the trend window to the month's last day. */
    fun window(month: YearMonth): ClosedRange<LocalDate> =
        month.minusMonths((TREND_MONTHS - 1).toLong()).atDay(1)..month.atEndOfMonth()

    fun trendMonths(month: YearMonth): List<YearMonth> =
        (TREND_MONTHS - 1 downTo 0).map { month.minusMonths(it.toLong()) }

    /** Clamp a requested month to the selectable range ending at [current]. */
    fun clamp(month: YearMonth, current: YearMonth): YearMonth = when {
        month > current -> current
        month < EARLIEST -> EARLIEST
        else -> month
    }

    fun canGoBack(month: YearMonth): Boolean = month > EARLIEST
    fun canGoForward(month: YearMonth, current: YearMonth): Boolean = month < current
}
