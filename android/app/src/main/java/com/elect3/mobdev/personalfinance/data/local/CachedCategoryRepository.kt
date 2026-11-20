package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.data.local.room.LocalCategoryDao
import com.elect3.mobdev.personalfinance.data.local.room.LocalCategoryEntity
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.Category
import com.elect3.mobdev.personalfinance.domain.repository.CategoryPage
import com.elect3.mobdev.personalfinance.domain.repository.CategoryQuery
import com.elect3.mobdev.personalfinance.domain.repository.CategoryRepository
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Device-local, per-account copy of categories the server returned. Every call is scoped to an account ID. */
interface LocalCategoryCache {
    /** Adds or updates rows; never removes anything (a single page is not a complete list). */
    suspend fun merge(accountId: Int, categories: List<Category>)
    /** Reconciles a complete active list of [type]; omitted rows become inactive locally, never from partial pages. */
    suspend fun replaceType(accountId: Int, type: TransactionType, categories: List<Category>)
    suspend fun read(accountId: Int, type: TransactionType?, includeInactive: Boolean): List<Category>
}

private fun Category.toEntity(accountId: Int, now: Long) = LocalCategoryEntity(accountId, categoryId, type.wireValue, name,
    colorHex, isActive, isSystemDefault, now)

private fun LocalCategoryEntity.toCategory() = Category(categoryId, name, TransactionType.fromWireValue(type), colorHex,
    isActive, isSystemDefault)

/** Room-backed cache (app-private database, this device only). */
class RoomCategoryCache(private val dao: LocalCategoryDao, private val now: () -> Long = System::currentTimeMillis) : LocalCategoryCache {
    override suspend fun merge(accountId: Int, categories: List<Category>) {
        if (categories.isNotEmpty()) dao.upsert(categories.map { it.toEntity(accountId, now()) })
    }
    override suspend fun replaceType(accountId: Int, type: TransactionType, categories: List<Category>) {
        require(categories.all { it.type == type && it.isActive })
        dao.replaceType(accountId, type.wireValue, categories.map { it.toEntity(accountId, now()) })
    }
    override suspend fun read(accountId: Int, type: TransactionType?, includeInactive: Boolean) =
        dao.read(accountId, type?.wireValue, includeInactive).map { it.toCategory() }
}

/** Same contract in memory (JVM tests). */
class InMemoryCategoryCache : LocalCategoryCache {
    private val rows = LinkedHashMap<Pair<Int, Int>, Category>()
    private val lock = Any()
    override suspend fun merge(accountId: Int, categories: List<Category>) {
        synchronized(lock) { categories.forEach { rows[accountId to it.categoryId] = it } }
    }
    override suspend fun replaceType(accountId: Int, type: TransactionType, categories: List<Category>) {
        require(categories.all { it.type == type && it.isActive })
        synchronized(lock) {
            rows.keys.filter { (account, id) -> account == accountId && rows[account to id]?.type == type }
                .forEach { key -> rows[key] = rows.getValue(key).copy(isActive = false) }
            categories.forEach { rows[accountId to it.categoryId] = it }
        }
    }
    override suspend fun read(accountId: Int, type: TransactionType?, includeInactive: Boolean): List<Category> = synchronized(lock) {
        rows.filterKeys { it.first == accountId }.values
            .filter { (type == null || it.type == type) && (includeInactive || it.isActive) }
            .sortedWith(compareBy<Category, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.categoryId })
    }
}

/**
 * Session-bound category selection only. Category management keeps its raw online repository.
 * Online results are returned unchanged. Complete active snapshots reconcile stale cached rows; partial pages only merge.
 * A cold/empty cache preserves the original connectivity error. Reachable-server and authentication errors never fall back.
 */
