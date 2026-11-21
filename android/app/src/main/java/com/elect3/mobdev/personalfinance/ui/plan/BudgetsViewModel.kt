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
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
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

enum class BudgetFormError {
    Amount, Category, Duplicate, Rejected, Unknown, Failed, NotFound,
    /** PUT 409: the budget changed on the server during the request. The list is reloaded. */
    Changed,
    /** The shown figures could not be refreshed; writes wait for a successful reload. */
    Stale,
}
enum class BudgetNotice { Created, Updated, Deleted, DeleteBlocked }

data class BudgetsState(
    val month: YearMonth,
    val loading: Boolean = true,
    val error: FinanceFailure? = null,
    val budgets: List<Budget> = emptyList(),
    /** Active expense categories, for the create form. */
    val categories: List<Category> = emptyList(),
    /** Categories could not be read: the create form says so instead of claiming every category is budgeted. */
    val categoriesFailed: Boolean = false,
    val saving: Boolean = false,
    val formError: BudgetFormError? = null,
    /** Increments on each confirmed write so the UI can close its sheet. */
    val savedCount: Int = 0,
    val notice: BudgetNotice? = null,
    val invalidated: Boolean = false,
    /** Device time of the last successful read of [budgets] for [month]. */
    val loadedAt: LocalDateTime? = null,
    /**
     * A reload failed while figures for [month] were already shown (offline, timeout, server error). They stay
     * visible, labelled with [loadedAt], but read-only: budgets have no offline queue, so nothing is written until
     * a reload succeeds.
     */
    val staleReason: FinanceFailure? = null,
    /** Account base currency for the create form when the month has no budgets yet (null if unknown). */
    val accountCurrency: String? = null,
    val minMonth: YearMonth = month,
    val maxMonth: YearMonth = month,
) {
    val loaded get() = !loading && error == null
    val stale get() = staleReason != null
    val canWrite get() = loaded && !stale && !saving && !invalidated
    val canGoBack get() = month > minMonth && !saving
    val canGoForward get() = month < maxMonth && !saving
    /** Categories that do not have a budget for this month yet (one per category per month). */
    val availableCategories get() = categories.filter { c -> budgets.none { it.categoryId == c.categoryId } }
    /** Currency shown on the create form: the month's server figures first, then the account's. */
    val formCurrency: String? get() = budgets.firstOrNull()?.baseCurrency ?: accountCurrency
}

/** Months reachable from the month Plan opened on: two years back for review, one year ahead for planning. */
const val BUDGET_MONTHS_BACK = 24L
const val BUDGET_MONTHS_AHEAD = 12L

