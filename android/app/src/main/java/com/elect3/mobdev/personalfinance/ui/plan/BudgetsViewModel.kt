package com.elect3.mobdev.personalfinance.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.Budget
import com.elect3.mobdev.personalfinance.domain.repository.BudgetLimits
import com.elect3.mobdev.personalfinance.domain.repository.BudgetRepository
import com.elect3.mobdev.personalfinance.domain.repository.Category
import com.elect3.mobdev.personalfinance.domain.repository.CategoryQuery
import com.elect3.mobdev.personalfinance.domain.repository.CategoryRepository
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.MAX_MONEY
import com.elect3.mobdev.personalfinance.domain.repository.validBudgetLimit
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Plan summary, from the server's per-budget figures only: totals are sums of limits and of spent amounts.
 * "Per day" is plain division of what is left by the days remaining (today included) — arithmetic, not a forecast.
 * Null when there are no budgets or when budgets disagree on currency (sums would be meaningless).
 */
data class BudgetSummary(
    val totalLimit: BigDecimal,
    val totalSpent: BigDecimal,
    /** Total limit minus total spent; negative when the budgets together are overspent. */
    val left: BigDecimal,
    val currency: String,
    /** Days left in the month including today; null for a month other than the current one. */
    val daysLeft: Int?,
    /** max(left, 0) / daysLeft, rounded half-up to centavos; null when [daysLeft] is null. */
    val perDay: BigDecimal?,
) {
    /** Share of the total limit used, 0..1+ (drawing only). */
    val usedFraction: Float get() = totalSpent.divide(totalLimit, 4, RoundingMode.HALF_UP).toFloat()
}

fun budgetSummary(budgets: List<Budget>, month: YearMonth, today: LocalDate): BudgetSummary? {
    if (budgets.isEmpty() || budgets.map { it.baseCurrency }.distinct().size != 1) return null
    val limit = budgets.fold(BigDecimal.ZERO) { a, b -> a.add(b.limitAmount) }
    val spent = budgets.fold(BigDecimal.ZERO) { a, b -> a.add(b.spentAmount) }
    val left = limit.subtract(spent)
    val days = if (YearMonth.from(today) == month) month.lengthOfMonth() - today.dayOfMonth + 1 else null
    val perDay = days?.let { left.max(BigDecimal.ZERO).divide(BigDecimal(it), 2, RoundingMode.HALF_UP) }
    return BudgetSummary(limit, spent, left, budgets.first().baseCurrency, days, perDay)
}

/** Whole percent of the limit used, rounded down (100% is shown only once the limit is reached). */
fun percentUsed(b: Budget): Int = b.spentAmount.multiply(BigDecimal(100)).divide(b.limitAmount, 0, RoundingMode.FLOOR).toInt()

/** Parses a typed amount exactly ("1,250.50" allowed); null if it is not a valid API limit. */
fun parseLimit(raw: String): BigDecimal? {
    val clean = raw.trim().replace(",", "")
    if (!clean.matches(Regex("[0-9]{1,16}(\\.[0-9]{1,2})?"))) return null
    return BigDecimal(clean).takeIf { validBudgetLimit(it) && it <= MAX_MONEY }
}

enum class BudgetFormError { Amount, Category, Duplicate, Rejected, Unknown, Failed, NotFound }
enum class BudgetNotice { Created, Updated, Deleted, DeleteBlocked }

data class BudgetsState(
    val month: YearMonth,
    val loading: Boolean = true,
    val error: FinanceFailure? = null,
    val budgets: List<Budget> = emptyList(),
    /** Active expense categories, for the create form. */
    val categories: List<Category> = emptyList(),
    val saving: Boolean = false,
    val formError: BudgetFormError? = null,
    /** Increments on each confirmed write so the UI can close its sheet. */
    val savedCount: Int = 0,
    val notice: BudgetNotice? = null,
    val invalidated: Boolean = false,
) {
    val loaded get() = !loading && error == null
    /** Categories that do not have a budget for this month yet (one per category per month). */
    val availableCategories get() = categories.filter { c -> budgets.none { it.categoryId == c.categoryId } }
}

