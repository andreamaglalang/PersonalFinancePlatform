package com.elect3.mobdev.personalfinance.domain.dashboard

import com.elect3.mobdev.personalfinance.domain.model.DashboardCategoryTotal
import com.elect3.mobdev.personalfinance.domain.model.DashboardSummary
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/** Complete-snapshot PHP summaries; demo source amounts and authenticated server base amounts stay distinct. */
class DashboardSummaryCalculator {
    /** Defaults to the latest transaction month; an explicit month also supports historical reports. */
    fun calculate(
        transactions: List<Transaction>,
        fallbackMonth: YearMonth,
        reportingMonth: YearMonth? = null,
    ): DashboardSummary = summarize(transactions, fallbackMonth, reportingMonth, useBaseAmounts = false)

    /** PHP account only: aggregate the validated server snapshot, retaining original rows and FX metadata. */
    fun calculatePhpAccount(
        transactions: List<Transaction>,
        fallbackMonth: YearMonth,
        reportingMonth: YearMonth? = null,
    ): DashboardSummary = summarize(transactions, fallbackMonth, reportingMonth, useBaseAmounts = true)

    private fun summarize(
        transactions: List<Transaction>,
        fallbackMonth: YearMonth,
        reportingMonth: YearMonth?,
        useBaseAmounts: Boolean,
    ): DashboardSummary {
        require(transactions.map { it.transactionId }.toSet().size == transactions.size) {
            "Repeated transaction ID"
        }
        transactions.forEach { if (useBaseAmounts) validatePhpAccount(it) else validate(it) }
        val month = reportingMonth ?: transactions.maxOfOrNull { it.transactionDate }
            ?.let(YearMonth::from) ?: fallbackMonth
        val start = month.atDay(1)
        val end = month.plusMonths(1).atDay(1)
        var income = BigDecimal("0.00")
        var expenses = BigDecimal("0.00")
        var monthlyIncome = BigDecimal("0.00")
        var monthlyExpenses = BigDecimal("0.00")
        var monthlyCount = 0L
        val monthlyExpenseByCategory = LinkedHashMap<Int, BigDecimal>()
        val expenseByCategory = LinkedHashMap<Int, BigDecimal>()
        for (transaction in transactions) {
            val reportingAmount = if (useBaseAmounts) transaction.amountBase else transaction.amount
            val inMonth = transaction.transactionDate >= start && transaction.transactionDate < end
            if (inMonth) monthlyCount++
            when (transaction.type) {
                TransactionType.Income -> {
                    income += reportingAmount
                    if (inMonth) monthlyIncome += reportingAmount
                }
                TransactionType.Expense -> {
                    expenses += reportingAmount
                    expenseByCategory.merge(transaction.categoryId, reportingAmount, BigDecimal::add)
                    if (inMonth) {
                        monthlyExpenses += reportingAmount
                        monthlyExpenseByCategory.merge(transaction.categoryId, reportingAmount, BigDecimal::add)
                    }
                }
            }
        }
        val newestFirst = transactions.sortedWith(
            compareByDescending<Transaction> { it.transactionDate }.thenByDescending { it.transactionId },
        )
        return DashboardSummary(
            reportingCurrency = "PHP",
            totalIncome = income,
            totalExpenses = expenses,
            netTransactionFlow = income - expenses,
            reportingMonth = month,
            monthlyIncome = monthlyIncome,
            monthlyExpenses = monthlyExpenses,
            monthlyNetTransactionFlow = monthlyIncome - monthlyExpenses,
            recentTransactions = newestFirst.take(5),
            recordCount = transactions.size.toLong(),
            monthlyRecordCount = monthlyCount,
            topExpenseCategories = topCategories(monthlyExpenseByCategory, newestFirst, start, end),
            expenseCategoryTotals = rankedCategories(expenseByCategory, newestFirst, null, null),
            monthlyExpenseCategoryTotals = rankedCategories(monthlyExpenseByCategory, newestFirst, start, end),
        )
    }

