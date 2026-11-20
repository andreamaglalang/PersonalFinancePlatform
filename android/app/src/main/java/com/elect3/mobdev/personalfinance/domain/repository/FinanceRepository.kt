package com.elect3.mobdev.personalfinance.domain.repository

import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import java.time.LocalDate
import java.util.Collections

/** Bounded categories only; never raw response bodies or exception messages. */
enum class FinanceFailure {
    NotConfigured, MissingSession, SessionExpired, InvalidInput, AccessDenied,
    NotFound, Conflict, RateLimited, Timeout, SecureConnection, Unavailable, InvalidResponse,
}
sealed interface FinanceResult<out T> {
    class Success<T>(val value: T) : FinanceResult<T>
    /** Only the transport may identify an I/O failure; HTTP 5xx keeps this false. */
    data class Failure(val reason: FinanceFailure, val isConnectivityFailure: Boolean = false) : FinanceResult<Nothing>
}
data class Category(
    val categoryId: Int, val name: String, val type: TransactionType,
    val colorHex: String?, val isActive: Boolean, val isSystemDefault: Boolean,
)
class CategoryPage(items: List<Category>, val page: Int, val pageSize: Int, val totalCount: Long) {
    val items: List<Category> = Collections.unmodifiableList(ArrayList(items))
    init {
        validPage(page, pageSize)
        require(totalCount >= 0 && this.items.size <= pageSize && this.items.size.toLong() <= totalCount) {
            "Invalid category pagination"
        }
    }
}
data class CategoryQuery(
    val type: TransactionType? = null, val includeInactive: Boolean = false,
    val page: Int = 1, val pageSize: Int = 50,
) { init { validPage(page, pageSize) } }
enum class TransactionSort(val wire: String) { Date("date"), Amount("amount"), CreatedAt("createdAt") }
enum class SortDirection(val wire: String) { Ascending("asc"), Descending("desc") }
data class TransactionQuery(
    val page: Int = 1, val pageSize: Int = 20, val type: TransactionType? = null,
    val categoryId: Int? = null, val fromDate: LocalDate? = null, val toDate: LocalDate? = null,
    val sortBy: TransactionSort = TransactionSort.Date,
    val sortDirection: SortDirection = SortDirection.Descending,
) {
    init {
        validPage(page, pageSize)
        require(categoryId == null || categoryId > 0) { "Invalid category filter" }
        require(listOfNotNull(fromDate, toDate).all { it.year in 1..9999 }) { "Invalid date filter" }
        require(fromDate == null || toDate == null || fromDate <= toDate) { "Invalid date range" }
    }
}
internal fun validPage(page: Int, pageSize: Int) {
    require(page >= 1 && pageSize in 1..100 && (page - 1L) * pageSize <= Int.MAX_VALUE) { "Invalid pagination" }
}
interface CategoryRepository {
    suspend fun list(query: CategoryQuery = CategoryQuery()): FinanceResult<CategoryPage>
}