class BudgetsViewModel(
    private val budgets: BudgetRepository,
    private val categories: CategoryRepository,
    month: YearMonth,
    private val isSessionCurrent: () -> Boolean = { true },
    private val onSessionExpired: () -> Unit = {},
) : ViewModel() {
    private val mutable = MutableStateFlow(BudgetsState(month))
    val state = mutable.asStateFlow()
    private var load: Job? = null
    private var write: Job? = null

    init { refresh() }

    fun refresh() {
        if (mutable.value.invalidated) return
        load?.cancel()
        mutable.value = mutable.value.copy(loading = mutable.value.budgets.isEmpty() || mutable.value.error != null, error = null)
        load = viewModelScope.launch {
            val month = mutable.value.month
            val result = safely { budgets.list(month) }
            if (!isSessionCurrent()) { expire(); return@launch }
            when (result) {
                is FinanceResult.Success -> {
                    val cats = safely { categories.list(CategoryQuery(type = TransactionType.Expense, pageSize = 100)) }
                    if (cats is FinanceResult.Failure && cats.reason.isSession()) { expire(); return@launch }
                    mutable.value = mutable.value.copy(loading = false,
                        budgets = result.value.sortedWith(compareBy({ it.categoryName.lowercase() }, { it.budgetId })),
                        categories = (cats as? FinanceResult.Success)?.value?.items?.filter { it.isActive && it.type == TransactionType.Expense }.orEmpty())
                }
                is FinanceResult.Failure -> {
                    if (result.reason.isSession()) { expire(); return@launch }
                    mutable.value = mutable.value.copy(loading = false, error = result.reason)
                }
            }
        }
    }

    fun clearFormError() { mutable.value = mutable.value.copy(formError = null) }
    fun consumeNotice() { mutable.value = mutable.value.copy(notice = null) }

    fun create(categoryId: Int?, rawLimit: String, alertPct: Int) {
        val limit = parseLimit(rawLimit)
        when {
            categoryId == null -> fail(BudgetFormError.Category)
            limit == null -> fail(BudgetFormError.Amount)
            else -> run(BudgetNotice.Created) { budgets.create(categoryId, mutable.value.month, BudgetLimits(limit, alertPct)) }
        }
    }

    fun update(budgetId: Int, rawLimit: String, alertPct: Int) {
        val limit = parseLimit(rawLimit) ?: return fail(BudgetFormError.Amount)
        run(BudgetNotice.Updated) { budgets.update(budgetId, BudgetLimits(limit, alertPct)) }
    }

    fun delete(budgetId: Int) = run(BudgetNotice.Deleted, deleting = true) { budgets.delete(budgetId) }

    private fun fail(error: BudgetFormError) { mutable.value = mutable.value.copy(formError = error) }

    private fun run(success: BudgetNotice, deleting: Boolean = false, block: suspend () -> FinanceResult<*>) {
        if (mutable.value.saving || mutable.value.invalidated) return
        if (!isSessionCurrent()) { expire(); return }
        mutable.value = mutable.value.copy(saving = true, formError = null)
        write = viewModelScope.launch {
            val result = safely { block() }
            if (!isSessionCurrent()) { expire(); return@launch }
            when (result) {
                is FinanceResult.Success -> {
                    mutable.value = mutable.value.copy(saving = false, savedCount = mutable.value.savedCount + 1, notice = success)
                    refresh() // Server figures are the source of truth: reread after every confirmed write.
                }
                is FinanceResult.Failure -> {
                    if (result.reason.isSession()) { expire(); return@launch }
                    val error = when (result.reason) {
                        FinanceFailure.Conflict -> if (deleting) null else BudgetFormError.Duplicate
                        FinanceFailure.InvalidInput -> BudgetFormError.Rejected
                        FinanceFailure.NotFound -> BudgetFormError.NotFound
                        // The request may have been applied without a confirmed reply: reload instead of guessing.
                        FinanceFailure.Timeout, FinanceFailure.InvalidResponse, FinanceFailure.Unavailable -> BudgetFormError.Unknown
                        else -> BudgetFormError.Failed
                    }
                    mutable.value = if (error == null) mutable.value.copy(saving = false, notice = BudgetNotice.DeleteBlocked, savedCount = mutable.value.savedCount + 1)
                        else mutable.value.copy(saving = false, formError = error)
                    if (error == BudgetFormError.Unknown || error == BudgetFormError.NotFound || error == BudgetFormError.Duplicate) refresh()
                }
            }
        }
    }

    private suspend fun <T> safely(block: suspend () -> FinanceResult<T>): FinanceResult<T> =
        try { block() } catch (c: CancellationException) { throw c } catch (_: Exception) { FinanceResult.Failure(FinanceFailure.Unavailable) }

    private fun expire() {
        if (mutable.value.invalidated) return
        load?.cancel(); write?.cancel()
        mutable.value = BudgetsState(mutable.value.month, loading = false, invalidated = true)
        onSessionExpired()
    }
}

private fun FinanceFailure.isSession() = this == FinanceFailure.MissingSession || this == FinanceFailure.SessionExpired
