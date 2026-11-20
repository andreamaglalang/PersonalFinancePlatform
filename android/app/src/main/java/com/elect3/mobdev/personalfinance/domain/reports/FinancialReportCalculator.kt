package com.elect3.mobdev.personalfinance.domain.reports

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

/**
 * Pure aggregation of a verified, complete set of transactions for one report window.
 *
 * Totals always use the server's stored `amountBase` (already in the account's base currency); the original
 * `amount` and `currencyCode` are only checked for consistency with that snapshot. Nothing is converted here,
 * so a foreign-currency row is counted exactly once, at the value the API stored when it was recorded.
 *
 * Throws [IllegalArgumentException] for data that does not match the API contract.
 */
class FinancialReportCalculator {
    fun calculate(transactions: List<Transaction>, month: YearMonth, baseCurrency: String): FinancialReport {
        require(baseCurrency.matches(CURRENCY)) { "Invalid base currency" }
        require(transactions.map { it.transactionId }.toSet().size == transactions.size) { "Repeated transaction ID" }
        val window = ReportPeriod.window(month)
        transactions.forEach {
            validate(it, baseCurrency)
            require(it.transactionDate in window) { "Transaction outside the requested period" }
        }
        val byMonth = transactions.groupBy { YearMonth.from(it.transactionDate) }
        val trend = ReportPeriod.trendMonths(month).map { m ->
            val rows = byMonth[m].orEmpty()
            MonthTrendPoint(m, sum(rows, TransactionType.Income), sum(rows, TransactionType.Expense), rows.size)
        }
        val rows = byMonth[month].orEmpty()
        val foreign = rows.filter { it.currencyCode != baseCurrency }
        val selected = MonthlyReport(
            month = month,
            currency = baseCurrency,
            totalIncome = sum(rows, TransactionType.Income),
            totalExpenses = sum(rows, TransactionType.Expense),
            transactionCount = rows.size,
            expenseCategories = categories(rows),
            foreignCurrencyCount = foreign.size,
            foreignCurrencies = foreign.map { it.currencyCode }.toSet(),
        )
        return FinancialReport(baseCurrency, selected, trend)
    }

    private fun sum(rows: List<Transaction>, type: TransactionType): BigDecimal =
        rows.filter { it.type == type }.fold(ZERO) { acc, t -> acc.add(t.amountBase) }

    /** Grouped by category ID; the display name is the one on the category's newest expense in the month. */
    private fun categories(rows: List<Transaction>): List<ReportCategoryTotal> {
        val expenses = rows.filter { it.type == TransactionType.Expense }
            .sortedWith(compareByDescending<Transaction> { it.transactionDate }.thenByDescending { it.transactionId })
        return expenses.groupBy { it.categoryId }.map { (id, group) ->
            ReportCategoryTotal(id, group.first().categoryName, group.fold(ZERO) { acc, t -> acc.add(t.amountBase) }, group.size)
        }.sortedWith(compareByDescending<ReportCategoryTotal> { it.total }.thenBy { it.categoryName }.thenBy { it.categoryId })
    }

    /** Same snapshot rules the Dashboard verifies, against the account's base currency rather than a fixed one. */
    private fun validate(t: Transaction, base: String) {
        require(t.currencyCode.matches(CURRENCY)) { "Invalid source currency" }
        require(t.amount.signum() > 0 && t.amount <= MAX_MONEY && t.amountBase.signum() >= 0 && t.amountBase <= MAX_MONEY &&
            twoDecimals(t.amount) && twoDecimals(t.amountBase)) { "Invalid source or base amount" }
        require(t.fxRate.signum() > 0 && t.fxRate <= MAX_RATE && t.fxRate.stripTrailingZeros().scale() <= 8) { "Invalid FX snapshot rate" }
        require(t.transactionDate.year in 1..9999 && t.fxRateDate.year in 1..9999 && t.fxRateDate <= t.transactionDate) {
            "Invalid FX snapshot date"
        }
        if (t.currencyCode == base) {
            require(t.fxRate.compareTo(BigDecimal.ONE) == 0 && t.amountBase.compareTo(t.amount) == 0 && t.fxRateDate == t.transactionDate) {
                "Inconsistent same-currency snapshot"
            }
        }
        // Verifies the stored snapshot only (HALF_UP equals the API's AwayFromZero for positive values); never replaces it.
        require(t.amount.multiply(t.fxRate).setScale(2, RoundingMode.HALF_UP).compareTo(t.amountBase) == 0) {
            "Inconsistent base snapshot"
        }
    }

    private fun twoDecimals(value: BigDecimal) = value.stripTrailingZeros().scale() <= 2

    private companion object {
        val CURRENCY = Regex("[A-Z]{3}")
        val ZERO = BigDecimal("0.00")
        val MAX_MONEY = BigDecimal("9999999999999999.99")
        val MAX_RATE = BigDecimal("9999999999.99999999")
    }
}