    /**
     * Every category with expenses in the window, ranked by exact total (descending; compareTo ignores scale),
     * then name, then ID. Display names follow the same rule as [topCategories]: the newest expense in the window.
     * A null window means the complete snapshot.
     */
    private fun rankedCategories(
        totals: Map<Int, BigDecimal>,
        newestFirst: List<Transaction>,
        start: LocalDate?,
        end: LocalDate?,
    ): List<DashboardCategoryTotal> {
        val names = HashMap<Int, String>()
        for (transaction in newestFirst) {
            if (transaction.type == TransactionType.Expense &&
                (start == null || transaction.transactionDate >= start) && (end == null || transaction.transactionDate < end)
            ) names.putIfAbsent(transaction.categoryId, transaction.categoryName)
        }
        return totals.map { (id, total) -> DashboardCategoryTotal(id, checkNotNull(names[id]), total) }
            .sortedWith(compareByDescending<DashboardCategoryTotal> { it.total }
                .thenBy { it.categoryName }.thenBy { it.categoryId })
    }

    /** All categories tied at the highest positive total; compareTo ignores decimal scale. */
    private fun topCategories(
        totals: Map<Int, BigDecimal>,
        newestFirst: List<Transaction>,
        start: LocalDate,
        end: LocalDate,
    ): List<DashboardCategoryTotal> {
        val highest = totals.values.filter { it.signum() > 0 }.maxWithOrNull(BigDecimal::compareTo)
            ?: return emptyList()
        // Display name comes from the category's newest reporting-month expense.
        val names = HashMap<Int, String>()
        for (transaction in newestFirst) {
            if (transaction.type == TransactionType.Expense &&
                transaction.transactionDate >= start && transaction.transactionDate < end
            ) names.putIfAbsent(transaction.categoryId, transaction.categoryName)
        }
        return totals.filter { it.value.compareTo(highest) == 0 }
            .map { (id, total) -> DashboardCategoryTotal(id, checkNotNull(names[id]), total) }
            .sortedWith(compareBy<DashboardCategoryTotal> { it.categoryName }.thenBy { it.categoryId })
    }

    private fun validatePhpAccount(transaction: Transaction) {
        val maximumMoney = BigDecimal("9999999999999999.99")
        val maximumRate = BigDecimal("9999999999.99999999")
        require(transaction.currencyCode.matches(Regex("[A-Z]{3}"))) { "Invalid source currency" }
        require(transaction.amount.signum() > 0 && transaction.amount <= maximumMoney &&
            transaction.amountBase.signum() >= 0 && transaction.amountBase <= maximumMoney &&
            hasPhpPrecision(transaction.amount) && hasPhpPrecision(transaction.amountBase)
        ) { "Invalid source or PHP base amount" }
        require(transaction.fxRate.signum() > 0 && transaction.fxRate <= maximumRate &&
            transaction.fxRate.stripTrailingZeros().scale() <= 8
        ) { "Invalid FX snapshot rate" }
        require(transaction.transactionDate.year in 1..9999 && transaction.fxRateDate.year in 1..9999 &&
            transaction.fxRateDate <= transaction.transactionDate
        ) { "Invalid FX snapshot date" }
        if (transaction.currencyCode == "PHP") {
            require(transaction.fxRate.compareTo(BigDecimal.ONE) == 0 &&
                transaction.amountBase.compareTo(transaction.amount) == 0 &&
                transaction.fxRateDate == transaction.transactionDate
            ) { "Inconsistent same-currency snapshot" }
        }
        // Verify the API's stored snapshot; never replace amountBase or fetch/recalculate an exchange rate.
        // For positive magnitudes HALF_UP matches FinanceValidation.ConvertAmount's AwayFromZero.
        require(transaction.amount.multiply(transaction.fxRate).setScale(2, RoundingMode.HALF_UP)
            .compareTo(transaction.amountBase) == 0) { "Inconsistent PHP base snapshot" }
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
