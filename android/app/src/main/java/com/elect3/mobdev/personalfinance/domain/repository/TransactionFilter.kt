package com.elect3.mobdev.personalfinance.domain.repository

import java.time.YearMonth

import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import java.time.LocalDate

/**
 * Transaction-list ordering supported end to end: the API sorts by transaction date with a transaction-ID
 * tie-breaker in the same direction, so pages stay stable. Amount ordering is intentionally absent: the API
 * orders by original-currency Amount (not the base amount), which is not a correct order for mixed currencies.
 */
enum class TransactionListSort { Newest, Oldest }

/**
 * Server-side filter for the transaction list. Every field maps to an existing `GET api/transactions`
 * query parameter (type, categoryId, fromDate, toDate, sortBy=date, sortDirection). No amount range is
 * offered because the API has none, and nothing is filtered client-side over partial pages.
 *
 * [categoryName] is display-only (for the applied-filter summary) and is never sent.
 */
data class TransactionFilter(
    val type: TransactionType? = null,
    val categoryId: Int? = null,
    val categoryName: String? = null,
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
    val sort: TransactionListSort = TransactionListSort.Newest,
) {
    init {
        require(categoryId == null || categoryId > 0) { "Invalid category filter" }
        require((categoryId == null) == (categoryName == null)) { "Category name must accompany the category" }
        require(listOfNotNull(fromDate, toDate).all { it.year in 1..9999 }) { "Invalid date filter" }
        require(fromDate == null || toDate == null || fromDate <= toDate) { "Invalid date range" }
    }

    /** True when the list shows every transaction in the default (newest first) order. */
    val isDefault: Boolean get() = this == TransactionFilter()

    /** True when any criterion narrows the dataset (sort alone does not). */
    val narrows: Boolean get() = type != null || categoryId != null || fromDate != null || toDate != null

    /** Both ends set: a bounded period (a month or a custom range) that may be read completely. */
    val isBounded: Boolean get() = fromDate != null && toDate != null

    /** The calendar month this filter covers exactly, or null. */
    val month: YearMonth? get() = fromDate?.let { from ->
        YearMonth.from(from).takeIf { from.dayOfMonth == 1 && toDate == it.atEndOfMonth() }
    }

    fun forMonth(month: YearMonth) = copy(fromDate = month.atDay(1), toDate = month.atEndOfMonth())
    fun allTime() = copy(fromDate = null, toDate = null)
    /** Removes the type, category and order criteria but keeps the period. */
    fun withoutCriteria() = TransactionFilter(fromDate = fromDate, toDate = toDate)
}

/**
 * A transaction source that applies [TransactionFilter] to the complete dataset before paging, so
 * `totalCount` and every page describe the filtered result set, never a filtered slice of one page.
 */
interface FilterableTransactionRepository : TransactionRepository {
    suspend fun readPage(page: Int, pageSize: Int, filter: TransactionFilter): TransactionPageResult
}