class BudgetsViewModel(
    private val budgets: BudgetRepository,
    private val categories: CategoryRepository,
    month: YearMonth,
    private val isSessionCurrent: () -> Boolean = { true },
    private val onSessionExpired: () -> Unit = {},
    private val accountCurrency: () -> String? = { null },
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val mutable = MutableStateFlow(BudgetsState(month,
        minMonth = month.minusMonths(BUDGET_MONTHS_BACK), maxMonth = month.plusMonths(BUDGET_MONTHS_AHEAD)))
    val state = mutable.asStateFlow()
    private var load: Job? = null
    private var write: Job? = null

    init { refresh() }

    /** Shows another month (within [BudgetsState.minMonth]..[BudgetsState.maxMonth]); ignored while a write runs. */
    fun selectMonth(target: YearMonth) {
        val s = mutable.value
        if (s.invalidated || s.saving || target == s.month || target < s.minMonth || target > s.maxMonth) return
        load?.cancel()
        // Never show one month's figures under another month's label.
        mutable.value = s.copy(month = target, loading = true, error = null, budgets = emptyList(), formError = null,
            notice = null, loadedAt = null, staleReason = null)
        refresh()
    }

    fun refresh() {
        if (mutable.value.invalidated) return
        load?.cancel()
        mutable.value = mutable.value.copy(loading = mutable.value.loadedAt == null, error = null)
        load = viewModelScope.launch {
            val month = mutable.value.month
            val result = safely { budgets.list(month) }
            if (!isSessionCurrent()) { expire(); return@launch }
            when (result) {
                is FinanceResult.Success -> {
                    val cats = loadCategories()
                    if (cats is FinanceResult.Failure && cats.reason.isSession()) { expire(); return@launch }
                    if (mutable.value.month != month) return@launch
                    mutable.value = mutable.value.copy(loading = false, error = null, staleReason = null,
                        loadedAt = LocalDateTime.now(clock), accountCurrency = accountCurrency(),
                        budgets = result.value.sortedWith(compareBy({ it.categoryName.lowercase() }, { it.budgetId })),
                        categoriesFailed = cats is FinanceResult.Failure,
                        categories = (cats as? FinanceResult.Success)?.value.orEmpty().filter { it.isActive && it.type == TransactionType.Expense })
                }
                is FinanceResult.Failure -> {
                    if (result.reason.isSession()) { expire(); return@launch }
                    if (mutable.value.month != month) return@launch
                    // Keep figures already shown for this month (labelled stale) rather than replacing them with an error.
                    mutable.value = if (mutable.value.loadedAt != null) mutable.value.copy(loading = false, staleReason = result.reason)
                        else mutable.value.copy(loading = false, error = result.reason)
                }
            }
        }
    }

    /** Every active expense category (all pages, bounded). */
    private suspend fun loadCategories(): FinanceResult<List<Category>> {
        val all = ArrayList<Category>()
        for (page in 1..MAX_CATEGORY_PAGES) {
            when (val r = safely { categories.list(CategoryQuery(type = TransactionType.Expense, page = page, pageSize = CATEGORY_PAGE_SIZE)) }) {
                is FinanceResult.Failure -> return r
                is FinanceResult.Success -> {
                    all += r.value.items
                    if (r.value.items.isEmpty() || all.size.toLong() >= r.value.totalCount) return FinanceResult.Success(all.distinctBy { it.categoryId })
                }
            }
        }
        return FinanceResult.Success(all.distinctBy { it.categoryId })
    }

    fun clearFormError() { mutable.value = mutable.value.copy(formError = null) }
    fun consumeNotice() { mutable.value = mutable.value.copy(notice = null) }

    fun create(categoryId: Int?, rawLimit: String, alertPct: Int) {
        val limit = parseLimit(rawLimit)
        when {
            categoryId == null -> fail(BudgetFormError.Category)
            limit == null -> fail(BudgetFormError.Amount)
            alertPct !in 1..100 -> fail(BudgetFormError.Rejected)
            else -> run(BudgetNotice.Created, conflict = BudgetFormError.Duplicate) {
                budgets.create(categoryId, mutable.value.month, BudgetLimits(limit, alertPct))
            }
        }
    }

    fun update(budgetId: Int, rawLimit: String, alertPct: Int) {
        val limit = parseLimit(rawLimit) ?: return fail(BudgetFormError.Amount)
        if (alertPct !in 1..100) return fail(BudgetFormError.Rejected)
        run(BudgetNotice.Updated, conflict = BudgetFormError.Changed) { budgets.update(budgetId, BudgetLimits(limit, alertPct)) }
    }

    /** A delete conflict is reported as blocked (notification history); the list is reloaded either way. */
    fun delete(budgetId: Int) = run(BudgetNotice.Deleted, conflict = null) { budgets.delete(budgetId) }

    private fun fail(error: BudgetFormError) { mutable.value = mutable.value.copy(formError = error) }

    private fun run(success: BudgetNotice, conflict: BudgetFormError?, block: suspend () -> FinanceResult<*>) {
        if (mutable.value.saving || mutable.value.invalidated) return
        if (!isSessionCurrent()) { expire(); return }
        // Never write against figures that could not be refreshed (no offline queue exists for budgets).
        if (mutable.value.stale) return fail(BudgetFormError.Stale)
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
                        FinanceFailure.Conflict -> conflict
                        FinanceFailure.InvalidInput -> BudgetFormError.Rejected
                        FinanceFailure.NotFound -> BudgetFormError.NotFound
                        // The request may have been applied without a confirmed reply: reload instead of guessing.
                        FinanceFailure.Timeout, FinanceFailure.InvalidResponse, FinanceFailure.Unavailable -> BudgetFormError.Unknown
                        else -> BudgetFormError.Failed
                    }
                    mutable.value = if (error == null) mutable.value.copy(saving = false, notice = BudgetNotice.DeleteBlocked, savedCount = mutable.value.savedCount + 1)
                        else mutable.value.copy(saving = false, formError = error)
                    if (error == null || error in RELOAD_AFTER) refresh()
                }
            }
        }
    }

    private suspend fun <T> safely(block: suspend () -> FinanceResult<T>): FinanceResult<T> =
        try { block() } catch (c: CancellationException) { throw c } catch (_: Exception) { FinanceResult.Failure(FinanceFailure.Unavailable) }

    private fun expire() {
        if (mutable.value.invalidated) return
        load?.cancel(); write?.cancel()
        val s = mutable.value
        mutable.value = BudgetsState(s.month, loading = false, invalidated = true, minMonth = s.minMonth, maxMonth = s.maxMonth)
        onSessionExpired()
    }

    private companion object {
        const val CATEGORY_PAGE_SIZE = 100
        const val MAX_CATEGORY_PAGES = 10
        val RELOAD_AFTER = setOf(BudgetFormError.Unknown, BudgetFormError.NotFound, BudgetFormError.Duplicate, BudgetFormError.Changed)
    }
}

private fun FinanceFailure.isSession() = this == FinanceFailure.MissingSession || this == FinanceFailure.SessionExpired