class CachedCategoryRepository(
    private val remote: CategoryRepository,
    private val cache: LocalCategoryCache,
    private val accountId: () -> Int?,
    private val isSessionCurrent: () -> Boolean,
    private val sessionIdentity: () -> String?,
    /** Redacted debug-only diagnostics (closed vocabulary). Release and tests default to no-op. */
    private val diagnostics: CategoryDiagnosticSink = NoCategoryDiagnostics,
) : CategoryRepository {
    private val reads = Mutex()

    /** Diagnostics must never change the result: any sink failure is ignored. */
    private fun emit(record: CategoryDiagnosticRecord) {
        try { diagnostics.record(record) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
    }

    override suspend fun list(query: CategoryQuery): FinanceResult<CategoryPage> {
        val request = CategoryDiagnosticIds.next()
        fun failed(stage: CategoryRequestStage, failure: FinanceResult.Failure): FinanceResult.Failure {
            emit(CategoryDiagnosticRecord(CategoryDiagnosticEvent.CATEGORY_REMOTE_REQUEST_FAILED, request, query.page, query.type,
                stage = stage, reason = failure.reason, connectivity = failure.isConnectivityFailure))
            return failure
        }
        fun fallback(outcome: CategoryFallbackOutcome, rows: Int? = null) =
            emit(CategoryDiagnosticRecord(CategoryDiagnosticEvent.CATEGORY_ROOM_FALLBACK_COMPLETED, request, query.page, query.type,
                outcome = outcome, rows = rows))
        // Capture both identities before any suspended operation, including waiting for another read.
        val account = accountId() ?: return failed(CategoryRequestStage.BEFORE_REQUEST, FinanceResult.Failure(FinanceFailure.MissingSession))
        val session = sessionIdentity()?.takeIf { it.isNotBlank() }
            ?: return failed(CategoryRequestStage.BEFORE_REQUEST, FinanceResult.Failure(FinanceFailure.MissingSession))
        fun current() = account > 0 && isSessionCurrent() && accountId() == account && sessionIdentity() == session
        if (!current()) return failed(CategoryRequestStage.BEFORE_REQUEST, FinanceResult.Failure(FinanceFailure.SessionExpired))
        return reads.withLock {
            if (!current()) return@withLock failed(CategoryRequestStage.BEFORE_REQUEST, FinanceResult.Failure(FinanceFailure.SessionExpired))
            emit(CategoryDiagnosticRecord(CategoryDiagnosticEvent.CATEGORY_REMOTE_REQUEST_STARTED, request, query.page, query.type))
            val result = remote.list(query)
            if (result is FinanceResult.Failure) failed(CategoryRequestStage.REMOTE, result)
            if (!current()) return@withLock FinanceResult.Failure(FinanceFailure.SessionExpired)
            when (result) {
                is FinanceResult.Success -> {
                    val page = result.value
                    if (page.page != query.page || page.pageSize != query.pageSize ||
                        page.items.map { it.categoryId }.distinct().size != page.items.size ||
                        page.items.any { it.categoryId <= 0 || it.name.isBlank() || it.name.length > 50 ||
                            (query.type != null && it.type != query.type) || (!query.includeInactive && !it.isActive) }) {
                        return@withLock failed(CategoryRequestStage.RESPONSE_VALIDATION, FinanceResult.Failure(FinanceFailure.InvalidResponse))
                    }
                    try {
                        val completeActive = !query.includeInactive && page.page == 1 && page.items.size.toLong() == page.totalCount
                        if (completeActive) {
                            val types = query.type?.let { listOf(it) } ?: TransactionType.entries
                            for (type in types) {
                                if (!current()) return@withLock FinanceResult.Failure(FinanceFailure.SessionExpired)
                                cache.replaceType(account, type, page.items.filter { it.type == type })
                            }
                        } else cache.merge(account, page.items)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Local cache failure must not hide a valid online response. */ }
                    if (current()) result else FinanceResult.Failure(FinanceFailure.SessionExpired)
                }
                is FinanceResult.Failure -> {
                    val connectivity = result.reason == FinanceFailure.Timeout || result.reason == FinanceFailure.SecureConnection ||
                        (result.reason == FinanceFailure.Unavailable && result.isConnectivityFailure)
                    if (!connectivity) return@withLock result
                    val rows = try { cache.read(account, query.type, query.includeInactive) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        val ended = !current()
                        fallback(if (ended) CategoryFallbackOutcome.SESSION_ENDED else CategoryFallbackOutcome.CACHE_ERROR)
                        return@withLock if (!ended) result else FinanceResult.Failure(FinanceFailure.SessionExpired)
                    }
                    if (!current()) { fallback(CategoryFallbackOutcome.SESSION_ENDED); return@withLock FinanceResult.Failure(FinanceFailure.SessionExpired) }
                    if (rows.isEmpty()) { fallback(CategoryFallbackOutcome.EMPTY_CACHE); return@withLock result }
                    val from = (query.page - 1L) * query.pageSize
                    val items = if (from >= rows.size) emptyList() else rows.subList(from.toInt(), minOf(rows.size.toLong(), from + query.pageSize).toInt())
                    fallback(CategoryFallbackOutcome.SERVED, items.size)
                    FinanceResult.Success(CategoryPage(items, query.page, query.pageSize, rows.size.toLong()))
                }
            }
        }
    }
}
