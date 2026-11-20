package com.elect3.mobdev.personalfinance.domain.repository

import java.math.BigDecimal
import java.time.YearMonth

/** Server status (BudgetStatusCalculator): Exceeded when spent > limit, Warning at or above the alert %, else OK. */
enum class BudgetStatus { OK, Warning, Exceeded }

/**
 * Mirrors the API BudgetResponse. Spent, remaining and status are computed by the server from the month's
 * expense transactions in the category (base-currency amounts); the app displays them and never recomputes them.
 */
data class Budget(
    val budgetId: Int,
    val categoryId: Int,
    val categoryName: String,
    val isCategoryActive: Boolean,
    val periodMonth: YearMonth,
    val limitAmount: BigDecimal,
    val alertThresholdPct: Int,
    val spentAmount: BigDecimal,
    /** Limit minus spent; negative when over budget. */
    val remainingAmount: BigDecimal,
    val status: BudgetStatus,
    val baseCurrency: String,
)

/** Largest amount the API accepts (decimal(18,2)). */
val MAX_MONEY: BigDecimal = BigDecimal("9999999999999999.99")

/** Same rule as the API's ValidateMoney: positive, at most two decimals, within decimal(18,2). */
fun validBudgetLimit(amount: BigDecimal): Boolean =
    amount.signum() > 0 && amount <= MAX_MONEY && amount.stripTrailingZeros().scale() <= 2

data class BudgetLimits(val limitAmount: BigDecimal, val alertThresholdPct: Int) {
    init {
        require(validBudgetLimit(limitAmount)) { "Invalid limit" }
        require(alertThresholdPct in 1..100) { "Invalid alert threshold" }
    }
}

/** api/budgets. One budget per expense category per month. */
interface BudgetRepository {
    /** Every budget for [month] (all pages). */
    suspend fun list(month: YearMonth): FinanceResult<List<Budget>>
    suspend fun create(categoryId: Int, month: YearMonth, limits: BudgetLimits): FinanceResult<Budget>
    suspend fun update(budgetId: Int, limits: BudgetLimits): FinanceResult<Budget>
    /** Conflict when the server keeps notification history for the budget. */
    suspend fun delete(budgetId: Int): FinanceResult<Unit>
}
