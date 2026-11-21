package com.elect3.mobdev.personalfinance.data.mock

import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.Budget
import com.elect3.mobdev.personalfinance.domain.repository.BudgetLimits
import com.elect3.mobdev.personalfinance.domain.repository.BudgetRepository
import com.elect3.mobdev.personalfinance.domain.repository.BudgetStatus
import com.elect3.mobdev.personalfinance.domain.repository.CategoryQuery
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import java.math.BigDecimal
import java.time.YearMonth

/**
 * Mock Demo budgets, in memory only (never sent anywhere; cleared with the demo session). Spending is
 * recomputed on every read with the API's rule: the month's EXPENSE transactions in the category, summed by
 * base amount (fixtures plus anything added during the demo). Status uses the API's BudgetStatusCalculator rule.
 */
class DemoBudgetStore(private val ledger: DemoLedger, private val categories: DemoCategoryStore) : BudgetRepository {
    private data class Row(val id: Int, val categoryId: Int, val month: YearMonth, val limits: BudgetLimits)
    private val lock = Any()
    private val rows = ArrayList<Row>()
    private var nextId = 1

    /** Seed: a few budgets for [month] using the sample categories, so Plan has something to show. */
    fun reset(month: YearMonth = YearMonth.now()) = synchronized(lock) {
        rows.clear(); nextId = 1
        listOf(5 to "1800.00", 3 to "3000.00", 6 to "1300.00", 4 to "1000.00", 7 to "1000.00").forEach { (category, limit) ->
            rows += Row(nextId++, category, month, BudgetLimits(BigDecimal(limit), 80))
        }
    }
    init { reset() }

    override suspend fun list(month: YearMonth): FinanceResult<List<Budget>> {
        val snapshot = synchronized(lock) { rows.filter { it.month == month } }
        val names = allCategories()
        return FinanceResult.Success(snapshot.mapNotNull { describe(it, names) })
    }

    override suspend fun create(categoryId: Int, month: YearMonth, limits: BudgetLimits): FinanceResult<Budget> {
        val names = allCategories()
        val category = names[categoryId]
        // Same rule as the API: an active expense category.
        if (category == null || category.second != TransactionType.Expense || !category.third) return FinanceResult.Failure(FinanceFailure.InvalidInput)
        val row = synchronized(lock) {
            if (rows.any { it.categoryId == categoryId && it.month == month }) return FinanceResult.Failure(FinanceFailure.Conflict)
            Row(nextId++, categoryId, month, limits).also { rows += it }
        }
        return describe(row, names)?.let { FinanceResult.Success(it) } ?: FinanceResult.Failure(FinanceFailure.InvalidResponse)
    }

    override suspend fun update(budgetId: Int, limits: BudgetLimits): FinanceResult<Budget> {
        val row = synchronized(lock) {
            val i = rows.indexOfFirst { it.id == budgetId }
            if (i < 0) return FinanceResult.Failure(FinanceFailure.NotFound)
            rows[i].copy(limits = limits).also { rows[i] = it }
        }
        return describe(row, allCategories())?.let { FinanceResult.Success(it) } ?: FinanceResult.Failure(FinanceFailure.InvalidResponse)
    }

    override suspend fun delete(budgetId: Int): FinanceResult<Unit> = synchronized(lock) {
        if (rows.removeAll { it.id == budgetId }) FinanceResult.Success(Unit) else FinanceResult.Failure(FinanceFailure.NotFound)
    }

    /** categoryId -> (name, type, active) from the demo category store. */
    private suspend fun allCategories(): Map<Int, Triple<String, TransactionType, Boolean>> =
        (categories.list(CategoryQuery(includeInactive = true, pageSize = 100)) as? FinanceResult.Success)?.value?.items
            ?.associate { it.categoryId to Triple(it.name, it.type, it.isActive) }.orEmpty()

    private fun describe(row: Row, names: Map<Int, Triple<String, TransactionType, Boolean>>): Budget? {
        val category = names[row.categoryId] ?: return null
        val spent = (TransactionFixtures.items + ledger.snapshot())
            .filter { it.type == TransactionType.Expense && it.categoryId == row.categoryId && YearMonth.from(it.transactionDate) == row.month }
            .fold(BigDecimal.ZERO) { acc, t -> acc.add(t.amountBase) }
        val limit = row.limits.limitAmount
        return Budget(row.id, row.categoryId, category.first, category.third, row.month, limit, row.limits.alertThresholdPct,
            spent, limit.subtract(spent), statusOf(spent, limit, row.limits.alertThresholdPct), "PHP")
    }

    companion object {
        /** The API's BudgetStatusCalculator: Exceeded if spent > limit; Warning if spent >= limit * pct / 100; else OK. */
        fun statusOf(spent: BigDecimal, limit: BigDecimal, pct: Int): BudgetStatus = when {
            spent > limit -> BudgetStatus.Exceeded
            spent >= limit.multiply(BigDecimal(pct)).divide(BigDecimal(100)) -> BudgetStatus.Warning
            else -> BudgetStatus.OK
        }
    }
}
