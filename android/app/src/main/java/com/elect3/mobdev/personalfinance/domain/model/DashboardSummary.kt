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
) {
    val recentTransactions: List<Transaction> =
        Collections.unmodifiableList(ArrayList(recentTransactions))
}
