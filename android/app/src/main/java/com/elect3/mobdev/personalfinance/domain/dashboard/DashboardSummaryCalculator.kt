package com.elect3.mobdev.personalfinance.domain.dashboard

import com.elect3.mobdev.personalfinance.domain.model.DashboardSummary
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

/** Pure PHP mock-contract calculation. Caller must supply a complete snapshot. */
class DashboardSummaryCalculator {
    /** Defaults to the latest transaction month; an explicit month also supports historical reports. */
    fun calculate(
        transactions: List<Transaction>,
        fallbackMonth: YearMonth,
        reportingMonth: YearMonth? = null,
    ): DashboardSummary {
        require(transactions.map { it.transactionId }.toSet().size == transactions.size) {
            "Repeated transaction ID"
        }
        transactions.forEach(::validate)
        val month = reportingMonth ?: transactions.maxOfOrNull { it.transactionDate }
            ?.let(YearMonth::from) ?: fallbackMonth
        val start = month.atDay(1)
        val end = month.plusMonths(1).atDay(1)
        var income = BigDecimal("0.00")
        var expenses = BigDecimal("0.00")
        var monthlyIncome = BigDecimal("0.00")
        var monthlyExpenses = BigDecimal("0.00")
        for (transaction in transactions) {
            val inMonth = transaction.transactionDate >= start && transaction.transactionDate < end
            when (transaction.type) {
                TransactionType.Income -> {
                    income += transaction.amount
                    if (inMonth) monthlyIncome += transaction.amount
                }
                TransactionType.Expense -> {
                    expenses += transaction.amount
                    if (inMonth) monthlyExpenses += transaction.amount
                }
            }
        }
        return DashboardSummary(
            reportingCurrency = "PHP",
            totalIncome = income,
            totalExpenses = expenses,
            netTransactionFlow = income - expenses,
            reportingMonth = month,
            monthlyIncome = monthlyIncome,
            monthlyExpenses = monthlyExpenses,
            monthlyNetTransactionFlow = monthlyIncome - monthlyExpenses,
            recentTransactions = transactions.sortedWith(
                compareByDescending<Transaction> { it.transactionDate }.thenByDescending { it.transactionId },
            ).take(5),
            recordCount = transactions.size.toLong(),
        )
    }

    private fun validate(transaction: Transaction) {
        require(transaction.currencyCode == "PHP") { "Only PHP mock transactions are supported" }
        require(transaction.amount.signum() >= 0 && transaction.amountBase.signum() >= 0) {
            "Monetary magnitudes must not be negative"
        }
        require(hasPhpPrecision(transaction.amount) && hasPhpPrecision(transaction.amountBase)) {
            "PHP amounts must be exactly representable with two decimal places"
        }
        require(transaction.fxRate.compareTo(BigDecimal.ONE) == 0 &&
            transaction.amountBase.compareTo(transaction.amount) == 0 &&
            transaction.fxRateDate == transaction.transactionDate
        ) { "Expected the unconverted PHP mock contract" }
    }

    private fun hasPhpPrecision(amount: BigDecimal): Boolean = try {
        // Validation only: retain the original value and scale in calculations.
        amount.setScale(2, RoundingMode.UNNECESSARY)
        true
    } catch (_: ArithmeticException) {
        false
    }
}
