package com.elect3.mobdev.personalfinance.domain.model

import java.math.BigDecimal
import java.time.YearMonth
import java.util.Collections

/** Complete transaction history totals, never an account balance. */
class DashboardSummary internal constructor(
    val reportingCurrency: String,
    val totalIncome: BigDecimal,
    val totalExpenses: BigDecimal,
    val netTransactionFlow: BigDecimal,
    val reportingMonth: YearMonth,
    val monthlyIncome: BigDecimal,
    val monthlyExpenses: BigDecimal,
    val monthlyNetTransactionFlow: BigDecimal,
    recentTransactions: List<Transaction>,
    val recordCount: Long,
    /** Transactions dated within [reportingMonth]. */
    val monthlyRecordCount: Long = 0,
    topExpenseCategories: List<DashboardCategoryTotal> = emptyList(),
) {
    val recentTransactions: List<Transaction> =
        Collections.unmodifiableList(ArrayList(recentTransactions))

    /**
     * Every category tied for the highest positive expense total within [reportingMonth],
     * ordered by name then ID. Empty when the month has no positive expenses.
     */
    val topExpenseCategories: List<DashboardCategoryTotal> =
        Collections.unmodifiableList(ArrayList(topExpenseCategories))
}

/** Exact reporting-month expense total for one category, grouped by category ID. */
data class DashboardCategoryTotal(val categoryId: Int, val categoryName: String, val total: BigDecimal)
