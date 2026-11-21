package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.domain.repository.Budget
import com.elect3.mobdev.personalfinance.domain.repository.BudgetLimits
import com.elect3.mobdev.personalfinance.domain.repository.BudgetRepository
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.google.gson.stream.JsonWriter
import java.io.StringWriter
import java.time.YearMonth

internal object BudgetWriteJson {
    /** CreateBudgetRequest: exactly categoryId, periodMonth, limitAmount, alertThresholdPct (the API rejects unknown fields). */
    fun create(categoryId: Int, month: YearMonth, limits: BudgetLimits): String = write {
        name("categoryId").value(categoryId)
        name("periodMonth").value(month.atDay(1).toString())
        limits(limits)
    }
    /** UpdateBudgetRequest: exactly limitAmount and alertThresholdPct. */
    fun update(limits: BudgetLimits): String = write { limits(limits) }

    private fun JsonWriter.limits(l: BudgetLimits) {
        name("limitAmount").value(l.limitAmount)
        name("alertThresholdPct").value(l.alertThresholdPct)
    }
    private fun write(block: JsonWriter.() -> Unit): String {
        val out = StringWriter()
        JsonWriter(out).use { it.beginObject(); it.block(); it.endObject() }
        return out.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_CATEGORY_REQUEST_BYTES) }
    }
}

/** Existing api/budgets endpoints only. Figures come from the server's BudgetResponse unchanged. */
internal class RemoteBudgetRepository(
    private val transport: AuthenticatedFinanceTransport,
    private val configuration: ApiConfiguration,
    auth: InMemoryAuthRepository,
    engine: FinanceWriteEngine,
) : BudgetRepository {
    private val writer = FinanceWriter(auth, engine)

    override suspend fun list(month: YearMonth): FinanceResult<List<Budget>> {
        val all = ArrayList<Budget>()
        for (page in 1..MAX_PAGES) {
            val result = transport.get(FinanceResource.Budgets,
                queryString("periodMonth" to month.atDay(1), "page" to page, "pageSize" to PAGE_SIZE)) { FinanceJson.budgetPage(it, page, PAGE_SIZE, month) }
            when (result) {
                is FinanceResult.Failure -> return result
                is FinanceResult.Success -> {
                    val (items, total) = result.value
                    all += items
                    if (items.isEmpty() || all.size.toLong() >= total) {
                        return if (all.map { it.budgetId }.distinct().size == all.size) FinanceResult.Success(all)
                        else FinanceResult.Failure(FinanceFailure.InvalidResponse)
                    }
                }
            }
        }
        return FinanceResult.Failure(FinanceFailure.InvalidResponse)
    }

    override suspend fun create(categoryId: Int, month: YearMonth, limits: BudgetLimits): FinanceResult<Budget> {
        if (categoryId <= 0) return FinanceResult.Failure(FinanceFailure.InvalidInput)
        return writer.send(WriteMethod.POST, configuration.budgetWriteUrl(null), BudgetWriteJson.create(categoryId, month, limits), setOf(201, 200)) {
            FinanceJson.budget(it).also { b -> require(b.categoryId == categoryId && b.periodMonth == month && b.limitAmount.compareTo(limits.limitAmount) == 0) }
        }
    }

    override suspend fun update(budgetId: Int, limits: BudgetLimits): FinanceResult<Budget> {
        if (budgetId <= 0) return FinanceResult.Failure(FinanceFailure.InvalidInput)
        return writer.send(WriteMethod.PUT, configuration.budgetWriteUrl(budgetId), BudgetWriteJson.update(limits), setOf(200)) {
            FinanceJson.budget(it).also { b -> require(b.budgetId == budgetId && b.limitAmount.compareTo(limits.limitAmount) == 0 && b.alertThresholdPct == limits.alertThresholdPct) }
        }
    }

    override suspend fun delete(budgetId: Int): FinanceResult<Unit> {
        if (budgetId <= 0) return FinanceResult.Failure(FinanceFailure.InvalidInput)
        return writer.send(WriteMethod.DELETE, configuration.budgetWriteUrl(budgetId), null, setOf(204, 200)) { }
    }

    private companion object { const val PAGE_SIZE = 100; const val MAX_PAGES = 50 }
}
